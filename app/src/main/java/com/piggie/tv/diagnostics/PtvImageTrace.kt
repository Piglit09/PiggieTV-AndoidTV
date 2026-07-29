package com.piggie.tv.diagnostics

data class PtvImageTrace(
    val timestampMs: Long = System.currentTimeMillis(),
    val itemId: String,
    val imageType: String,
    val requestedWidth: Int,
    val requestedHeight: Int,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    val cacheSource: String? = null,
    val loadMs: Long,
    val failure: String? = null,
    val placeholderMs: Long? = null,
    val bitmapBytes: Long? = null,
    val concurrentRequests: Int
)
