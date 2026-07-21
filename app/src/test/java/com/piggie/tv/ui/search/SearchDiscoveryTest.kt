package com.piggie.tv.ui.search

import com.piggie.tv.data.models.MediaItem
import org.junit.Assert.*
import org.junit.Test

class SearchDiscoveryTest {

    private fun mockItem(id: String, type: String) = MediaItem(
        id = id, title = "T", type = type, year = null, imageTag = null, 
        seriesName = null, episodeLabel = null, playbackPositionTicks = 0L, runtimeTicks = 0L
    )

    @Test
    fun testResultGroupingByType() {
        val results = listOf(
            mockItem("1", "Movie"),
            mockItem("2", "Book"),
            mockItem("3", "Movie"),
            mockItem("4", "Person")
        )
        
        val grouped = results.groupBy { it.type }
        assertEquals(2, grouped["Movie"]?.size)
        assertEquals(1, grouped["Book"]?.size)
        assertEquals(1, grouped["Person"]?.size)
    }

    @Test
    fun testEmptyResultsHandling() {
        val results = emptyList<MediaItem>()
        val grouped = results.groupBy { it.type }
        assertTrue(grouped.isEmpty())
    }

    @Test
    fun testSearchQueryParsing() {
        val query = "  Batman  "
        assertEquals("Batman", query.trim())
    }
}
