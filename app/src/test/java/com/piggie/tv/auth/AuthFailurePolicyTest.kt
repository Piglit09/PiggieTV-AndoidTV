package com.piggie.tv.auth

import com.piggie.tv.data.api.HttpRequestFailure
import com.piggie.tv.data.models.NativeSession
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AuthFailurePolicyTest {
    private val completeSession = NativeSession(
        token = "token",
        serverId = "server",
        userId = "user",
        userName = "Piggie",
        serverUrl = "https://example.test",
    )

    @Test
    fun `missing local session opens login without clearing`() {
        val decision = AuthFailurePolicy.initialRestore(null)

        assertEquals(SessionRestoreDestination.LOGIN, decision.destination)
        assertFalse(decision.clearAuthentication)
    }

    @Test
    fun `malformed local session is cleared before login`() {
        val decision = AuthFailurePolicy.initialRestore(completeSession.copy(token = ""))

        assertEquals(SessionRestoreDestination.LOGIN, decision.destination)
        assertTrue(decision.clearAuthentication)
        assertEquals(AuthFailureCategory.INVALID_LOCAL_SESSION, decision.category)
    }

    @Test
    fun `structurally complete local session must be validated`() {
        val decision = AuthFailurePolicy.initialRestore(completeSession)

        assertEquals(SessionRestoreDestination.VALIDATE, decision.destination)
        assertFalse(decision.clearAuthentication)
    }

    @Test
    fun `confirmed Jellyfin rejection clears stale credentials`() {
        listOf(401, 403, 404).forEach { status ->
            val decision = AuthFailurePolicy.validationFailure(HttpRequestFailure(status, "private body"))

            assertEquals(SessionRestoreDestination.LOGIN, decision.destination)
            assertTrue(decision.clearAuthentication)
            assertEquals(AuthFailureCategory.AUTH_REJECTED, decision.category)
        }
    }

    @Test
    fun `network and server outages preserve credentials for recovery`() {
        val errors = listOf(
            UnknownHostException("private-host"),
            SocketTimeoutException("timed out"),
            IOException("connection reset"),
            HttpRequestFailure(503, "private body"),
            HttpRequestFailure(429, "private body"),
            JSONException("bad server response"),
        )

        errors.forEach { error ->
            val decision = AuthFailurePolicy.validationFailure(error)
            assertEquals(SessionRestoreDestination.CONNECTION_RECOVERY, decision.destination)
            assertFalse(decision.clearAuthentication)
        }
    }

    @Test
    fun `request cancellation produces no navigation`() {
        val decision = AuthFailurePolicy.validationFailure(IOException("Request scope was cancelled"))

        assertEquals(SessionRestoreDestination.NONE, decision.destination)
        assertFalse(decision.clearAuthentication)
        assertEquals(AuthFailureCategory.CANCELLED, decision.category)
    }

    @Test
    fun `storage failure preserves credentials for recovery`() {
        val decision = AuthFailurePolicy.storageFailure()

        assertEquals(SessionRestoreDestination.CONNECTION_RECOVERY, decision.destination)
        assertFalse(decision.clearAuthentication)
        assertEquals(AuthFailureCategory.SECURE_STORAGE_FAILURE, decision.category)
    }

    @Test
    fun `password failure copy never exposes exception details`() {
        val privateDetail = "host.example token=do-not-show"

        val message = AuthFailurePolicy.passwordLoginMessage(IOException(privateDetail))

        assertFalse(message.contains(privateDetail))
        assertFalse(message.contains("do-not-show"))
    }

    @Test
    fun `password endpoint not found is not reported as rejected credentials`() {
        assertEquals(
            "The Jellyfin server is temporarily unavailable.",
            AuthFailurePolicy.passwordLoginMessage(HttpRequestFailure(404, "private response")),
        )
    }
}
