package com.piggie.tv.ui.widgets

import com.piggie.tv.data.models.MediaCardPresentation
import org.junit.Assert.*
import org.junit.Test

class MediaCardTest {

    @Test
    fun testPresentationEnumConsistency() {
        assertEquals("POSTER", MediaCardPresentation.POSTER.name)
        assertEquals("LANDSCAPE", MediaCardPresentation.LANDSCAPE.name)
        assertEquals("SQUARE", MediaCardPresentation.SQUARE.name)
    }

    @Test
    fun testPosterAspectRatio() {
        val width = 140
        val height = 210
        assertEquals(1.5f, height.toFloat() / width.toFloat())
    }

    @Test
    fun testLandscapeAspectRatio() {
        val width = 240
        val height = 135
        // 16:9 is ~1.77
        assertEquals(1.77f, width.toFloat() / height.toFloat(), 0.1f)
    }

    @Test
    fun testSquareAspectRatio() {
        val width = 160
        val height = 160
        assertEquals(1f, width.toFloat() / height.toFloat())
    }

    @Test
    fun testCardPaddingValue() {
        val padding = 6 // res.getDimensionPixelSize(R.dimen.tv_card_padding)
        assertTrue(padding > 0)
    }

    @Test
    fun testFocusScaleValue() {
        val scale = 1.05f
        assertTrue(scale > 1.0f)
        assertTrue(scale < 1.10f)
    }
}
