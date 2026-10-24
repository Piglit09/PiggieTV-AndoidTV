package com.piggie.tv.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.api.NativeRequestScope
import com.piggie.tv.data.api.SessionOrigin
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.NativeSettings
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.data.session.SessionReadResult
import com.piggie.tv.diagnostics.DiagnosticsExperiment
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import com.piggie.tv.util.JellyfinServerUrl
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val api by lazy { JellyfinNativeApi(this) }
    private val store by lazy { SecureSessionStore(this) }

    private lateinit var serverInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var passwordAction: Button
    private lateinit var quickConnectAction: Button
    private lateinit var statusText: TextView

    private var activeAttempt: AuthAttempt? = null
    private var authenticationBusy = false
    private var quickConnectLaunched = false
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.piggie.tv.util.CrashReporter.init(this)
        configureDebugOptions()
        setContentView(R.layout.activity_main)
        bindLoginForm()

        if (intent.getBooleanExtra(EXTRA_STORAGE_CLEAR_FAILED, false)) {
            showConnectionRecovery(AuthFailureCategory.SECURE_STORAGE_FAILURE)
            return
        }

        when (val result = store.readResult()) {
            is SessionReadResult.Available -> restoreStoredSession(result.session, result.origin)
            SessionReadResult.Missing -> {
                AuthSessionCoordinator.invalidateAll()
                showLoginForm()
            }
            SessionReadResult.InvalidCleared -> {
                AuthSessionCoordinator.invalidateAll()
                showLoginForm("The saved sign-in was incomplete. Please sign in again.")
            }
            SessionReadResult.StorageFailure -> {
                AuthSessionCoordinator.invalidateAll()
                showConnectionRecovery(AuthFailureCategory.SECURE_STORAGE_FAILURE)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (quickConnectLaunched) {
            quickConnectLaunched = false
            showLoginForm()
        }
    }

    override fun onStop() {
        activeAttempt?.let { attempt ->
            AuthSessionCoordinator.cancelIfCurrent(attempt)
            activeAttempt = null
            if (!isFinishing) setAuthenticationBusy(false, "Login was cancelled.")
        }
        if (::passwordInput.isInitialized) passwordInput.text?.clear()
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        activeAttempt?.let(AuthSessionCoordinator::cancelIfCurrent)
        activeAttempt = null
        if (::passwordInput.isInitialized) passwordInput.text?.clear()
        super.onDestroy()
    }

    private fun bindLoginForm() {
        serverInput = findViewById(R.id.server_input)
        usernameInput = findViewById(R.id.username_input)
        passwordInput = findViewById(R.id.password_input)
        passwordInput.isSaveEnabled = false
        passwordAction = findViewById(R.id.shell_launch_button)
        quickConnectAction = findViewById(R.id.quick_connect_button)
        statusText = findViewById(R.id.status_text)

        restoreNormalizedServer()?.let(serverInput::setText)

        passwordAction.setOnClickListener { beginPasswordLogin() }
        quickConnectAction.setOnClickListener { beginQuickConnect() }
    }

    private fun restoreStoredSession(session: NativeSession, origin: SessionOrigin) {
        val decision = AuthFailurePolicy.initialRestore(session)
        if (decision.destination != SessionRestoreDestination.VALIDATE) {
            if (decision.clearAuthentication) {
                val cleared = AuthLogout.clearLocalAuthentication(store, api::cancelInFlightRequests)
                if (!cleared) {
                    showConnectionRecovery(AuthFailureCategory.SECURE_STORAGE_FAILURE)
                    return
                }
            }
            showLoginForm("The saved sign-in was incomplete. Please sign in again.")
            return
        }

        serverInput.setText(session.serverUrl)
        setAuthenticationBusy(true, "Checking your saved sign-in…")
        val requestScope = NativeRequestScope()
        val attempt = AuthSessionCoordinator.beginAttempt(requestScope::cancel)
        activeAttempt = attempt

        thread(name = "ptv-session-restore") {
            runCatching {
                api.withRequestScope(requestScope) { api.validateSession(session) }
            }.onSuccess { refreshed ->
                runOnUiThread {
                    if (!canCommitFromUi(attempt)) return@runOnUiThread
                    val committed = runCatching {
                        AuthSessionCoordinator.commitAuthenticated(attempt, refreshed) {
                            store.save(refreshed, origin)
                        }
                    }.getOrDefault(false)
                    if (committed && AuthSessionCoordinator.isValidated(refreshed)) {
                        activeAttempt = null
                        launchAuthenticatedHost()
                    } else if (AuthSessionCoordinator.isCurrent(attempt)) {
                        AuthSessionCoordinator.cancelIfCurrent(attempt)
                        activeAttempt = null
                        showConnectionRecovery(AuthFailureCategory.SECURE_STORAGE_FAILURE)
                    }
                }
            }.onFailure { error ->
                handleRestoreFailure(attempt, session, error)
            }
        }
    }

    private fun handleRestoreFailure(attempt: AuthAttempt, session: NativeSession, error: Throwable) {
        if (!AuthSessionCoordinator.isCurrent(attempt)) return
        val decision = AuthFailurePolicy.validationFailure(error)
        runOnUiThread {
            if (destroyed || !AuthSessionCoordinator.isCurrent(attempt)) return@runOnUiThread
            when (decision.destination) {
                SessionRestoreDestination.LOGIN -> {
                    // The server URL is safe, useful recovery state and remains outside credential
                    // storage. Never persist raw input from the login form.
                    rememberNormalizedServer(session.serverUrl)
                    serverInput.setText(session.serverUrl)
                    val cleared = AuthLogout.clearLocalAuthentication(store, api::cancelInFlightRequests)
                    activeAttempt = null
                    if (cleared) {
                        showLoginForm("Your saved sign-in expired. Please sign in again.")
                    } else {
                        showConnectionRecovery(AuthFailureCategory.SECURE_STORAGE_FAILURE)
                    }
                }

                SessionRestoreDestination.CONNECTION_RECOVERY -> {
                    AuthSessionCoordinator.cancelIfCurrent(attempt)
                    activeAttempt = null
                    showConnectionRecovery(decision.category ?: AuthFailureCategory.UNKNOWN)
                }

                SessionRestoreDestination.NONE -> {
                    activeAttempt = null
                }

                SessionRestoreDestination.VALIDATE -> Unit
            }
        }
    }

    private fun beginPasswordLogin() {
        if (authenticationBusy) return
        val server = serverInput.text.toString().trim()
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (server.isBlank() || username.isBlank()) {
            statusText.text = "Enter a Jellyfin server and username."
            return
        }

        val normalized = runCatching { JellyfinServerUrl.normalize(server) }.getOrElse {
            statusText.text = "Enter a valid Jellyfin server URL."
            return
        }
        rememberNormalizedServer(normalized)
        setAuthenticationBusy(true, "Signing in…")
        val requestScope = NativeRequestScope()
        val attempt = AuthSessionCoordinator.beginAttempt(requestScope::cancel)
        activeAttempt = attempt

        thread(name = "ptv-password-login") {
            runCatching {
                api.withRequestScope(requestScope) {
                    api.authenticateWithPassword(normalized, username, password)
                }
            }.onSuccess { session ->
                runOnUiThread {
                    if (!canCommitFromUi(attempt)) return@runOnUiThread
                    val committed = runCatching {
                        AuthSessionCoordinator.commitAuthenticated(attempt, session) {
                            store.save(session, SessionOrigin.PASSWORD)
                        }
                    }.getOrDefault(false)
                    if (committed && AuthSessionCoordinator.isValidated(session)) {
                        activeAttempt = null
                        passwordInput.text?.clear()
                        launchAuthenticatedHost()
                    } else if (AuthSessionCoordinator.isCurrent(attempt)) {
                        AuthSessionCoordinator.cancelIfCurrent(attempt)
                        activeAttempt = null
                        passwordInput.text?.clear()
                        showLoginForm("PiggieTV could not securely save this login.")
                    }
                }
            }.onFailure { error ->
                if (!AuthSessionCoordinator.isCurrent(attempt)) return@onFailure
                val message = AuthFailurePolicy.passwordLoginMessage(error)
                runOnUiThread {
                    if (destroyed || !AuthSessionCoordinator.isCurrent(attempt)) return@runOnUiThread
                    AuthSessionCoordinator.cancelIfCurrent(attempt)
                    activeAttempt = null
                    passwordInput.text?.clear()
                    showLoginForm(message)
                }
            }
        }
    }

    private fun beginQuickConnect() {
        if (authenticationBusy) return
        val server = serverInput.text.toString().trim()
        if (server.isBlank()) {
            statusText.text = "Enter a Jellyfin server URL first."
            return
        }
        val normalized = runCatching {
            JellyfinServerUrl.normalize(server)
        }.getOrElse {
            statusText.text = "Enter a valid Jellyfin server URL first."
            return
        }
        if (normalized.isBlank()) {
            statusText.text = "Enter a valid Jellyfin server URL first."
            return
        }
        rememberNormalizedServer(normalized)
        quickConnectLaunched = true
        setAuthenticationBusy(true, "Starting Quick Connect…")
        startActivity(Intent(this, QuickConnectActivity::class.java).apply {
            putExtra("server", normalized)
        })
    }

    private fun launchAuthenticatedHost() {
        startActivity(Intent(this, PtvHostActivity::class.java))
        finish()
    }

    private fun canCommitFromUi(attempt: AuthAttempt): Boolean =
        AuthSessionCoordinator.canCommitUiResult(
            attempt = attempt,
            activeAttempt = activeAttempt,
            lifecycleStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            destroyed = destroyed,
        )

    private fun showConnectionRecovery(category: AuthFailureCategory) {
        startActivity(ConnectionRecoveryActivity.intent(this, category))
        finish()
    }

    private fun showLoginForm(message: String = "") {
        setAuthenticationBusy(false, message)
        serverInput.requestFocus()
    }

    private fun setAuthenticationBusy(busy: Boolean, message: String) {
        authenticationBusy = busy
        serverInput.isEnabled = !busy
        usernameInput.isEnabled = !busy
        passwordInput.isEnabled = !busy
        passwordAction.isEnabled = !busy
        quickConnectAction.isEnabled = !busy
        statusText.text = message
    }

    private fun rememberNormalizedServer(server: String) {
        getSharedPreferences(AUTH_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(LAST_SERVER_KEY, server)
            .apply()
    }

    /**
     * Older builds persisted the raw server field before validation. Normalize that value before
     * displaying it, and synchronously remove anything that could contain credentials or another
     * invalid base URL.
     */
    private fun restoreNormalizedServer(): String? {
        val preferences = getSharedPreferences(AUTH_PREFERENCES, MODE_PRIVATE)
        val stored = preferences.getString(LAST_SERVER_KEY, null)?.takeIf(String::isNotBlank)
            ?: return null
        val normalized = runCatching { JellyfinServerUrl.normalize(stored) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
        if (normalized == null) {
            preferences.edit().remove(LAST_SERVER_KEY).commit()
            return null
        }
        if (normalized != stored) {
            preferences.edit().putString(LAST_SERVER_KEY, normalized).commit()
        }
        return normalized
    }

    private fun configureDebugOptions() {
        TvRenderingRuntime.configureDebugExperiment(
            intent.getStringExtra(TvRenderingRuntime.DEBUG_EXPERIMENT_EXTRA),
        )
        if (!com.piggie.tv.BuildConfig.DEBUG) return
        val faultCategory = intent.getStringExtra("ptv_fault_category")?.let { value ->
            com.piggie.tv.data.api.DiscoveryEndpointCategory.entries.firstOrNull {
                it.name.equals(value, true)
            }
        }
        com.piggie.tv.data.api.DebugDiscoveryFaultInjector.configure(
            faultCategory,
            com.piggie.tv.data.api.DiscoveryFaultMode.fromWireName(intent.getStringExtra("ptv_fault_mode")),
            intent.getStringExtra("ptv_fault_shelf"),
            intent.getBooleanExtra("ptv_discovery_refresh", false),
        )
        val diagnosticsExperiment = DiagnosticsExperiment.fromWireName(
            intent.getStringExtra(DiagnosticsExperiment.DEBUG_EXTRA),
        )
        diagnosticsExperiment.collectionMode?.let { mode ->
            PtvDiagnosticsManager.setCollectionMode(this, mode)
            NativeSettings(this).diagnosticsOverlayEnabled =
                diagnosticsExperiment.overlayVisible == true
        }
    }

    companion object {
        internal const val EXTRA_STORAGE_CLEAR_FAILED = "ptv_storage_clear_failed"
        private const val AUTH_PREFERENCES = "ptv_auth"
        private const val LAST_SERVER_KEY = "last_server"
    }
}
