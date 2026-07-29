package com.piggie.tv.diagnostics

data class PtvNetworkTrace(
    val method: String,
    val endpoint: String,
    val startedAtMs: Long,
    val dnsMs: Long? = null,
    val connectMs: Long? = null,
    val tlsMs: Long? = null,
    val requestWriteMs: Long? = null,
    val responseHeadersMs: Long? = null,
    val firstByteMs: Long? = null,
    val totalMs: Long? = null,
    val status: Int? = null,
    val requestBytes: Long? = null,
    val responseBytes: Long? = null,
    val exception: String? = null,
    val retryCount: Int = 0
)
