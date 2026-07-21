package com.piggie.tv.data.models

import org.junit.Assert.*
import org.junit.Test

class MediaShelfTest {

    private fun mockItem(id: String) = MediaItem(
        id = id, title = "T", type = "Movie", year = null, imageTag = null, 
        seriesName = null, episodeLabel = null, playbackPositionTicks = 0L, runtimeTicks = 0L
    )

    @Test
    fun testShelfInitialization() {
        val items = listOf(mockItem("1"), mockItem("2"))
        val shelf = MediaShelf("Trending", items, MediaCardPresentation.POSTER)
        
        assertEquals("Trending", shelf.title)
        assertEquals(2, shelf.items.size)
        assertEquals(MediaCardPresentation.POSTER, shelf.presentation)
    }

    @Test
    fun testShelfItemAccess() {
        val item = mockItem("1")
        val shelf = MediaShelf("S", listOf(item), MediaCardPresentation.LANDSCAPE)
        assertEquals("1", shelf.items[0].id)
    }

    @Test
    fun testEmptyShelfAllowed() {
        val shelf = MediaShelf("Empty", emptyList(), MediaCardPresentation.SQUARE)
        assertTrue(shelf.items.isEmpty())
    }
}
