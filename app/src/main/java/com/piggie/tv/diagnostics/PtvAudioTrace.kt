package com.piggie.tv.diagnostics

data class PtvAudioTrace(
    val timestampMs: Long = System.currentTimeMillis(),
    val event: String,
    val serviceState: String? = null,
    val mediaSessionState: String? = null,
    val queueSize: Int? = null,
    val currentIndex: Int? = null,
    val audioFocus: String? = null,
    val notificationState: String? = null,
    val miniPlayerState: String? = null,
    val error: String? = null
)
