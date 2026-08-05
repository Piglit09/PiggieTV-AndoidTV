package com.piggie.tv.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvCompactDimensionsTest {
    @Test fun compactPlayerAndDialogMeetPhysicalTargets() {
        assertEquals(64, TvCompactTargets.PRIMARY_PLAYER_CONTROL_DP)
        assertEquals(44, TvCompactTargets.SECONDARY_PLAYER_CONTROL_DP)
        assertTrue(TvCompactTargets.DIALOG_WIDTH_DP <= TvCompactTargets.CANVAS_WIDTH_DP * 0.45f)
    }

    @Test fun compactCardsMeetVisibleCounts() {
        val usableWidth = TvCompactTargets.CANVAS_WIDTH_DP - TvCompactTargets.HORIZONTAL_MARGIN_DP * 2
        assertTrue(usableWidth.toFloat() / TvCompactTargets.POSTER_WIDTH_DP >= 8f)
        assertTrue(usableWidth.toFloat() / TvCompactTargets.LANDSCAPE_WIDTH_DP >= 4f)
        assertTrue(usableWidth.toFloat() / TvCompactTargets.SQUARE_WIDTH_DP >= 7f)
    }

    @Test fun compactBaselineIsCanonicalAndNotPercentageDerived() {
        assertEquals(960, TvCompactTargets.CANVAS_WIDTH_DP)
        assertEquals(540, TvCompactTargets.CANVAS_HEIGHT_DP)
        assertEquals(215, TvCompactTargets.HERO_HEIGHT_DP)
    }
}
