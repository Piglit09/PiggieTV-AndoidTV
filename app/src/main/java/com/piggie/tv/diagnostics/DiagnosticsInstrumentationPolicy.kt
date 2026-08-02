package com.piggie.tv.diagnostics

object DiagnosticsInstrumentationPolicy {
    fun shouldAttachFrameMetrics(buildEnabled: Boolean, userEnabled: Boolean): Boolean =
        buildEnabled && userEnabled

    fun shouldAttachFrameMetrics(buildEnabled: Boolean, mode: DiagnosticsCollectionMode): Boolean =
        buildEnabled && mode != DiagnosticsCollectionMode.DISABLED

    fun shouldCollectShelfTrace(mode: DiagnosticsCollectionMode): Boolean =
        mode.level >= DiagnosticsCollectionMode.SHELF_TRACE.level

    fun shouldCollectFocusTrace(mode: DiagnosticsCollectionMode): Boolean =
        mode.level >= DiagnosticsCollectionMode.FULL_TRACE.level

    fun shouldCollectFullTrace(mode: DiagnosticsCollectionMode): Boolean =
        mode.level >= DiagnosticsCollectionMode.FULL_TRACE.level

    fun shouldRecordEvent(
        mode: DiagnosticsCollectionMode,
        category: String,
        name: String
    ): Boolean = when (mode) {
        DiagnosticsCollectionMode.DISABLED -> false
        DiagnosticsCollectionMode.SUMMARY -> category != "focus"
        DiagnosticsCollectionMode.SHELF_TRACE ->
            category != "focus" || name == "shelf_center" || name == "artwork_bounds"
        DiagnosticsCollectionMode.FULL_TRACE -> true
    }
}

enum class DiagnosticsCollectionMode(internal val level: Int) {
    DISABLED(0),
    SUMMARY(1),
    SHELF_TRACE(2),
    FULL_TRACE(3)
}

enum class DiagnosticsExperiment(
    val wireName: String,
    val collectionMode: DiagnosticsCollectionMode?,
    val overlayVisible: Boolean?
) {
    AUTO("auto", null, null),
    DISABLED("disabled", DiagnosticsCollectionMode.DISABLED, false),
    ENABLED_NO_OVERLAY("enabled_no_overlay", DiagnosticsCollectionMode.SUMMARY, false),
    SHELF_TRACE("shelf_trace", DiagnosticsCollectionMode.SHELF_TRACE, false),
    FULL_TRACE("full_trace", DiagnosticsCollectionMode.FULL_TRACE, false),
    OVERLAY_VISIBLE("overlay_visible", DiagnosticsCollectionMode.FULL_TRACE, true),

    /** Kept as a wire-compatible alias for older ADB scripts. */
    FULL("full", DiagnosticsCollectionMode.FULL_TRACE, true);

    companion object {
        const val DEBUG_EXTRA = "ptv_diagnostics_experiment"

        fun fromWireName(value: String?): DiagnosticsExperiment =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: AUTO
    }
}
