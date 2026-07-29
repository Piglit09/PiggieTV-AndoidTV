package com.piggie.tv.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderComparatorTest {

    @Test
    fun testNaturalSort() {
        val filenames = listOf("page_10.jpg", "page_2.jpg", "page_1.jpg", "page_20.jpg", "001.png", "01.png", "002.png")
        val sorted = filenames.sortedWith(NaturalOrderComparator)

        assertEquals("001.png", sorted[0])
        assertEquals("01.png", sorted[1])
        assertEquals("002.png", sorted[2])
        assertEquals("page_1.jpg", sorted[3])
        assertEquals("page_2.jpg", sorted[4])
        assertEquals("page_10.jpg", sorted[5])
        assertEquals("page_20.jpg", sorted[6])
    }

    @Test
    fun testNestedDirectories() {
        val paths = listOf("vol1/page1.jpg", "vol1/page10.jpg", "vol1/page2.jpg", "cover.jpg")
        val sorted = paths.sortedWith(NaturalOrderComparator)

        assertEquals("cover.jpg", sorted[0])
        assertEquals("vol1/page1.jpg", sorted[1])
        assertEquals("vol1/page2.jpg", sorted[2])
        assertEquals("vol1/page10.jpg", sorted[3])
    }
}
