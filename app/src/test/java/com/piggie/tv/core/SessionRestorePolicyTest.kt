package com.piggie.tv.core

import com.piggie.tv.data.api.HttpRequestFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRestorePolicyTest {
    @Test
    fun onlyNetworkFailuresAreRetryable() {
        assertTrue(PhaseOnePointFivePolicies.isNetworkErrorRetryable(java.net.SocketTimeoutException()))
        assertTrue(PhaseOnePointFivePolicies.isNetworkErrorRetryable(java.net.UnknownHostException("")))
        
        // 401 Unauthorized should NOT be retryable automatically (session expired)
        assertFalse(PhaseOnePointFivePolicies.isNetworkErrorRetryable(HttpRequestFailure(401, "Auth failed")))
        
        // 503 Service Unavailable SHOULD be retryable
        assertTrue(PhaseOnePointFivePolicies.isNetworkErrorRetryable(HttpRequestFailure(503, "Server busy")))
    }
}
