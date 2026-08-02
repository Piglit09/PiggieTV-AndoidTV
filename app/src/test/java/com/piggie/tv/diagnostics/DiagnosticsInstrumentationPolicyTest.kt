package com.piggie.tv.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsInstrumentationPolicyTest {
    @Test
    fun `disabled diagnostics never attach frame profiling`() {
        assertFalse(DiagnosticsInstrumentationPolicy.shouldAttachFrameMetrics(true, false))
        assertFalse(DiagnosticsInstrumentationPolicy.shouldAttachFrameMetrics(false, true))
        assertFalse(DiagnosticsInstrumentationPolicy.shouldAttachFrameMetrics(false, false))
    }

    @Test
    fun `frame profiling requires both build and user enablement`() {
        assertTrue(DiagnosticsInstrumentationPolicy.shouldAttachFrameMetrics(true, true))
        assertFalse(
            DiagnosticsInstrumentationPolicy.shouldAttachFrameMetrics(
                true,
                DiagnosticsCollectionMode.DISABLED
            )
        )
        assertTrue(
            DiagnosticsInstrumentationPolicy.shouldAttachFrameMetrics(
                true,
                DiagnosticsCollectionMode.SUMMARY
            )
        )
    }

    @Test
    fun `debug diagnostic experiment names are bounded`() {
        assertEquals(DiagnosticsExperiment.DISABLED, DiagnosticsExperiment.fromWireName("disabled"))
        assertEquals(
            DiagnosticsExperiment.ENABLED_NO_OVERLAY,
            DiagnosticsExperiment.fromWireName("enabled_no_overlay")
        )
        assertEquals(DiagnosticsExperiment.SHELF_TRACE, DiagnosticsExperiment.fromWireName("shelf_trace"))
        assertEquals(DiagnosticsExperiment.FULL_TRACE, DiagnosticsExperiment.fromWireName("full_trace"))
        assertEquals(
            DiagnosticsExperiment.OVERLAY_VISIBLE,
            DiagnosticsExperiment.fromWireName("overlay_visible")
        )
        assertEquals(DiagnosticsExperiment.FULL, DiagnosticsExperiment.fromWireName("full"))
        assertEquals(DiagnosticsExperiment.AUTO, DiagnosticsExperiment.fromWireName("unexpected"))
        assertEquals(
            DiagnosticsCollectionMode.SUMMARY,
            DiagnosticsExperiment.ENABLED_NO_OVERLAY.collectionMode
        )
        assertEquals(false, DiagnosticsExperiment.FULL_TRACE.overlayVisible)
        assertEquals(true, DiagnosticsExperiment.OVERLAY_VISIBLE.overlayVisible)
    }

    @Test
    fun `summary shelf and full modes gate high-volume collection`() {
        assertFalse(DiagnosticsInstrumentationPolicy.shouldCollectShelfTrace(DiagnosticsCollectionMode.SUMMARY))
        assertFalse(DiagnosticsInstrumentationPolicy.shouldCollectFocusTrace(DiagnosticsCollectionMode.SUMMARY))

        assertTrue(DiagnosticsInstrumentationPolicy.shouldCollectShelfTrace(DiagnosticsCollectionMode.SHELF_TRACE))
        assertFalse(DiagnosticsInstrumentationPolicy.shouldCollectFocusTrace(DiagnosticsCollectionMode.SHELF_TRACE))

        assertTrue(DiagnosticsInstrumentationPolicy.shouldCollectShelfTrace(DiagnosticsCollectionMode.FULL_TRACE))
        assertTrue(DiagnosticsInstrumentationPolicy.shouldCollectFocusTrace(DiagnosticsCollectionMode.FULL_TRACE))
    }

    @Test
    fun `event policy keeps focus hot path scoped without dropping other traces`() {
        assertTrue(
            DiagnosticsInstrumentationPolicy.shouldRecordEvent(
                DiagnosticsCollectionMode.SUMMARY,
                "diagnostics",
                "enabled"
            )
        )
        assertFalse(
            DiagnosticsInstrumentationPolicy.shouldRecordEvent(
                DiagnosticsCollectionMode.SUMMARY,
                "focus",
                "artwork_bounds"
            )
        )
        assertTrue(
            DiagnosticsInstrumentationPolicy.shouldRecordEvent(
                DiagnosticsCollectionMode.SUMMARY,
                "route",
                "visible"
            )
        )
        assertTrue(
            DiagnosticsInstrumentationPolicy.shouldRecordEvent(
                DiagnosticsCollectionMode.SHELF_TRACE,
                "focus",
                "shelf_center"
            )
        )
        assertTrue(
            DiagnosticsInstrumentationPolicy.shouldRecordEvent(
                DiagnosticsCollectionMode.SHELF_TRACE,
                "focus",
                "artwork_bounds"
            )
        )
    }
}
