package com.piggie.tv.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickConnectPollingPolicyTest {
    @Test
    fun mappingHttpStatusToOutcome() {
        assertEquals(QuickConnectPollOutcome.WAITING, QuickConnectPollingPolicy.outcomeFor(401))
        assertEquals(QuickConnectPollOutcome.WAITING, QuickConnectPollingPolicy.outcomeFor(403))
        assertEquals(QuickConnectPollOutcome.EXPIRED, QuickConnectPollingPolicy.outcomeFor(404))
        assertEquals(QuickConnectPollOutcome.FAILED, QuickConnectPollingPolicy.outcomeFor(429))
        assertEquals(QuickConnectPollOutcome.FAILED, QuickConnectPollingPolicy.outcomeFor(500))
    }

    @Test
    fun timeoutLogic() {
        val start = 1000L
        assertFalse(QuickConnectPollingPolicy.hasTimedOut(start, start + 1000L))
        assertTrue(QuickConnectPollingPolicy.hasTimedOut(start, start + QuickConnectPollingPolicy.TIMEOUT_MS))
        assertTrue(QuickConnectPollingPolicy.hasTimedOut(start, start + QuickConnectPollingPolicy.TIMEOUT_MS + 1L))
        assertFalse(QuickConnectPollingPolicy.hasTimedOut(0L, QuickConnectPollingPolicy.TIMEOUT_MS + 1L))
    }

    @Test
    fun networkFailuresAreBounded() {
        assertFalse(QuickConnectPollingPolicy.shouldRetryNetworkFailure(0))
        assertTrue(QuickConnectPollingPolicy.shouldRetryNetworkFailure(1))
        assertTrue(
            QuickConnectPollingPolicy.shouldRetryNetworkFailure(
                QuickConnectPollingPolicy.MAX_CONSECUTIVE_NETWORK_FAILURES
            )
        )
        assertFalse(
            QuickConnectPollingPolicy.shouldRetryNetworkFailure(
                QuickConnectPollingPolicy.MAX_CONSECUTIVE_NETWORK_FAILURES + 1
            )
        )
    }
}
