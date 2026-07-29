package com.piggie.tv.diagnostics

data class PtvFocusTrace(
    val timestampMs: Long = System.currentTimeMillis(),
    val route: String,
    val direction: String,
    val previousFocus: String?,
    val resultingFocus: String?,
    val resultingBounds: String? = null,
    val failure: String? = null
)
