package com.piggie.tv.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickConnectStateControllerTest {
    @Test
    fun exposesEveryRequiredState() {
        assertEquals(
            setOf(
                QuickConnectState.IDLE,
                QuickConnectState.REQUESTING,
                QuickConnectState.WAITING_FOR_APPROVAL,
                QuickConnectState.APPROVED,
                QuickConnectState.AUTHENTICATING,
                QuickConnectState.SUCCESS,
                QuickConnectState.EXPIRED,
                QuickConnectState.CANCELLED,
                QuickConnectState.FAILED,
            ),
            QuickConnectState.entries.toSet(),
        )
    }

    @Test
    fun approvalAuthenticatesAndPersistsExactlyOnce() {
        val controller = QuickConnectStateController()
        val attempt = controller.beginAttempt()
        var persistenceCount = 0

        assertEquals(QuickConnectState.REQUESTING, attempt.state)
        assertTrue(controller.waitingForApproval(attempt.generation, "ABC123"))
        assertTrue(controller.approved(attempt.generation))
        assertFalse("approval cannot be published twice", controller.approved(attempt.generation))
        assertTrue(controller.authenticating(attempt.generation))
        assertFalse("authentication cannot be entered twice", controller.authenticating(attempt.generation))

        assertEquals(
            QuickConnectCommitResult.SUCCESS,
            controller.complete(attempt.generation) {
                persistenceCount += 1
                QuickConnectCommitResult.SUCCESS
            },
        )
        assertEquals(QuickConnectState.SUCCESS, controller.snapshot().state)
        assertEquals(1, persistenceCount)

        assertEquals(
            QuickConnectCommitResult.STALE,
            controller.complete(attempt.generation) {
                persistenceCount += 1
                QuickConnectCommitResult.SUCCESS
            },
        )
        assertEquals("terminal success must not persist twice", 1, persistenceCount)
    }

    @Test
    fun expirationIsTerminal() {
        val controller = QuickConnectStateController()
        val attempt = controller.beginAttempt()
        assertTrue(controller.waitingForApproval(attempt.generation, "EXPIRE"))

        assertTrue(controller.expire(attempt.generation))
        assertEquals(QuickConnectState.EXPIRED, controller.snapshot().state)
        assertFalse(controller.approved(attempt.generation))
        assertFalse(controller.cancel(attempt.generation))
    }

    @Test
    fun cancellationAndRegenerationRejectStaleResults() {
        val controller = QuickConnectStateController()
        val first = controller.beginAttempt()
        assertTrue(controller.waitingForApproval(first.generation, "FIRST"))
        assertTrue(controller.cancel(first.generation))
        assertEquals(QuickConnectState.CANCELLED, controller.snapshot().state)

        val second = controller.beginAttempt()
        assertTrue(second.generation > first.generation)
        assertFalse(controller.waitingForApproval(first.generation, "STALE"))
        assertFalse(controller.approved(first.generation))
        assertTrue(controller.waitingForApproval(second.generation, "SECOND"))
        assertEquals("SECOND", controller.snapshot().code)
    }

    @Test
    fun repeatedGenerateLeavesOnlyNewestAttemptCurrent() {
        val controller = QuickConnectStateController()
        val first = controller.beginAttempt()
        val second = controller.beginAttempt()

        assertFalse(controller.isCurrent(first.generation))
        assertTrue(controller.isCurrent(second.generation))
        assertEquals(QuickConnectState.REQUESTING, controller.snapshot().state)
    }

    @Test
    fun storageFailureNeverPublishesSuccess() {
        val controller = QuickConnectStateController()
        val attempt = controller.beginAttempt()
        controller.waitingForApproval(attempt.generation, "STORE")
        controller.approved(attempt.generation)
        controller.authenticating(attempt.generation)

        assertEquals(
            QuickConnectCommitResult.STORAGE_FAILURE,
            controller.complete(attempt.generation) {
                QuickConnectCommitResult.STORAGE_FAILURE
            },
        )
        assertEquals(QuickConnectState.FAILED, controller.snapshot().state)
        assertEquals(QuickConnectFailure.SECURE_STORAGE, controller.snapshot().failure)
    }

    @Test
    fun staleSharedCommitCancelsLocalAttempt() {
        val controller = QuickConnectStateController()
        val attempt = controller.beginAttempt()
        controller.waitingForApproval(attempt.generation, "STALE")
        controller.approved(attempt.generation)
        controller.authenticating(attempt.generation)

        assertEquals(
            QuickConnectCommitResult.STALE,
            controller.complete(attempt.generation) {
                QuickConnectCommitResult.STALE
            },
        )
        assertEquals(QuickConnectState.CANCELLED, controller.snapshot().state)
    }

    @Test
    fun staleGenerationCannotClaimPersistence() {
        val controller = QuickConnectStateController()
        val stale = controller.beginAttempt()
        controller.waitingForApproval(stale.generation, "STALE")
        controller.approved(stale.generation)
        controller.authenticating(stale.generation)
        controller.beginAttempt()
        var persistenceCount = 0

        assertEquals(
            QuickConnectCommitResult.STALE,
            controller.complete(stale.generation) {
                persistenceCount += 1
                QuickConnectCommitResult.SUCCESS
            },
        )
        assertEquals(0, persistenceCount)
    }

    @Test
    fun snapshotRepresentationRedactsDisplayedCode() {
        val controller = QuickConnectStateController()
        val attempt = controller.beginAttempt()
        controller.waitingForApproval(attempt.generation, "DO-NOT-LOG")

        val representation = controller.snapshot().toString()
        assertFalse(representation.contains("DO-NOT-LOG"))
        assertTrue(representation.contains("[REDACTED]"))
    }

    @Test
    fun finalAuthenticationExchangeCannotReturnToPollingOrBeClaimedTwice() {
        val controller = QuickConnectStateController()
        val generation = controller.beginAttempt().generation
        assertTrue(controller.waitingForApproval(generation, "123456"))
        assertTrue(controller.approved(generation))
        assertTrue(controller.authenticating(generation))

        assertFalse(controller.waitingForApproval(generation))
        assertFalse(controller.approved(generation))
        assertFalse(controller.authenticating(generation))
    }
}
