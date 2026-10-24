package com.piggie.tv.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.api.HttpRequestFailure
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.api.NativeRequestScope
import com.piggie.tv.data.api.SessionOrigin
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.SecureSessionStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickConnectActivity : AppCompatActivity() {
    private val api by lazy { JellyfinNativeApi(this) }
    private val store by lazy { SecureSessionStore(this) }
    private val stateController = QuickConnectStateController()

    private lateinit var server: String
    private lateinit var serverView: TextView
    private lateinit var codeView: TextView
    private lateinit var statusView: TextView
    private lateinit var generateButton: Button
    private lateinit var cancelButton: Button

    private var autoStarted = false
    private var activeAttempt: ActiveQuickConnectAttempt? = null
    private var pollJob: Job? = null
    private var timeoutJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = intent.getStringExtra(SERVER_EXTRA)?.takeIf(String::isNotBlank) ?: run {
            finish()
            return
        }

        setContentView(R.layout.activity_quick_connect)
        serverView = findViewById(R.id.quick_connect_server)
        codeView = findViewById(R.id.quick_connect_code)
        statusView = findViewById(R.id.quick_connect_status)
        generateButton = findViewById(R.id.quick_connect_generate_button)
        cancelButton = findViewById(R.id.quick_connect_cancel_button)

        serverView.text = safeServerLabel(server)
        generateButton.setOnClickListener { startNewAttempt() }
        cancelButton.setOnClickListener {
            cancelActiveAttempt(renderCancelled = true)
            finish()
        }
        render(stateController.snapshot())
    }

    override fun onStart() {
        super.onStart()
        if (!::server.isInitialized) return
        if (!autoStarted) {
            autoStarted = true
            startNewAttempt()
        }
    }

    override fun onStop() {
        cancelActiveAttempt(renderCancelled = !isFinishing)
        super.onStop()
    }

    override fun onDestroy() {
        cancelActiveAttempt(renderCancelled = false)
        super.onDestroy()
    }

    private fun startNewAttempt() {
        cancelActiveAttempt(renderCancelled = false)
        val requested = stateController.beginAttempt()
        val requestScope = NativeRequestScope()
        val authAttempt = AuthSessionCoordinator.beginAttempt(requestScope::cancel)
        val attempt = ActiveQuickConnectAttempt(
            localGeneration = requested.generation,
            authAttempt = authAttempt,
            requestScope = requestScope,
            startedAtMs = SystemClock.elapsedRealtime().coerceAtLeast(1L),
        )
        activeAttempt = attempt
        render(requested)

        pollJob = lifecycleScope.launch { runAttempt(attempt) }
        timeoutJob = lifecycleScope.launch {
            delay(QuickConnectPollingPolicy.TIMEOUT_MS)
            expireActiveAttempt(attempt)
        }
    }

    private suspend fun runAttempt(attempt: ActiveQuickConnectAttempt) {
        try {
            val ticket = executeScoped(attempt) { api.initiateQuickConnect(server) }
            if (!owns(attempt)) return
            if (!stateController.waitingForApproval(attempt.localGeneration, ticket.code)) {
                failActiveAttempt(attempt, QuickConnectFailure.INVALID_RESPONSE)
                return
            }
            render(stateController.snapshot())

            var pollNetworkFailures = 0
            while (owns(attempt) && currentCoroutineContext().isActive) {
                if (QuickConnectPollingPolicy.hasTimedOut(attempt.startedAtMs, SystemClock.elapsedRealtime())) {
                    expireActiveAttempt(attempt)
                    return
                }

                delay(QuickConnectPollingPolicy.POLL_INTERVAL_MS)
                if (!owns(attempt)) return

                val status = try {
                    executeScoped(attempt) { api.getQuickConnectStatus(server, ticket.secret) }
                } catch (error: HttpRequestFailure) {
                    if (!owns(attempt)) return
                    when (QuickConnectPollingPolicy.outcomeFor(error.statusCode)) {
                        QuickConnectPollOutcome.WAITING -> {
                            pollNetworkFailures = 0
                            stateController.waitingForApproval(attempt.localGeneration)
                            render(stateController.snapshot())
                            continue
                        }
                        QuickConnectPollOutcome.EXPIRED -> {
                            expireActiveAttempt(attempt)
                            return
                        }
                        QuickConnectPollOutcome.FAILED -> {
                            failActiveAttempt(attempt, QuickConnectFailure.SERVER)
                            return
                        }
                    }
                } catch (error: IOException) {
                    if (!owns(attempt)) return
                    pollNetworkFailures += 1
                    if (QuickConnectPollingPolicy.shouldRetryNetworkFailure(pollNetworkFailures)) {
                        stateController.waitingForApproval(attempt.localGeneration)
                        render(stateController.snapshot())
                        continue
                    }
                    failActiveAttempt(attempt, QuickConnectFailure.NETWORK)
                    return
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    if (owns(attempt)) failActiveAttempt(attempt, QuickConnectFailure.INVALID_RESPONSE)
                    return
                }

                if (!owns(attempt)) return
                pollNetworkFailures = 0
                if (!status.authenticated) {
                    stateController.waitingForApproval(attempt.localGeneration)
                    render(stateController.snapshot())
                    continue
                }

                if (!stateController.approved(attempt.localGeneration)) return
                render(stateController.snapshot())
                if (!stateController.authenticating(attempt.localGeneration)) return
                render(stateController.snapshot())

                val session = try {
                    executeScoped(attempt) {
                        api.authenticateWithQuickConnect(server, ticket.secret)
                    }
                } catch (error: HttpRequestFailure) {
                    if (!owns(attempt)) return
                    if (error.statusCode == 404) {
                        expireActiveAttempt(attempt)
                    } else {
                        failActiveAttempt(attempt, QuickConnectFailure.SERVER)
                    }
                    return
                } catch (error: IOException) {
                    if (!owns(attempt)) return
                    // The server may have accepted the one-time secret even when its response was
                    // lost. Retrying could create an orphaned duplicate session, so final exchange
                    // is single-claim and ambiguous failures require a newly generated code.
                    failActiveAttempt(attempt, QuickConnectFailure.NETWORK)
                    return
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    if (owns(attempt)) failActiveAttempt(attempt, QuickConnectFailure.INVALID_RESPONSE)
                    return
                }

                if (!owns(attempt)) return
                completeAttempt(attempt, session)
                return
            }
        } catch (_: CancellationException) {
            // Expected for cancellation, regeneration, lifecycle exit, or timeout.
        } catch (_: HttpRequestFailure) {
            if (owns(attempt)) failActiveAttempt(attempt, QuickConnectFailure.SERVER)
        } catch (_: IOException) {
            if (owns(attempt)) failActiveAttempt(attempt, QuickConnectFailure.NETWORK)
        } catch (_: Throwable) {
            if (owns(attempt)) failActiveAttempt(attempt, QuickConnectFailure.INVALID_RESPONSE)
        } finally {
            if (activeAttempt === attempt && !AuthSessionCoordinator.isCurrent(attempt.authAttempt)) {
                stateController.cancel(attempt.localGeneration)
                releaseAttempt(attempt, cancelCoordinator = false)
                render(stateController.snapshot())
            }
        }
    }

    private fun completeAttempt(attempt: ActiveQuickConnectAttempt, session: NativeSession) {
        var persistenceInvoked = false
        val result = stateController.complete(attempt.localGeneration) {
            val committed = AuthSessionCoordinator.commitAuthenticated(attempt.authAttempt, session) {
                persistenceInvoked = true
                store.save(session, SessionOrigin.QUICK_CONNECT)
            }
            when {
                committed && AuthSessionCoordinator.isValidated(session) -> QuickConnectCommitResult.SUCCESS
                committed -> QuickConnectCommitResult.STALE
                persistenceInvoked -> QuickConnectCommitResult.STORAGE_FAILURE
                else -> QuickConnectCommitResult.STALE
            }
        }

        when (result) {
            QuickConnectCommitResult.SUCCESS -> {
                releaseAttempt(attempt, cancelCoordinator = false)
                render(stateController.snapshot())
                if (
                    AuthSessionCoordinator.isValidated(session) &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    startActivity(Intent(this, PtvHostActivity::class.java))
                    finishAffinity()
                }
            }
            QuickConnectCommitResult.STORAGE_FAILURE -> {
                releaseAttempt(attempt, cancelCoordinator = true)
                render(stateController.snapshot())
            }
            QuickConnectCommitResult.STALE -> {
                releaseAttempt(attempt, cancelCoordinator = false)
                render(stateController.snapshot())
            }
        }
    }

    private suspend fun <T> executeScoped(
        attempt: ActiveQuickConnectAttempt,
        action: () -> T,
    ): T = withContext(Dispatchers.IO) {
        api.withRequestScope(attempt.requestScope, action)
    }

    private fun owns(attempt: ActiveQuickConnectAttempt): Boolean =
        activeAttempt === attempt &&
            stateController.isCurrent(attempt.localGeneration) &&
            AuthSessionCoordinator.isCurrent(attempt.authAttempt) &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun expireActiveAttempt(attempt: ActiveQuickConnectAttempt) {
        if (activeAttempt !== attempt) return
        val changed = stateController.expire(attempt.localGeneration)
        releaseAttempt(attempt, cancelCoordinator = true)
        if (changed) render(stateController.snapshot())
    }

    private fun failActiveAttempt(
        attempt: ActiveQuickConnectAttempt,
        failure: QuickConnectFailure,
    ) {
        if (activeAttempt !== attempt) return
        val changed = stateController.fail(attempt.localGeneration, failure)
        releaseAttempt(attempt, cancelCoordinator = true)
        if (changed) render(stateController.snapshot())
    }

    private fun cancelActiveAttempt(renderCancelled: Boolean) {
        val attempt = activeAttempt ?: return
        val changed = stateController.cancel(attempt.localGeneration)
        releaseAttempt(attempt, cancelCoordinator = true)
        if (renderCancelled && changed) render(stateController.snapshot())
    }

    private fun releaseAttempt(
        attempt: ActiveQuickConnectAttempt,
        cancelCoordinator: Boolean,
    ) {
        if (activeAttempt !== attempt) return
        activeAttempt = null
        val polling = pollJob
        pollJob = null
        val timeout = timeoutJob
        timeoutJob = null
        polling?.cancel()
        timeout?.cancel()
        attempt.requestScope.cancel()
        if (cancelCoordinator) AuthSessionCoordinator.cancelIfCurrent(attempt.authAttempt)
    }

    private fun render(snapshot: QuickConnectSnapshot) {
        codeView.text = snapshot.code ?: getString(R.string.native_login_quick_connect_code_placeholder)
        statusView.setText(
            when (snapshot.state) {
                QuickConnectState.IDLE -> R.string.native_login_quick_connect_idle
                QuickConnectState.REQUESTING -> R.string.native_login_quick_connect_generating
                QuickConnectState.WAITING_FOR_APPROVAL -> R.string.native_login_quick_connect_waiting
                QuickConnectState.APPROVED,
                QuickConnectState.AUTHENTICATING,
                QuickConnectState.SUCCESS -> R.string.native_login_quick_connect_success
                QuickConnectState.EXPIRED -> R.string.native_login_quick_connect_expired
                QuickConnectState.CANCELLED -> R.string.native_login_quick_connect_cancelled
                QuickConnectState.FAILED -> when (snapshot.failure) {
                    QuickConnectFailure.SECURE_STORAGE -> R.string.native_login_quick_connect_storage_failed
                    else -> R.string.native_login_quick_connect_unavailable
                }
            }
        )
        generateButton.isEnabled = snapshot.state != QuickConnectState.SUCCESS
        cancelButton.isEnabled = snapshot.state != QuickConnectState.SUCCESS
    }

    private fun safeServerLabel(value: String): String = runCatching {
        val parsed = Uri.parse(value)
        val scheme = parsed.scheme?.lowercase().orEmpty()
        val host = parsed.host.orEmpty()
        check(scheme.isNotBlank() && host.isNotBlank())
        buildString {
            append(scheme)
            append("://")
            append(host)
            if (parsed.port >= 0) append(":${parsed.port}")
        }
    }.getOrDefault(getString(R.string.native_login_quick_connect_server_fallback))

    private data class ActiveQuickConnectAttempt(
        val localGeneration: Long,
        val authAttempt: AuthAttempt,
        val requestScope: NativeRequestScope,
        val startedAtMs: Long,
    )

    private companion object {
        const val SERVER_EXTRA = "server"
    }
}
