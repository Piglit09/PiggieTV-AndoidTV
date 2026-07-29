package com.piggie.tv.diagnostics

data class PtvReaderTrace(
    val timestampMs: Long = System.currentTimeMillis(),
    val itemId: String,
    val event: String,
    val format: String? = null,
    val pageCount: Int? = null,
    val page: Int? = null,
    val fitMode: String? = null,
    val direction: String? = null,
    val renderTarget: String? = null,
    val renderMs: Long? = null,
    val cacheHit: Boolean? = null,
    val bitmapBytes: Int? = null,
    val input: String? = null,
    val error: String? = null
)
