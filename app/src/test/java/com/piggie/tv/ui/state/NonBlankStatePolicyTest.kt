package com.piggie.tv.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NonBlankStatePolicyTest {
    @Test fun loadingBecomesRetryableTimeoutAtThreshold() {
        val state = NonBlankStatePolicy.watchdog(NonBlankUiState.Loading, 12_000, 12_000)
        assertTrue(state is NonBlankUiState.TimedOut)
        assertTrue((state as NonBlankUiState.TimedOut).retryable)
    }

    @Test fun contentAndErrorsAreNeverReplacedByWatchdog() {
        assertEquals(NonBlankUiState.Content, NonBlankStatePolicy.watchdog(NonBlankUiState.Content, 99_000, 1))
        val error = NonBlankUiState.Error("failed", retryable = true)
        assertEquals(error, NonBlankStatePolicy.watchdog(error, 99_000, 1))
    }
}
