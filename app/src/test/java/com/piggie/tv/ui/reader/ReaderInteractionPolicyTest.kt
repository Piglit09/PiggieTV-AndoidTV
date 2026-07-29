package com.piggie.tv.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderInteractionPolicyTest {
    @Test fun fitPageUsesSmallerAxisAndCentersPortraitPage() {
        val fit = ReaderFitCalculator.calculate(
            ReaderFitMode.FIT_PAGE,
            availableWidth = 1920,
            availableHeight = 1080,
            pageWidth = 2000,
            pageHeight = 3000
        )
        assertEquals(0.36f, fit.scale, 0.0001f)
        assertEquals(720f, fit.scaledWidth, 0.1f)
        assertEquals(1080f, fit.scaledHeight, 0.1f)
        assertEquals(600f, fit.offsetX, 0.1f)
    }

    @Test fun fitWidthUsesAvailableWidth() {
        val fit = ReaderFitCalculator.calculate(
            ReaderFitMode.FIT_WIDTH,
            1000,
            500,
            2000,
            3000
        )
        assertEquals(0.5f, fit.scale, 0.0001f)
        assertEquals(1000f, fit.scaledWidth, 0.1f)
        assertEquals(1500f, fit.scaledHeight, 0.1f)
    }

    @Test fun automaticFitNeverUpscalesByDefault() {
        val fit = ReaderFitCalculator.calculate(ReaderFitMode.FIT_PAGE, 1920, 1080, 640, 480)
        assertEquals(1f, fit.scale, 0f)
    }

    @Test fun ltrAndRtlNavigationAreReversed() {
        assertEquals(
            ReaderPageAction.NEXT,
            ReaderNavigationPolicy.pageAction(ReaderHorizontalDirection.RIGHT, isRtl = false)
        )
        assertEquals(
            ReaderPageAction.PREVIOUS,
            ReaderNavigationPolicy.pageAction(ReaderHorizontalDirection.RIGHT, isRtl = true)
        )
        assertEquals(
            ReaderPageAction.NEXT,
            ReaderNavigationPolicy.pageAction(ReaderHorizontalDirection.LEFT, isRtl = true)
        )
    }

    @Test fun zoomedEdgeRequiresSecondPress() {
        val gate = ReaderEdgeTurnGate(timeoutMs = 1000)
        assertFalse(gate.onEdgePress(ReaderHorizontalDirection.RIGHT, 100))
        assertTrue(gate.onEdgePress(ReaderHorizontalDirection.RIGHT, 500))
        assertFalse(gate.onEdgePress(ReaderHorizontalDirection.RIGHT, 600))
        gate.reset()
        assertFalse(gate.onEdgePress(ReaderHorizontalDirection.LEFT, 700))
    }
}
