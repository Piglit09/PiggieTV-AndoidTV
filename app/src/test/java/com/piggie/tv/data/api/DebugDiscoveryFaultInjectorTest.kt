package com.piggie.tv.data.api

import java.net.SocketTimeoutException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DebugDiscoveryFaultInjectorTest {
    @After fun reset() {
        DebugDiscoveryFaultInjector.configure(null, DiscoveryFaultMode.NORMAL)
        DebugDiscoveryFaultInjector.sleeper = Thread::sleep
        DebugDiscoveryFaultInjector.elapsedRealtime = android.os.SystemClock::elapsedRealtime
    }

    @Test fun httpAndDisconnectFaultsAreDeterministicAndScoped() {
        DebugDiscoveryFaultInjector.configure(DiscoveryEndpointCategory.NEXT_UP, DiscoveryFaultMode.HTTP_400)
        assertNull(DebugDiscoveryFaultInjector.beforeRequest(DiscoveryEndpointCategory.RESUME))
        assertEquals(400, assertThrows(HttpRequestFailure::class.java) {
            DebugDiscoveryFaultInjector.beforeRequest(DiscoveryEndpointCategory.NEXT_UP)
        }.statusCode)

        DebugDiscoveryFaultInjector.configure(DiscoveryEndpointCategory.NEXT_UP, DiscoveryFaultMode.HTTP_502)
        assertEquals(502, assertThrows(HttpRequestFailure::class.java) {
            DebugDiscoveryFaultInjector.beforeRequest(DiscoveryEndpointCategory.NEXT_UP)
        }.statusCode)

        DebugDiscoveryFaultInjector.configure(DiscoveryEndpointCategory.NEXT_UP, DiscoveryFaultMode.DISCONNECT_BEFORE_HEADERS)
        assertThrows(DebugInjectedDisconnectBeforeHeadersException::class.java) {
            DebugDiscoveryFaultInjector.beforeRequest(DiscoveryEndpointCategory.NEXT_UP)
        }

        DebugDiscoveryFaultInjector.configure(DiscoveryEndpointCategory.NEXT_UP, DiscoveryFaultMode.DISCONNECT_DURING_BODY)
        assertThrows(DebugInjectedDisconnectDuringBodyException::class.java) {
            DebugDiscoveryFaultInjector.beforeRequest(DiscoveryEndpointCategory.NEXT_UP)
        }
    }

    @Test fun timeoutThenManualRetrySucceeds() {
        DebugDiscoveryFaultInjector.configure(DiscoveryEndpointCategory.NEXT_UP, DiscoveryFaultMode.SUCCESS_AFTER_MANUAL_RETRY)
        assertThrows(SocketTimeoutException::class.java) {
            DebugDiscoveryFaultInjector.beforeRequest(DiscoveryEndpointCategory.NEXT_UP)
        }
        assertNull(DebugDiscoveryFaultInjector.beforeRequest(DiscoveryEndpointCategory.NEXT_UP))
        assertEquals(2, DebugDiscoveryFaultInjector.snapshot().attempts)
        assertEquals(1, DebugDiscoveryFaultInjector.snapshot().applied)
    }

    @Test fun malformedAndEmptyBodiesAreStable() {
        DebugDiscoveryFaultInjector.configure(DiscoveryEndpointCategory.NEXT_UP, DiscoveryFaultMode.MALFORMED_JSON)
        assertEquals("{\"Items\":[", DebugDiscoveryFaultInjector.beforeRequest(DiscoveryEndpointCategory.NEXT_UP))
        DebugDiscoveryFaultInjector.configure(DiscoveryEndpointCategory.NEXT_UP, DiscoveryFaultMode.EMPTY_HTTP_200)
        assertEquals("{\"Items\":[]}", DebugDiscoveryFaultInjector.beforeRequest(DiscoveryEndpointCategory.NEXT_UP))
    }

    @Test fun shelfAndAttemptScopePreventsAnUnrelatedRequestFromConsumingRetryFault() {
        DebugDiscoveryFaultInjector.configure(
            DiscoveryEndpointCategory.USER_ITEMS,
            DiscoveryFaultMode.SUCCESS_AFTER_MANUAL_RETRY,
            "home.latest"
        )
        assertNull(DebugDiscoveryFaultInjector.withShelfScope("home.added", 7, 0) {
            DebugDiscoveryFaultInjector.beforeRequest(DiscoveryEndpointCategory.USER_ITEMS)
        })
        assertThrows(SocketTimeoutException::class.java) {
            DebugDiscoveryFaultInjector.withShelfScope("home.latest", 7, 0) {
                DebugDiscoveryFaultInjector.beforeRequest(DiscoveryEndpointCategory.USER_ITEMS)
            }
        }
        assertNull(DebugDiscoveryFaultInjector.withShelfScope("home.latest", 7, 1) {
            DebugDiscoveryFaultInjector.beforeRequest(DiscoveryEndpointCategory.USER_ITEMS)
        })
    }

    @Test fun bothDelayModesUseTheirExactDeterministicDurations() {
        val delays = mutableListOf<Long>()
        var nowMs = 1_000L
        DebugDiscoveryFaultInjector.elapsedRealtime = { nowMs }
        DebugDiscoveryFaultInjector.sleeper = { delayMs ->
            delays += delayMs
            nowMs += delayMs
        }
        DebugDiscoveryFaultInjector.configure(DiscoveryEndpointCategory.NEXT_UP, DiscoveryFaultMode.DELAY_10_SECONDS)
        assertNull(DebugDiscoveryFaultInjector.beforeRequest(DiscoveryEndpointCategory.NEXT_UP))
        DebugDiscoveryFaultInjector.configure(DiscoveryEndpointCategory.NEXT_UP, DiscoveryFaultMode.DELAY_BEYOND_TIMEOUT)
        assertThrows(SocketTimeoutException::class.java) {
            DebugDiscoveryFaultInjector.beforeRequest(DiscoveryEndpointCategory.NEXT_UP)
        }
        assertEquals(listOf(10_000L, 46_000L), delays)
    }
}
