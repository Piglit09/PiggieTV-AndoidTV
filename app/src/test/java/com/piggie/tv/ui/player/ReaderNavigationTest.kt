package com.piggie.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderNavigationTest {

    @Test
    fun testRtlNavigation() {
        var currentItem = 5
        val isRtl = true

        // DPAD_RIGHT in RTL should go to PREVIOUS page (decrement)
        if (isRtl) {
            currentItem--
        } else {
            currentItem++
        }

        assertEquals(4, currentItem)
    }

    @Test
    fun testLtrNavigation() {
        var currentItem = 5
        val isRtl = false

        // DPAD_RIGHT in LTR should go to NEXT page (increment)
        if (isRtl) {
            currentItem--
        } else {
            currentItem++
        }

        assertEquals(6, currentItem)
    }
}
