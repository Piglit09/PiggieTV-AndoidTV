package com.piggie.tv.data.discovery

import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MoviesBrowseAuthorityTest {
    @Test fun moviesRouteContainsOnlyActualMoviesLibraryQueries() {
        val shelves = DiscoveryManager.manifest(DiscoveryPage.MOVIES).shelves

        assertTrue(shelves.isNotEmpty())
        assertTrue(shelves.all {
            it.type == DiscoveryShelfType.LIBRARY_SPECIFIC &&
                it.libraryName == "Movies" &&
                it.itemTypes == listOf("Movie")
        })
    }

    @Test fun moviesContinueWatchingKeepsItsActionInsideTheMoviesRoot() {
        val definition = requireNotNull(DiscoveryManager.manifest(DiscoveryPage.MOVIES)
            .shelves.singleOrNull { it.id == "movies.continue" })
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Items":[
              {"Id":"movies-root","Name":"Movies","Type":"CollectionFolder"}
            ]}"""))
            server.enqueue(MockResponse().setBody("""{"Items":[
              {"Id":"movie-resume","Name":"Resume Movie","Type":"Movie"}
            ]}"""))
            val session = NativeSession(
                "token", "server", "user", "User", server.url("/").toString().removeSuffix("/")
            )
            val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
            var received: DiscoveryShelf? = null
            DiscoveryManager.loadShelf(
                api, session, definition, DiscoveryManager.getSession(api, session),
                page = DiscoveryPage.MOVIES, launchInBackground = false,
                onSuccess = { received = it },
                onError = { throw AssertionError("unexpected shelf failure", it) }
            )

            assertEquals(ShelfStatus.READY, received?.status)
            assertEquals(listOf("movie-resume"), received?.items?.map { it.id })
            server.takeRequest()
            val items = server.takeRequest().requestUrl!!
            assertEquals("movies-root", items.queryParameter("ParentId"))
            assertEquals("IsResumable", items.queryParameter("Filters"))
            assertEquals("Movie", items.queryParameter("IncludeItemTypes"))
        }
    }

    @Test fun everyLibraryHeaderOpensTheCorrespondingScopedBrowser() {
        val expected = mapOf(
            DiscoveryPage.MOVIES to ("Movies" to "Movie"),
            DiscoveryPage.SHOWS to ("Shows" to "Series"),
            DiscoveryPage.ANIME to ("Anime" to "Series"),
            DiscoveryPage.CARTOONS to ("Cartoons" to "Series")
        )
        expected.forEach { (page, expectedScope) ->
            val request = requireNotNull(DiscoveryLibraryRoutePolicy.browseRequest(page))
            assertEquals(expectedScope.first, request.libraryName)
            assertEquals(expectedScope.first, request.filter.value)
            assertEquals(listOf(expectedScope.second), request.itemTypes)
        }
        assertEquals(null, DiscoveryLibraryRoutePolicy.browseRequest(DiscoveryPage.HOME))
    }

    @Test fun fullMoviesBrowserResolvesCurrentUserVisibleRootBeforeItemsQuery() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Items":[
              {"Id":"movies-root","Name":"Movies","Type":"CollectionFolder"},
              {"Id":"shows-root","Name":"Shows","Type":"CollectionFolder"}
            ]}"""))
            server.enqueue(MockResponse().setBody("""{"Items":[
              {"Id":"movie-one","Name":"Movie One","Type":"Movie"}
            ]}"""))
            val result = awaitBrowser(server, DiscoveryBrowseRequest(
                title = "Movies",
                filter = DiscoveryFilter(DiscoveryFilterType.LIBRARY, "Movies"),
                itemTypes = listOf("Movie")
            ))

            assertEquals(ShelfStatus.READY, result.status)
            assertEquals(listOf("movie-one"), result.items.map { it.id })
            val views = server.takeRequest()
            val items = server.takeRequest()
            assertTrue(views.path!!.startsWith("/Users/user/Views"))
            assertEquals("movies-root", items.requestUrl!!.queryParameter("ParentId"))
            assertEquals("Movie", items.requestUrl!!.queryParameter("IncludeItemTypes"))
        }
    }

    @Test fun ambiguousMoviesRootNeverTriggersGlobalItemsQuery() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Items":[
              {"Id":"old-root","Name":"Movies","Type":"CollectionFolder"},
              {"Id":"new-root","Name":"Movies","Type":"CollectionFolder"}
            ]}"""))
            val result = awaitBrowser(server, DiscoveryBrowseRequest(
                title = "Movies",
                filter = DiscoveryFilter(DiscoveryFilterType.LIBRARY, "Movies"),
                itemTypes = listOf("Movie")
            ))

            assertEquals(ShelfStatus.MISSING_LIBRARY, result.status)
            assertTrue(result.items.isEmpty())
            assertEquals(1, server.requestCount)
            assertFalse(server.takeRequest().path!!.contains("/Items"))
        }
    }

    @Test fun sortingAndWatchedFilterStayInsideTheSelectedMoviesRoot() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Items":[
              {"Id":"movies-root","Name":"Movies","Type":"CollectionFolder"}
            ]}"""))
            server.enqueue(MockResponse().setBody("""{"Items":[
              {"Id":"movie-one","Name":"Movie One","Type":"Movie"}
            ]}"""))
            val result = awaitBrowser(server, DiscoveryBrowseRequest(
                title = "Movies",
                filter = DiscoveryFilter(DiscoveryFilterType.LIBRARY, "Movies"),
                itemTypes = listOf("Movie"),
                sort = DiscoveryBrowseSort.TITLE,
                watchFilter = DiscoveryBrowseWatchFilter.UNPLAYED
            ))

            assertEquals(ShelfStatus.READY, result.status)
            server.takeRequest()
            val items = server.takeRequest().requestUrl!!
            assertEquals("movies-root", items.queryParameter("ParentId"))
            assertEquals("SortName", items.queryParameter("SortBy"))
            assertEquals("Ascending", items.queryParameter("SortOrder"))
            assertEquals("IsUnplayed", items.queryParameter("Filters"))
        }
    }

    @Test fun aNamedGenreFilterCannotLoseItsLibraryRoot() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Items":[
              {"Id":"anime-root","Name":"Anime","Type":"CollectionFolder"}
            ]}"""))
            server.enqueue(MockResponse().setBody("""{"Items":[
              {"Id":"anime-series","Name":"Anime Series","Type":"Series"}
            ]}"""))
            val result = awaitBrowser(server, DiscoveryBrowseRequest(
                title = "Action Anime",
                filter = DiscoveryFilter(DiscoveryFilterType.GENRE, "Action"),
                itemTypes = listOf("Series"),
                libraryName = "Anime"
            ))

            assertEquals(ShelfStatus.READY, result.status)
            assertEquals(2, server.requestCount)
            server.takeRequest()
            val items = server.takeRequest().requestUrl!!
            assertEquals("anime-root", items.queryParameter("ParentId"))
            assertEquals("Action", items.queryParameter("Genres"))
            assertEquals("Series", items.queryParameter("IncludeItemTypes"))
        }
    }

    private fun awaitBrowser(
        server: MockWebServer,
        request: DiscoveryBrowseRequest
    ): DiscoveryBrowserResult {
        val session = NativeSession(
            "token", "server", "user", "User", server.url("/").toString().removeSuffix("/")
        )
        val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
        val latch = CountDownLatch(1)
        var value: DiscoveryBrowserResult? = null
        val handle = DiscoveryManager.loadBrowser(api, session, request) {
            value = it
            latch.countDown()
        }
        try {
            assertTrue("browser load timed out", latch.await(5, TimeUnit.SECONDS))
            return requireNotNull(value)
        } finally {
            handle.cancel()
        }
    }
}
