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
    fun `Fire TV feature flags flatten GPU-heavy browsing work`() {
        val features = RendererFeaturePolicy.fireTvPerformance
        assertTrue(features.flatRectangularCards)
        assertTrue(features.opaqueRoots)
        assertFalse(features.dynamicBrowsingBackdrops)
        assertFalse(features.gradients)
        assertFalse(features.alphaOverlays)
        assertEquals(FocusIndicatorStrategy.SINGLE_OVERLAY, features.focusIndicator)
        assertEquals(DetailsArchitecture.IN_HOST_FRAGMENT, features.detailsArchitecture)
        assertEquals(DetailsBackdropMode.DELAYED, features.detailsBackdrop)
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
}
