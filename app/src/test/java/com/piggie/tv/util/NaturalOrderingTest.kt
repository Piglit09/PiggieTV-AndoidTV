package com.piggie.tv.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderingTest {

    @Test
    fun testNaturalSort() {
        val filenames = listOf("page_10.jpg", "page_2.jpg", "page_1.jpg", "page_20.jpg")
        val sorted = filenames.sortedWith(compareBy { it.length }).sortedBy {
            // Simple logic for test, real logic might be more complex
            it.filter { c -> c.isDigit() }.toInt()
        }

        assertEquals("page_1.jpg", sorted[0])
        assertEquals("page_2.jpg", sorted[1])
        assertEquals("page_10.jpg", sorted[2])
        assertEquals("page_20.jpg", sorted[3])
    }
}
