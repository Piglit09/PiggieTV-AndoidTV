package com.piggie.tv.auth

import com.piggie.tv.data.models.NativeSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AuthSessionCoordinatorTest {
    private val session = NativeSession(
        token = "token-a",
        serverId = "server",
        userId = "user",
        userName = "Piggie",
        serverUrl = "https://example.test",
    )

    @Before
    fun setUp() = AuthSessionCoordinator.resetForTests()

    @After
    fun tearDown() = AuthSessionCoordinator.resetForTests()

    @Test
    fun `newer attempt cancels and rejects older result`() {
        val firstCancelled = AtomicBoolean(false)
        val first = AuthSessionCoordinator.beginAttempt { firstCancelled.set(true) }
        val second = AuthSessionCoordinator.beginAttempt()
        var stalePersisted = false

        val staleCommitted = AuthSessionCoordinator.commitAuthenticated(first, session) {
            stalePersisted = true
            true
        }
        val currentCommitted = AuthSessionCoordinator.commitAuthenticated(second, session) { true }

        assertTrue(firstCancelled.get())
        assertFalse(staleCommitted)
        assertFalse(stalePersisted)
        assertTrue(currentCommitted)
        assertTrue(AuthSessionCoordinator.isValidated(session))
    }

    @Test
    fun `successful commit terminalizes attempt without clearing host marker`() {
        val attempt = AuthSessionCoordinator.beginAttempt()

        assertTrue(AuthSessionCoordinator.commitAuthenticated(attempt, session) { true })
        assertFalse(AuthSessionCoordinator.isCurrent(attempt))

        // Activity.onDestroy may still call this with the completed attempt.
        AuthSessionCoordinator.cancelIfCurrent(attempt)
        assertTrue(AuthSessionCoordinator.isValidated(session))
    }

    @Test
    fun `failed persistence does not publish authenticated state`() {
        val attempt = AuthSessionCoordinator.beginAttempt()

        assertFalse(AuthSessionCoordinator.commitAuthenticated(attempt, session) { false })
        assertFalse(AuthSessionCoordinator.isValidated(session))
        assertTrue(AuthSessionCoordinator.isCurrent(attempt))
    }

    @Test
    fun `logout during validation rejects stale completion and remains logged out`() {
        val attempt = AuthSessionCoordinator.beginAttempt()
        var stalePersisted = false

        assertTrue(AuthSessionCoordinator.invalidateAndClear { true })
        val committed = AuthSessionCoordinator.commitAuthenticated(attempt, session) {
            stalePersisted = true
            true
        }

        assertFalse(committed)
        assertFalse(stalePersisted)
        assertFalse(AuthSessionCoordinator.isValidated(session))
    }

    @Test
    fun `logout clear is atomic against a newer login attempt`() {
        val oldAttempt = AuthSessionCoordinator.beginAttempt()
        val clearStarted = CountDownLatch(1)
        val releaseClear = CountDownLatch(1)
        val logoutFinished = CountDownLatch(1)
        val nextAttempt = AtomicReference<AuthAttempt?>()
        val nextAttemptFinished = CountDownLatch(1)

        Thread {
            AuthSessionCoordinator.invalidateAndClear {
                clearStarted.countDown()
                releaseClear.await(2, TimeUnit.SECONDS)
            }
            logoutFinished.countDown()
        }.start()
        assertTrue(clearStarted.await(1, TimeUnit.SECONDS))

        Thread {
            nextAttempt.set(AuthSessionCoordinator.beginAttempt())
            nextAttemptFinished.countDown()
        }.start()
        assertFalse(nextAttemptFinished.await(100, TimeUnit.MILLISECONDS))

        releaseClear.countDown()
        assertTrue(logoutFinished.await(1, TimeUnit.SECONDS))
        assertTrue(nextAttemptFinished.await(1, TimeUnit.SECONDS))
        assertFalse(AuthSessionCoordinator.commitAuthenticated(oldAttempt, session) { true })
        assertTrue(
            AuthSessionCoordinator.commitAuthenticated(requireNotNull(nextAttempt.get()), session) { true }
        )
    }

    @Test
    fun `validation marker is bound to the exact credential`() {
        val attempt = AuthSessionCoordinator.beginAttempt()
        assertTrue(AuthSessionCoordinator.commitAuthenticated(attempt, session) { true })

        assertTrue(AuthSessionCoordinator.isValidated(session))
        assertFalse(AuthSessionCoordinator.isValidated(session.copy(token = "token-b")))
        assertFalse(AuthSessionCoordinator.isValidated(session.copy(userId = "other-user")))
    }

    @Test
    fun `only a rejection for the currently validated credential is published`() {
        val attempt = AuthSessionCoordinator.beginAttempt()
        assertTrue(AuthSessionCoordinator.commitAuthenticated(attempt, session) { true })
        var rejection: AuthenticationRejection? = null
        val listener: (AuthenticationRejection) -> Unit = { rejection = it }
        AuthSessionCoordinator.addAuthenticationRejectionListener(listener)

        assertNull(AuthSessionCoordinator.authenticatedRequestTicket("other-token"))
        assertTrue(rejection == null)
        val ticket = requireNotNull(
            AuthSessionCoordinator.authenticatedRequestTicket(session.token)
        )
        assertTrue(AuthSessionCoordinator.notifyAuthenticationRejected(ticket))
        assertTrue(AuthSessionCoordinator.rejectionMatches(requireNotNull(rejection), session))
        // Notification itself never mutates auth; the lifecycle owner atomically clears it.
        assertTrue(AuthSessionCoordinator.isValidated(session))

        AuthSessionCoordinator.removeAuthenticationRejectionListener(listener)
    }

    @Test
    fun `only one current rejection is published for an authentication epoch`() {
        val attempt = AuthSessionCoordinator.beginAttempt()
        assertTrue(AuthSessionCoordinator.commitAuthenticated(attempt, session) { true })
        val listenerCalls = AtomicInteger()
        val listener: (AuthenticationRejection) -> Unit = { listenerCalls.incrementAndGet() }
        AuthSessionCoordinator.addAuthenticationRejectionListener(listener)
        val tickets = List(4) {
            requireNotNull(AuthSessionCoordinator.authenticatedRequestTicket(session.token))
        }
        val ready = CountDownLatch(tickets.size)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(tickets.size)

        try {
            val results = tickets.map { ticket ->
                executor.submit<Boolean> {
                    ready.countDown()
                    release.await(1, TimeUnit.SECONDS)
                    AuthSessionCoordinator.notifyAuthenticationRejected(ticket)
                }
            }
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            release.countDown()

            assertEquals(1, results.count { it.get(1, TimeUnit.SECONDS) })
            assertEquals(1, listenerCalls.get())
            assertFalse(AuthSessionCoordinator.notifyAuthenticationRejected(tickets.first()))
        } finally {
            executor.shutdownNow()
            AuthSessionCoordinator.removeAuthenticationRejectionListener(listener)
        }
    }

    @Test
    fun `rejection without a host listener does not consume the epoch`() {
        val attempt = AuthSessionCoordinator.beginAttempt()
        assertTrue(AuthSessionCoordinator.commitAuthenticated(attempt, session) { true })
        val ticket = requireNotNull(
            AuthSessionCoordinator.authenticatedRequestTicket(session.token)
        )
        var published = false

        assertFalse(AuthSessionCoordinator.notifyAuthenticationRejected(ticket))
        val listener: (AuthenticationRejection) -> Unit = { published = true }
        AuthSessionCoordinator.addAuthenticationRejectionListener(listener)

        assertTrue(AuthSessionCoordinator.notifyAuthenticationRejected(ticket))
        assertTrue(published)
        AuthSessionCoordinator.removeAuthenticationRejectionListener(listener)
    }

    @Test
    fun `old 401 cannot reject a newer login that receives the same token`() {
        val firstAttempt = AuthSessionCoordinator.beginAttempt()
        assertTrue(AuthSessionCoordinator.commitAuthenticated(firstAttempt, session) { true })
        val oldRequest = requireNotNull(
            AuthSessionCoordinator.authenticatedRequestTicket(session.token)
        )
        val secondAttempt = AuthSessionCoordinator.beginAttempt()
        assertTrue(AuthSessionCoordinator.commitAuthenticated(secondAttempt, session) { true })
        var rejectionPublished = false
        val listener: (AuthenticationRejection) -> Unit = { rejectionPublished = true }
        AuthSessionCoordinator.addAuthenticationRejectionListener(listener)

        assertFalse(AuthSessionCoordinator.notifyAuthenticationRejected(oldRequest))
        assertFalse(rejectionPublished)
        assertTrue(AuthSessionCoordinator.isValidated(session))

        AuthSessionCoordinator.removeAuthenticationRejectionListener(listener)
    }

    @Test
    fun `logout and same-token relogin reject the old ticket and allow the new epoch`() {
        val firstAttempt = AuthSessionCoordinator.beginAttempt()
        assertTrue(AuthSessionCoordinator.commitAuthenticated(firstAttempt, session) { true })
        val oldRequest = requireNotNull(
            AuthSessionCoordinator.authenticatedRequestTicket(session.token)
        )

        assertTrue(AuthSessionCoordinator.invalidateAndClear { true })
        val nextAttempt = AuthSessionCoordinator.beginAttempt()
        assertTrue(AuthSessionCoordinator.commitAuthenticated(nextAttempt, session) { true })
        val listenerCalls = AtomicInteger()
        val listener: (AuthenticationRejection) -> Unit = { listenerCalls.incrementAndGet() }
        AuthSessionCoordinator.addAuthenticationRejectionListener(listener)

        assertFalse(AuthSessionCoordinator.notifyAuthenticationRejected(oldRequest))
        assertTrue(AuthSessionCoordinator.isValidated(session))
        val newRequest = requireNotNull(
            AuthSessionCoordinator.authenticatedRequestTicket(session.token)
        )
        assertTrue(AuthSessionCoordinator.notifyAuthenticationRejected(newRequest))
        assertEquals(1, listenerCalls.get())

        AuthSessionCoordinator.removeAuthenticationRejectionListener(listener)
    }

    @Test
    fun `stopped or detached activity cannot commit a completed worker result`() {
        val attempt = AuthSessionCoordinator.beginAttempt()

        assertTrue(
            AuthSessionCoordinator.canCommitUiResult(
                attempt,
                activeAttempt = attempt,
                lifecycleStarted = true,
                destroyed = false,
            )
        )
        assertFalse(
            AuthSessionCoordinator.canCommitUiResult(
                attempt,
                activeAttempt = attempt,
                lifecycleStarted = false,
                destroyed = false,
            )
        )
        assertFalse(
            AuthSessionCoordinator.canCommitUiResult(
                attempt,
                activeAttempt = null,
                lifecycleStarted = true,
                destroyed = false,
            )
        )
        assertFalse(
            AuthSessionCoordinator.canCommitUiResult(
                attempt,
                activeAttempt = attempt,
                lifecycleStarted = true,
                destroyed = true,
            )
        )
    }
}
