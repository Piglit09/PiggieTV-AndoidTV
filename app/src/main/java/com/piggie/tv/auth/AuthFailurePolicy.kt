package com.piggie.tv.auth

import com.piggie.tv.data.api.HttpRequestFailure
import com.piggie.tv.data.models.NativeSession
import org.json.JSONException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class AuthFailureCategory {
    AUTH_REJECTED,
    NETWORK_UNAVAILABLE,
    SERVER_UNAVAILABLE,
    INVALID_LOCAL_SESSION,
    SECURE_STORAGE_FAILURE,
    CANCELLED,
    UNKNOWN,
}

enum class SessionRestoreDestination { LOGIN, VALIDATE, CONNECTION_RECOVERY, NONE }

data class SessionRestoreDecision(
    val destination: SessionRestoreDestination,
    val clearAuthentication: Boolean,
    val category: AuthFailureCategory? = null,
)

/** Pure classification and routing rules shared by startup and focused unit tests. */
object AuthFailurePolicy {
    fun classify(error: Throwable): AuthFailureCategory {
        val causes = causeChain(error)
        val httpFailure = causes.filterIsInstance<HttpRequestFailure>().firstOrNull()
        return when {
            httpFailure != null -> when (httpFailure.statusCode) {
                401, 403 -> AuthFailureCategory.AUTH_REJECTED
                // A generic 404 usually means a wrong base path or unavailable endpoint. Stored
                // session validation handles its user-specific 404 separately below.
                404 -> AuthFailureCategory.SERVER_UNAVAILABLE
                408, 425, 429 -> AuthFailureCategory.SERVER_UNAVAILABLE
                in 500..599 -> AuthFailureCategory.SERVER_UNAVAILABLE
                else -> AuthFailureCategory.UNKNOWN
            }

            causes.any { failure ->
                failure is SocketTimeoutException ||
                    failure is UnknownHostException ||
                    failure is ConnectException ||
                    failure is NoRouteToHostException ||
                    failure is SSLException
            } -> AuthFailureCategory.NETWORK_UNAVAILABLE

            causes.any { it is InterruptedIOException && it.message.isCancellationMessage() } ->
                AuthFailureCategory.CANCELLED

            causes.any { it is IOException && it.message.isCancellationMessage() } ->
                AuthFailureCategory.CANCELLED

            causes.any { it is IOException } -> AuthFailureCategory.NETWORK_UNAVAILABLE
            causes.any { it is JSONException } -> AuthFailureCategory.SERVER_UNAVAILABLE
            else -> AuthFailureCategory.UNKNOWN
        }
    }

    fun initialRestore(session: NativeSession?): SessionRestoreDecision = when {
        session == null -> SessionRestoreDecision(SessionRestoreDestination.LOGIN, clearAuthentication = false)
        !session.isComplete() -> SessionRestoreDecision(
            SessionRestoreDestination.LOGIN,
            clearAuthentication = true,
            AuthFailureCategory.INVALID_LOCAL_SESSION,
        )

        else -> SessionRestoreDecision(SessionRestoreDestination.VALIDATE, clearAuthentication = false)
    }

    fun storageFailure(): SessionRestoreDecision = SessionRestoreDecision(
        SessionRestoreDestination.CONNECTION_RECOVERY,
        clearAuthentication = false,
        AuthFailureCategory.SECURE_STORAGE_FAILURE,
    )

    fun validationFailure(error: Throwable): SessionRestoreDecision {
        val category = if (causeChain(error).filterIsInstance<HttpRequestFailure>().any {
                it.statusCode == 404
            }
        ) {
            // validateSession calls the stored user's endpoint, so 404 means that Jellyfin
            // identity no longer exists and the local token must be removed.
            AuthFailureCategory.AUTH_REJECTED
        } else {
            classify(error)
        }
        return when (category) {
            AuthFailureCategory.AUTH_REJECTED -> SessionRestoreDecision(
                SessionRestoreDestination.LOGIN,
                clearAuthentication = true,
                category,
            )

            AuthFailureCategory.CANCELLED -> SessionRestoreDecision(
                SessionRestoreDestination.NONE,
                clearAuthentication = false,
                category,
            )

            else -> SessionRestoreDecision(
                SessionRestoreDestination.CONNECTION_RECOVERY,
                clearAuthentication = false,
                category,
            )
        }
    }

    fun passwordLoginMessage(error: Throwable): String = when (classify(error)) {
        AuthFailureCategory.AUTH_REJECTED -> "The username or password was not accepted."
        AuthFailureCategory.NETWORK_UNAVAILABLE -> "PiggieTV could not reach that Jellyfin server."
        AuthFailureCategory.SERVER_UNAVAILABLE -> "The Jellyfin server is temporarily unavailable."
        AuthFailureCategory.CANCELLED -> "Login was cancelled."
        AuthFailureCategory.SECURE_STORAGE_FAILURE -> "PiggieTV could not securely save this login."
        else -> "PiggieTV could not complete login."
    }

    private fun causeChain(error: Throwable): List<Throwable> {
        val causes = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && current !in causes) {
            causes += current
            current = current.cause
        }
        return causes
    }

    private fun String?.isCancellationMessage(): Boolean {
        val value = this?.lowercase().orEmpty()
        return value.contains("cancel") || value.contains("request scope")
    }
}
