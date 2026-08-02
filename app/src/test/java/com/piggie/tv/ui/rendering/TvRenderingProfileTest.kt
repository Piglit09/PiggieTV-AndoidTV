package com.piggie.tv.ui.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRenderingProfileTest {
    @Test
    fun `AFTKM selects Fire TV performance rendering independently of layout`() {
        assertEquals(
            TvRenderingProfile.FIRE_TV_PERFORMANCE,
            TvRenderingProfileResolver.resolve(TvRenderingDevice("Amazon", "AFTKM"))
        )
    }

    @Test
    fun `other hardware keeps rich rendering`() {
        assertEquals(
            TvRenderingProfile.RICH,
            TvRenderingProfileResolver.resolve(TvRenderingDevice("Google", "ADT-3"))
        )
    }

    @Test
    fun `Fire TV keeps low cost rendering while enabling safe static glass`() {
        val features = RendererFeaturePolicy.fireTvPerformance
        assertTrue(features.flatRectangularCards)
        assertTrue(features.cardClipping)
        assertTrue(features.opaqueRoots)
        assertFalse(features.dynamicBrowsingBackdrops)
        assertFalse(features.gradients)
        assertFalse(features.alphaOverlays)
        assertEquals(FocusIndicatorStrategy.SINGLE_OVERLAY, features.focusIndicator)
        assertEquals(DetailsArchitecture.IN_HOST_FRAGMENT, features.detailsArchitecture)
        assertEquals(DetailsBackdropMode.DELAYED, features.detailsBackdrop)
        assertTrue(features.staticGlassEnabled)
        assertTrue(features.shelfGlassEnabled)
        assertTrue(features.iceCardEnabled)
        assertTrue(features.progressGradientEnabled)
        assertEquals(setOf(VisualFeatureLevel.SAFE_STATIC_GLASS), features.visualFeatureLevels())
        assertFalse(features.enhancedGlassEnabled)
        assertFalse(features.expensiveEffectsEnabled)
        assertFalse(features.animatedEffectsEnabled)
        assertEquals(1f, features.focusScale, 0f)
        assertEquals(0f, features.focusElevation, 0f)
        assertFalse(features.blurEnabled)
    }

    @Test
    fun `debug experiments isolate one rendering variable`() {
        val profile = TvRenderingProfile.FIRE_TV_PERFORMANCE
        assertEquals(7, RendererFeaturePolicy.forExperiment(profile, RenderingExperiment.A_CURRENT).posterSpanCount)
        assertFalse(
            RendererFeaturePolicy.forExperiment(
                profile,
                RenderingExperiment.B_NO_DYNAMIC_BACKDROP
            ).dynamicBrowsingBackdrops
        )
        assertEquals(
            6,
            RendererFeaturePolicy.forExperiment(
                profile,
                RenderingExperiment.F_SIX_VISIBLE_POSTERS
            ).posterSpanCount
        )
    }

    @Test
    fun `flat and safe AFTKM experiments differ only in static visual capabilities`() {
        val profile = TvRenderingProfile.FIRE_TV_PERFORMANCE
        val flat = RendererFeaturePolicy.forExperiment(profile, RenderingExperiment.FLAT_FIRE_FALLBACK)
        val safe = RendererFeaturePolicy.forExperiment(profile, RenderingExperiment.SAFE_STATIC_GLASS)

        assertEquals(
            flat.copy(
                staticGlassEnabled = true,
                shelfGlassEnabled = true,
                iceCardEnabled = true,
                progressGradientEnabled = true
            ),
            safe
        )
        assertFalse(flat.staticGlassEnabled)
        assertFalse(flat.shelfGlassEnabled)
        assertFalse(flat.iceCardEnabled)
        assertFalse(flat.progressGradientEnabled)
        assertEquals(
            RenderingExperiment.FLAT_FIRE_FALLBACK,
            RenderingExperiment.fromWireName("flat_fire_fallback")
        )
        assertEquals(
            RenderingExperiment.SAFE_STATIC_GLASS,
            RenderingExperiment.fromWireName("safe_static_glass")
        )
    }

    @Test
    fun `AFTKM capability snapshot exposes accepted focus and effect policy`() {
        val snapshot = RendererFeaturePolicy.capabilitySnapshot(
            TvRenderingProfile.FIRE_TV_PERFORMANCE,
            RenderingExperiment.AUTO
        )

        assertEquals("FIRE_TV_PERFORMANCE", snapshot.renderingProfile)
        assertEquals("auto", snapshot.renderingExperiment)
        assertEquals(listOf("SAFE_STATIC_GLASS"), snapshot.visualFeatureLevels)
        assertTrue(snapshot.staticGlassEnabled)
        assertTrue(snapshot.shelfGlassEnabled)
        assertTrue(snapshot.iceCardEnabled)
        assertTrue(snapshot.progressGradientEnabled)
        assertFalse(snapshot.enhancedGlassEnabled)
        assertFalse(snapshot.expensiveEffectsEnabled)
        assertFalse(snapshot.animatedEffectsEnabled)
        assertEquals(1f, snapshot.focusScale, 0f)
        assertEquals(0f, snapshot.focusElevation, 0f)
        assertFalse(snapshot.blurEnabled)
    }
}
