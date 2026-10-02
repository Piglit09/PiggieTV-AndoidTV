package com.piggie.tv.ui.search

import com.piggie.tv.data.discovery.DiscoveryFilterType
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.MediaCardPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchDiscoveryTest {

    private fun mockItem(id: String, type: String, title: String = "T") = MediaItem(
        id = id, title = title, type = type, year = null, imageTag = null,
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
    fun genresMergeVideoAndMusicTaxonomyWhileStudiosRemainSeparate() {
        val shelves = SearchResultPolicy.shelves(
            listOf(
                mockItem("music-rock", "MusicGenre", "Rock"),
                mockItem("studio", "Studio", "A24"),
                mockItem("video-rock", "Genre", "Rock")
            )
        )

        assertEquals(listOf("Genres", "Studios"), shelves.map { it.title })
        assertEquals(
            listOf("Genre", "MusicGenre"),
            shelves.first().items.map { it.type }
        )
        assertEquals(listOf("Studio"), shelves.last().items.map { it.type })
    }

    @Test
    fun categoryScopesOnlyExposeTheirMatchingShelf() {
        val results = listOf(
            mockItem("movie", "Movie"),
            mockItem("genre", "Genre"),
            mockItem("music-genre", "MusicGenre"),
            mockItem("studio", "Studio")
        )

        assertEquals(
            listOf("Genres"),
            SearchResultPolicy.shelves(results, SearchResultPolicy.Scope.GENRES).map { it.title }
        )
        assertEquals(
            listOf("Studios"),
            SearchResultPolicy.shelves(results, SearchResultPolicy.Scope.STUDIOS).map { it.title }
        )
    }

    @Test
    fun scopeStateAndServerBranchesAreCanonical() {
        val all = SearchResultPolicy.Scope.ALL
        assertFalse("Book" in all.itemTypes)
        assertTrue(all.includeGenres)
        assertTrue(all.includeStudios)
        assertEquals(SearchResultPolicy.Scope.GENRES, SearchResultPolicy.Scope.fromStored("genres"))
        assertEquals(
            SearchResultPolicy.Scope.STUDIOS,
            SearchResultPolicy.Scope.fromControlKey(SearchResultPolicy.Scope.STUDIOS.controlKey)
        )
        assertEquals(SearchResultPolicy.Scope.ALL, SearchResultPolicy.Scope.fromStored("invalid"))
        assertNull(SearchResultPolicy.Scope.fromControlKey("input"))
    }

    @Test
    fun taxonomyActivationCreatesNativeFilteredCatalogRequests() {
        val genre = requireNotNull(
            SearchResultPolicy.categoryBrowseRequest(mockItem("genre", "Genre", "Drama"))
        )
        assertEquals(DiscoveryFilterType.GENRE, genre.filter.type)
        assertEquals("Drama", genre.filter.value)
        assertEquals(listOf("Movie", "Series"), genre.itemTypes)

        val musicGenre = requireNotNull(
            SearchResultPolicy.categoryBrowseRequest(
                mockItem("music-genre", "MusicGenre", "Alternative")
            )
        )
        assertEquals(DiscoveryFilterType.GENRE, musicGenre.filter.type)
        assertEquals(listOf("MusicAlbum"), musicGenre.itemTypes)

        val studio = requireNotNull(
            SearchResultPolicy.categoryBrowseRequest(mockItem("studio", "Studio", "A24"))
        )
        assertEquals(DiscoveryFilterType.STUDIO, studio.filter.type)
        assertEquals("A24", studio.filter.value)
        assertEquals(listOf("Movie", "Series"), studio.itemTypes)
        assertNull(SearchResultPolicy.categoryBrowseRequest(mockItem("movie", "Movie")))
    }

    @Test
    fun queryAndCompactSafeMarginAreCanonical() {
        assertEquals("Batman", SearchResultPolicy.normalizeQuery("  Batman  "))
        assertEquals(20, SearchResultPolicy.COMPACT_HORIZONTAL_SAFE_MARGIN_DP)
        assertEquals(40, SearchResultPolicy.compactSafeMarginPx(density = 2f))
    }
}
