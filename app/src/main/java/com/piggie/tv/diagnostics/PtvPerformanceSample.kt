package com.piggie.tv.diagnostics

data class PtvPerformanceSample(
    val timestampMs: Long = System.currentTimeMillis(),
    val route: String,
    val frameCount: Int,
    val averageFps: Double?,
    val frameTimeP50Ms: Double?,
    val frameTimeP95Ms: Double?,
    val frameTimeP99Ms: Double?,
    val slowFrames: Int,
    val frozenFrames: Int,
    val maxFrameMs: Double?,
    val pssKb: Int,
    val javaHeapBytes: Long,
    val nativeHeapBytes: Long,
    val bitmapBytesEstimate: Long,
    val playerCount: Int,
    val activityCount: Int,
    val pageCacheEntries: Int,
    val measurementMethod: String = "Window.FrameMetrics total-duration"
)
