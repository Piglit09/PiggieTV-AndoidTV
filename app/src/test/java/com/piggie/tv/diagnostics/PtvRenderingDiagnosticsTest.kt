package com.piggie.tv.diagnostics

import com.piggie.tv.ui.rendering.TvRenderingRuntime
import com.piggie.tv.ui.rendering.RendererFeaturePolicy
import com.piggie.tv.ui.rendering.RenderingExperiment
import com.piggie.tv.ui.rendering.TvRenderingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PtvRenderingDiagnosticsTest {
    @Test
    fun `AFTKM safe static glass capabilities are diagnostics visible`() {
        val capabilities = RendererFeaturePolicy.capabilitySnapshot(
            TvRenderingProfile.FIRE_TV_PERFORMANCE,
            RenderingExperiment.AUTO
        )
        val json = PtvDiagnosticExporter.renderingJson(capabilities)

        assertEquals("FIRE_TV_PERFORMANCE", json.getString("renderingProfile"))
        assertEquals("auto", json.getString("renderingExperiment"))
        assertEquals("SAFE_STATIC_GLASS", json.getJSONArray("visualFeatureLevels").getString(0))
        assertTrue(json.getBoolean("staticGlassEnabled"))
        assertTrue(json.getBoolean("shelfGlassEnabled"))
        assertTrue(json.getBoolean("iceCardEnabled"))
        assertTrue(json.getBoolean("progressGradientEnabled"))
        assertFalse(json.getBoolean("enhancedGlassEnabled"))
        assertFalse(json.getBoolean("expensiveEffectsEnabled"))
        assertFalse(json.getBoolean("animatedEffectsEnabled"))
        assertEquals(1.0, json.getDouble("focusScale"), 0.0)
        assertEquals(0.0, json.getDouble("focusElevation"), 0.0)
        assertFalse(json.getBoolean("blurEnabled"))
    }

    @Test
    fun `runtime caches copy producing features and invalidates on reconfigure`() {
        try {
            // This experiment calls data-class copy() for every policy resolution, so identity
            // reuse can only come from TvRenderingRuntime's resolution cache.
            TvRenderingRuntime.configureDebugExperiment("b_no_dynamic_backdrop")
            val firstResolution = TvRenderingRuntime.features()

            assertSame(firstResolution, TvRenderingRuntime.features())

            // Reconfiguring even to the same experiment must invalidate the cached resolution.
            TvRenderingRuntime.configureDebugExperiment("b_no_dynamic_backdrop")
            val reconfiguredResolution = TvRenderingRuntime.features()
            assertNotSame(firstResolution, reconfiguredResolution)
            assertSame(reconfiguredResolution, TvRenderingRuntime.features())

            TvRenderingRuntime.configureDebugExperiment("e_opaque_backgrounds")
            val changedExperimentResolution = TvRenderingRuntime.features()
            assertNotSame(reconfiguredResolution, changedExperimentResolution)
            assertSame(changedExperimentResolution, TvRenderingRuntime.features())
        } finally {
            TvRenderingRuntime.configureDebugExperiment(null)
        }
    }
}
