package com.piggie.tv.auth

import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.SecureSessionStore
import java.security.MessageDigest

/** Identifies one authentication or restoration attempt within this app process. */
class AuthAttempt internal constructor(internal val generation: Long)

/**
 * Small process-wide ownership gate for authentication work.
 *
 * Android activities may be replaced while blocking Jellyfin requests are still completing. Every
 * request that can establish a session receives a generation, and only the current generation may
 * persist or publish its result. Logout invalidates the generation before clearing credentials, so
 * a late callback cannot recreate the session.
 */
object AuthSessionCoordinator {
    private data class ValidatedSessionMarker(
        val serverUrl: String,
        val serverId: String,
        val userId: String,
        val tokenDigest: String,
    )

    private val lock = Any()
    private var generation = 0L
    private var cancelCurrent: (() -> Unit)? = null
    private var validatedSession: ValidatedSessionMarker? = null
    private var publishedAuthenticationRejectionEpoch: Long? = null
    private val authenticationRejectionListeners = mutableSetOf<(AuthenticationRejection) -> Unit>()

    fun beginAttempt(cancel: () -> Unit = {}): AuthAttempt {
        val (previousCancellation, attempt) = synchronized(lock) {
            val previous = cancelCurrent
            generation += 1L
            validatedSession = null
            publishedAuthenticationRejectionEpoch = null
            cancelCurrent = cancel
            previous to AuthAttempt(generation)
        }
        previousCancellation?.invokeSafely()
        return attempt
    }

    fun isCurrent(attempt: AuthAttempt): Boolean = synchronized(lock) {
        attempt.generation == generation
    }

    /** Main-thread gate used immediately before a lifecycle-owned result can persist. */
    fun canCommitUiResult(
        attempt: AuthAttempt,
        activeAttempt: AuthAttempt?,
        lifecycleStarted: Boolean,
        destroyed: Boolean,
    ): Boolean = !destroyed &&
        lifecycleStarted &&
        activeAttempt === attempt &&
        isCurrent(attempt)

    fun cancelIfCurrent(attempt: AuthAttempt) {
        val cancellation = synchronized(lock) {
            if (attempt.generation != generation) return
            generation += 1L
            validatedSession = null
            publishedAuthenticationRejectionEpoch = null
            cancelCurrent.also { cancelCurrent = null }
        }
        cancellation?.invokeSafely()
    }

    /** Invalidates all outstanding auth work before a logout mutates persistent state. */
    fun invalidateAll() {
        val cancellation = synchronized(lock) {
            generation += 1L
            validatedSession = null
            publishedAuthenticationRejectionEpoch = null
            cancelCurrent.also { cancelCurrent = null }
        }
        cancellation?.invokeSafely()
    }

    /**
     * Invalidates authentication and clears durable credentials as one generation transaction.
     * A new attempt cannot commit immediately before [clear] and then be erased by logout.
     */
    fun invalidateAndClear(clear: () -> Boolean): Boolean {
        val (cancellation, cleared) = synchronized(lock) {
            generation += 1L
            validatedSession = null
            publishedAuthenticationRejectionEpoch = null
            val pendingCancellation = cancelCurrent
            cancelCurrent = null
            pendingCancellation to runCatching(clear).getOrDefault(false)
        }
        cancellation?.invokeSafely()
        return cleared
    }

    /**
     * Persists and publishes [session] only when [attempt] still owns authentication.
     *
     * [persist] runs while the generation lock is held so logout or a newer attempt cannot pass
     * the ownership check between the check and the durable write. It must be a short local write.
     */
    fun commitAuthenticated(
        attempt: AuthAttempt,
        session: NativeSession,
        persist: () -> Boolean,
    ): Boolean = synchronized(lock) {
        if (attempt.generation != generation) return@synchronized false
        if (!persist()) return@synchronized false
        validatedSession = session.marker()
        // A committed result is terminal. Activity destruction must not be able to cancel the
        // completed attempt and clear the marker needed by the host it just launched.
        generation += 1L
        publishedAuthenticationRejectionEpoch = null
        cancelCurrent = null
        true
    }

    /** Allows a host only when this exact credential was validated in the current process. */
    fun isValidated(session: NativeSession): Boolean = synchronized(lock) {
        validatedSession == session.marker()
    }

    /**
     * Returns durable credentials only when this exact session was validated in this process.
     *
     * Standalone Android components can be recreated without passing through [MainActivity].
     * Reading through this gate prevents those components from treating decrypted storage alone
     * as proof that a Jellyfin session is still accepted by the server.
     */
    fun validatedSession(store: SecureSessionStore): NativeSession? = synchronized(lock) {
        store.read()?.takeIf { validatedSession == it.marker() }
    }

    /** Captures opaque ownership before an authenticated request enters transport. */
    internal fun authenticatedRequestTicket(token: String?): AuthenticatedRequestTicket? {
        val fingerprint = token?.takeIf(String::isNotBlank)?.digest() ?: return null
        return synchronized(lock) {
            if (validatedSession?.tokenDigest != fingerprint) return@synchronized null
            AuthenticatedRequestTicket(generation, fingerprint)
        }
    }

    /**
     * Publishes a confirmed 401 only while the exact credential epoch captured at request dispatch
     * is still current. A same-token relogin receives a new epoch and cannot be cleared by an older
     * response. The host owns durable clearing and routing.
     */
    internal fun notifyAuthenticationRejected(ticket: AuthenticatedRequestTicket?): Boolean {
        ticket ?: return false
        val (rejection, listeners) = synchronized(lock) {
            if (
                generation != ticket.epoch ||
                validatedSession?.tokenDigest != ticket.tokenDigest
            ) {
                return false
            }
            val currentListeners = authenticationRejectionListeners.toList()
            if (
                currentListeners.isEmpty() ||
                publishedAuthenticationRejectionEpoch == ticket.epoch
            ) {
                return false
            }
            publishedAuthenticationRejectionEpoch = ticket.epoch
            AuthenticationRejection(ticket.epoch, ticket.tokenDigest) to currentListeners
        }
        listeners.forEach { listener -> runCatching { listener(rejection) } }
        return true
    }

    internal fun addAuthenticationRejectionListener(listener: (AuthenticationRejection) -> Unit) {
        synchronized(lock) { authenticationRejectionListeners += listener }
    }

    internal fun removeAuthenticationRejectionListener(listener: (AuthenticationRejection) -> Unit) {
        synchronized(lock) {
            authenticationRejectionListeners -= listener
            // Do not let a rejection delivered only to a detached host suppress recovery after
            // lifecycle replacement. A later 401 can be published once a new host is listening.
            if (authenticationRejectionListeners.isEmpty()) {
                publishedAuthenticationRejectionEpoch = null
            }
        }
    }

    internal fun rejectionMatches(
        rejection: AuthenticationRejection,
        session: NativeSession,
    ): Boolean = synchronized(lock) {
        generation == rejection.epoch &&
            rejection.tokenDigest == session.token.digest() &&
            validatedSession == session.marker()
    }

    internal fun resetForTests() {
        synchronized(lock) {
            generation = 0L
            cancelCurrent = null
            validatedSession = null
            publishedAuthenticationRejectionEpoch = null
            authenticationRejectionListeners.clear()
        }
    }

    private fun NativeSession.marker() = ValidatedSessionMarker(
        serverUrl = serverUrl,
        serverId = serverId,
        userId = userId,
        tokenDigest = token.digest(),
    )

    private fun String.digest(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun (() -> Unit).invokeSafely() {
        runCatching { invoke() }
    }
}

internal class AuthenticatedRequestTicket internal constructor(
    internal val epoch: Long,
    internal val tokenDigest: String,
) {
    override fun toString(): String = "AuthenticatedRequestTicket(credential=[REDACTED])"
}

internal class AuthenticationRejection internal constructor(
    internal val epoch: Long,
    internal val tokenDigest: String,
) {
    override fun toString(): String = "AuthenticationRejection(credential=[REDACTED])"
}
