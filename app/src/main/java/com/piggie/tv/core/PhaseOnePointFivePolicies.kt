package com.piggie.tv.core

import com.piggie.tv.data.api.HttpRequestFailure

object PhaseOnePointFivePolicies {
    fun isNetworkErrorRetryable(error: Throwable): Boolean {
        return when (error) {
            is java.net.SocketTimeoutException -> true
            is java.net.UnknownHostException -> true
            is java.io.InterruptedIOException -> true
            is HttpRequestFailure -> {
                // Retry on server errors, not client errors (4xx)
                error.statusCode >= 500
            }
            else -> false
        }
    }
}
