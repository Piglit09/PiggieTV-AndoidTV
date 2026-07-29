package com.piggie.tv.diagnostics

object DiagnosticsInstrumentationPolicy {
    fun shouldAttachFrameMetrics(buildEnabled: Boolean, userEnabled: Boolean): Boolean =
        buildEnabled && userEnabled
}

enum class DiagnosticsExperiment(val wireName: String) {
    AUTO("auto"),
    DISABLED("disabled"),
    ENABLED_NO_OVERLAY("enabled_no_overlay"),
    FULL("full");

    companion object {
        const val DEBUG_EXTRA = "ptv_diagnostics_experiment"

        fun fromWireName(value: String?): DiagnosticsExperiment =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: AUTO
    }
}
