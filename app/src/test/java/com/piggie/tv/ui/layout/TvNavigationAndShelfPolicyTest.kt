package com.piggie.tv.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvNavigationAndShelfPolicyTest {
    @Test
    fun centeredNavigationHasNoAlignmentError() {
        val alignment = TvNavigationAlignmentPolicy.measure(
            screenLeftPx = 0,
            screenWidthPx = 960,
            navigationLeftPx = 240,
            navigationWidthPx = 480,
            density = 1f
        )

        assertEquals(0f, alignment.errorDp, 0f)
        assertEquals(0f, alignment.offsetDp, 0f)
        assertTrue(alignment.withinTolerance)
    }

    @Test
    fun navigationToleranceIsFourDp() {
        assertTrue(
            TvNavigationAlignmentPolicy.measure(0, 960, 244, 480, 1f).withinTolerance
        )
        assertFalse(
            TvNavigationAlignmentPolicy.measure(0, 960, 245, 480, 1f).withinTolerance
        )
        assertEquals(
            -4f,
            TvNavigationAlignmentPolicy.measure(0, 960, 236, 480, 1f).offsetDp,
            0f
        )
    }

    @Test
    fun shelfArtworkCenterTargetsFortyFivePercentOfViewport() {
        val requestedTop = ShelfAnchorPolicy.requestedRowTop(
            viewportHeight = 400,
            artworkCenterOffsetInRow = 80
        )

        assertEquals(100, requestedTop)
        assertEquals(180, requestedTop + 80)
    }
}
