package com.piggie.tv.diagnostics

data class PtvDiagnosticEvent(
    val timestampMs: Long = System.currentTimeMillis(),
    val category: String,
    val name: String,
    val route: String? = null,
    val attributes: Map<String, String> = emptyMap()
)
