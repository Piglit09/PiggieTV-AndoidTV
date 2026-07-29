package com.piggie.tv.diagnostics

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
    }

    @Test
    fun `debug diagnostic experiment names are bounded`() {
        assertTrue(DiagnosticsExperiment.fromWireName("disabled") == DiagnosticsExperiment.DISABLED)
        assertTrue(DiagnosticsExperiment.fromWireName("full") == DiagnosticsExperiment.FULL)
        assertTrue(DiagnosticsExperiment.fromWireName("unexpected") == DiagnosticsExperiment.AUTO)
    }
}
