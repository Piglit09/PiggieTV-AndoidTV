package com.piggie.tv.ui.search

import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.MediaCardPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchDiscoveryTest {

    private fun mockItem(id: String, type: String) = MediaItem(
        id = id, title = "T", type = type, year = null, imageTag = null, 
        seriesName = null, episodeLabel = null, playbackPositionTicks = 0L, runtimeTicks = 0L
    )

    @Test
    fun booksAndUnknownTypesAreExcludedFromTvSearch() {
        val results = listOf(
            mockItem("1", "Movie"),
            mockItem("2", "Book"),
            mockItem("3", "Movie"),
            mockItem("4", "Person"),
            mockItem("5", "Unsupported")
        )

        val shelves = SearchResultPolicy.shelves(results)

        assertEquals(listOf("Movies", "People"), shelves.map { it.title })
        assertEquals(listOf("1", "3", "4"), shelves.flatMap { it.items }.map { it.id })
        assertFalse(shelves.flatMap { it.items }.any { it.type == "Book" })
    }

    @Test
    fun groupsUseStableOrderTitlesAndPresentations() {
        val results = listOf(
            mockItem("person", "Person"),
            mockItem("song", "Audio"),
            mockItem("album", "MusicAlbum"),
            mockItem("artist", "MusicArtist"),
            mockItem("series", "Series"),
            mockItem("movie", "Movie")
        )

        val shelves = SearchResultPolicy.shelves(results)

        assertEquals(
            listOf("Movies", "Series", "Artists", "Albums", "Songs", "People"),
            shelves.map { it.title }
        )
        assertEquals(
            listOf(
                MediaCardPresentation.POSTER,
                MediaCardPresentation.POSTER,
                MediaCardPresentation.SQUARE,
                MediaCardPresentation.LANDSCAPE,
                MediaCardPresentation.LANDSCAPE,
                MediaCardPresentation.SQUARE
            ),
            shelves.map { it.presentation }
        )
    }

    @Test
    fun unsupportedOnlyResultsProduceNoShelves() {
        assertTrue(SearchResultPolicy.shelves(listOf(mockItem("book", "Book"))).isEmpty())
    }

    @Test
    fun queryAndCompactSafeMarginAreCanonical() {
        assertEquals("Batman", SearchResultPolicy.normalizeQuery("  Batman  "))
        assertEquals(20, SearchResultPolicy.COMPACT_HORIZONTAL_SAFE_MARGIN_DP)
        assertEquals(40, SearchResultPolicy.compactSafeMarginPx(density = 2f))
    }
}
