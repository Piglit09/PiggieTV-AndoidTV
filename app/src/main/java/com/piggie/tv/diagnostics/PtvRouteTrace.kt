package com.piggie.tv.diagnostics

data class PtvRouteTrace(
    val route: String,
    val requestedAtMs: Long,
    val visibleAtMs: Long? = null,
    val firstContentAtMs: Long? = null,
    val firstImageAtMs: Long? = null,
    val interactiveAtMs: Long? = null,
    val focusOwner: String? = null,
    val requestCount: Int = 0,
    val failureCount: Int = 0,
    val retryCount: Int = 0
)
