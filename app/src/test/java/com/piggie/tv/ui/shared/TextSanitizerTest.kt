package com.piggie.tv.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TextSanitizerTest {

    @Test
    fun sanitizerConvertsHtmlBreaks() {
        val input = "Line 1<br>Line 2<br />Line 3"
        val expected = "Line 1\nLine 2\nLine 3"
        assertEquals(expected, TextSanitizer.sanitize(input))
    }

    @Test
    fun sanitizerConvertsParagraphs() {
        val input = "<p>P1</p><p>P2</p>"
        val expected = "P1\n\nP2"
        assertEquals(expected, TextSanitizer.sanitize(input))
    }

    @Test
    fun sanitizerDecodesEntities() {
        val input = "Tom &amp; Jerry"
        assertEquals("Tom & Jerry", TextSanitizer.sanitize(input))
    }

    @Test
    fun metadataFormattingHandlesNulls() {
        assertEquals("2024", TextSanitizer.formatMetadata("2024", null, ""))
        assertEquals("2024  •  R", TextSanitizer.formatMetadata("2024", "R"))
        assertEquals("", TextSanitizer.formatMetadata(null, "", "  "))
    }

    @Test
    fun metadataFormattingJoinsMultipleParts() {
        assertEquals("A  •  B  •  C", TextSanitizer.formatMetadata("A", "B", "C"))
        assertEquals("A  •  C", TextSanitizer.formatMetadata("A", null, "C"))
        assertEquals("A", TextSanitizer.formatMetadata("A"))
    }
}
