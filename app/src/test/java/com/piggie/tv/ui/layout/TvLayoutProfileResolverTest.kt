package com.piggie.tv.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class TvLayoutProfileResolverTest {
    @Test
    fun fireTvAftkmMetricsSelectCompact() {
        assertEquals(
            TvLayoutProfile.TV_COMPACT,
            TvLayoutProfileResolver.resolve(
                TvLogicalViewport(960, 540, 540, manufacturer = "Amazon", model = "AFTKM")
            )
        )
    }

    @Test
    fun raw4kOutputDoesNotEnterProfileSelection() {
        // The AFTKM's raw 3840x2160 output is deliberately absent. Only 960x540 dp matters.
        assertEquals(
            TvLayoutProfile.TV_COMPACT,
            TvLayoutProfileResolver.resolve(TvLogicalViewport(960, 540, 540))
        )
    }

    @Test
    fun largerLogicalCanvasesSelectStandardAndLarge() {
        assertEquals(
            TvLayoutProfile.TV_STANDARD,
            TvLayoutProfileResolver.resolve(TvLogicalViewport(1280, 720, 720))
        )
        assertEquals(
            TvLayoutProfile.TV_LARGE,
            TvLayoutProfileResolver.resolve(TvLogicalViewport(1920, 1080, 1080))
        )
    }
}
