package com.piggie.tv.diagnostics

data class PtvPlaybackTrace(
    val timestampMs: Long = System.currentTimeMillis(),
    val itemId: String,
    val event: String,
    val playMethod: String? = null,
    val mediaSourceId: String? = null,
    val codecs: String? = null,
    val container: String? = null,
    val resolution: String? = null,
    val bitrate: Long? = null,
    val connectionSpeed: String? = null,
    val negotiatedBitrate: Int? = null,
    val startupMs: Long? = null,
    val bufferCount: Int? = null,
    val droppedFrames: Int? = null,
    val decoder: String? = null,
    val audioTrack: String? = null,
    val subtitleTrack: String? = null,
    val detail: String? = null
)
