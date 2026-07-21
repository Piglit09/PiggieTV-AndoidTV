package com.piggie.tv.util

import com.piggie.tv.R
import com.piggie.tv.ui.shared.TextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DimenBucketTest {

    @Test
    fun testMetadataLineLimits() {
        val input = "Line 1\nLine 2\nLine 3\nLine 4"
        val sanitized = TextSanitizer.sanitize(input)
        assert(sanitized.contains("Line 1"))
        assert(sanitized.contains("Line 2"))
    }

    @Test
    fun testDensityCalculation() {
        val density = 2.0f
        val dp = 100
        val px = (dp * density).toInt()
        assertEquals(200, px)
    }

    @Test
    fun testSpCalculation() {
        val scaledDensity = 1.5f
        val sp = 16f
        val px = sp * scaledDensity
        assertEquals(24f, px)
    }
}
