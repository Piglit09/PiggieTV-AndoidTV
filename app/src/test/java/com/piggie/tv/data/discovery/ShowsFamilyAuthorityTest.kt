package com.piggie.tv.data.discovery

import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ShowsFamilyAuthorityTest {
    @Test fun animeToCartoonsToAnimeRejectsTheLateFirstAnimeResult() {
        DiscoveryManager.clearForLogout()
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"old-anime","Name":"Anime","Type":"CollectionFolder"}]}"""))
                server.enqueue(MockResponse()
                    .setHeadersDelay(800, TimeUnit.MILLISECONDS)
                    .setBody("""{"Items":[{"Id":"stale-series","Name":"Stale","Type":"Series"}]}"""))
                server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"cartoons-root","Name":"Cartoons","Type":"CollectionFolder"}]}"""))
                server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"cartoon-series","Name":"Cartoon","Type":"Series"}]}"""))
                server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"new-anime","Name":"Anime","Type":"CollectionFolder"}]}"""))
                server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"current-series","Name":"Current","Type":"Series"}]}"""))
                val session = NativeSession(
                    "token", "server", "user", "User", server.url("/").toString().removeSuffix("/")
                )
                val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
                val discoverySession = DiscoveryManager.getSession(api, session)
                val anime = DiscoveryManager.manifest(DiscoveryPage.ANIME)
                val oldDelivered = Collections.synchronizedList(mutableListOf<DiscoveryShelf>())
                val oldRequest = DiscoveryManager.loadPage(api, session, anime, discoverySession) {
                    oldDelivered += it
                }
                try {
                    assertTrue(server.takeRequest(5, TimeUnit.SECONDS)?.path?.endsWith("/Views") == true)
                    assertEquals("old-anime", server.takeRequest(5, TimeUnit.SECONDS)
                        ?.requestUrl?.queryParameter("ParentId"))
                    oldRequest.cancel()

                    val cartoons = awaitShelf(api, session,
                        DiscoveryManager.manifest(DiscoveryPage.CARTOONS), discoverySession)
                    val currentAnime = awaitShelf(api, session, anime, discoverySession)

                    assertEquals(listOf("cartoon-series"), cartoons.items.map { it.id })
                    assertEquals(listOf("current-series"), currentAnime.items.map { it.id })
                    Thread.sleep(900)
                    assertTrue("retired Anime request delivered stale cards", oldDelivered.isEmpty())
                } finally {
                    oldRequest.cancel()
                }
            }
        } finally {
            DiscoveryManager.clearForLogout()
        }
    }

    @Test fun returningToAnimeResolvesTheNewRootInsteadOfReplayingOldScopedCards() {
        DiscoveryManager.clearForLogout()
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"old-root","Name":"Anime","Type":"CollectionFolder"}]}"""))
                server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"old-series","Name":"Old","Type":"Series"}]}"""))
                server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"new-root","Name":"Anime","Type":"CollectionFolder"}]}"""))
                server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"new-series","Name":"New","Type":"Series"}]}"""))
                val session = NativeSession(
                    "token", "server", "user", "User", server.url("/").toString().removeSuffix("/")
                )
                val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
                val discoverySession = DiscoveryManager.getSession(api, session)
                val manifest = DiscoveryManager.manifest(DiscoveryPage.ANIME)

                val first = awaitShelf(api, session, manifest, discoverySession)
                val second = awaitShelf(api, session, manifest, discoverySession)

                assertEquals(listOf("old-series"), first.items.map { it.id })
                assertEquals(listOf("new-series"), second.items.map { it.id })
                assertFalse(first.diagnostic.cacheHit)
                assertFalse(second.diagnostic.cacheHit)
                assertEquals(4, server.requestCount)
                server.takeRequest()
                assertEquals("old-root", server.takeRequest().requestUrl!!.queryParameter("ParentId"))
                server.takeRequest()
                assertEquals("new-root", server.takeRequest().requestUrl!!.queryParameter("ParentId"))
            }
        } finally {
            DiscoveryManager.clearForLogout()
        }
    }

    @Test fun ambiguousAnimeRootFailsClosedBeforeAnyItemsQuery() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Items":[
              {"Id":"one","Name":"Anime","Type":"CollectionFolder"},
              {"Id":"two","Name":"Anime","Type":"CollectionFolder"}
            ]}"""))
            val session = NativeSession(
                "token", "server", "user", "User", server.url("/").toString().removeSuffix("/")
            )
            val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
            var received: DiscoveryShelf? = null
            DiscoveryManager.loadShelf(
                api, session,
                DiscoveryManager.manifest(DiscoveryPage.ANIME).shelves.single(),
                DiscoveryManager.getSession(api, session),
                page = DiscoveryPage.ANIME,
                launchInBackground = false,
                onSuccess = { received = it },
                onError = { throw AssertionError("unexpected load failure", it) }
            )

            assertEquals(ShelfStatus.MISSING_LIBRARY, received?.status)
            assertTrue(received?.items?.isEmpty() == true)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun selectedAnimeShelfQueriesOnlyItsCurrentUserVisibleRootAndHasNoDeadViewMoreCard() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Items":[
              {"Id":"shows-root","Name":"Shows","Type":"CollectionFolder"},
              {"Id":"anime-root","Name":"Anime","Type":"CollectionFolder"}
            ]}"""))
            server.enqueue(MockResponse().setBody("""{"Items":[
              {"Id":"anime-series","Name":"Anime Series","Type":"Series"}
            ]}"""))
            val session = NativeSession(
                "token", "server", "user", "User", server.url("/").toString().removeSuffix("/")
            )
            val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
            var received: DiscoveryShelf? = null
            DiscoveryManager.loadShelf(
                api = api,
                nativeSession = session,
                definition = DiscoveryManager.manifest(DiscoveryPage.ANIME).shelves.single(),
                discoverySession = DiscoveryManager.getSession(api, session),
                page = DiscoveryPage.ANIME,
                launchInBackground = false,
                onSuccess = { received = it },
                onError = { throw AssertionError("unexpected load failure", it) }
            )

            val shelf = requireNotNull(received)
            assertEquals(ShelfStatus.READY, shelf.status)
            assertEquals(listOf("anime-series"), shelf.items.map { it.id })
            assertNull("a scoped page cannot show a nonfunctional View More target", shelf.viewMoreItem)
            val views = server.takeRequest()
            val items = server.takeRequest()
            assertTrue(views.path!!.startsWith("/Users/user/Views"))
            assertEquals("anime-root", items.requestUrl!!.queryParameter("ParentId"))
            assertEquals("Series", items.requestUrl!!.queryParameter("IncludeItemTypes"))
            assertFalse(items.path!!.contains("SearchTerm"))
        }
    }

    @Test fun showsFamilyManifestsNeverContainGlobalOrOtherLibraryShelves() {
        val expected = mapOf("SHOWS" to "Shows", "ANIME" to "Anime", "CARTOONS" to "Cartoons")
        expected.forEach { (route, library) ->
            val shelves = DiscoveryManager.getPageDefinition(route).shelves
            assertTrue("$route needs an actual scoped shelf", shelves.isNotEmpty())
            assertTrue("$route contains an unscoped or foreign shelf", shelves.all {
                it.type == DiscoveryShelfType.LIBRARY_SPECIFIC &&
                    it.libraryName == library &&
                    it.itemTypes == listOf("Series")
            })
        }
    }

    @Test fun duplicateSameNameUserViewsNeverChooseTheFirstRoot() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Items":[
              {"Id":"anime-old","Name":"Anime","Type":"CollectionFolder"},
              {"Id":"anime-new","Name":"Anime","Type":"CollectionFolder"}
            ]}"""))
            val session = NativeSession(
                "token", "server", "user", "User", server.url("/").toString().removeSuffix("/")
            )
            val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())

            assertNull(api.findLibraryId(session, "Anime"))
            assertEquals(1, server.requestCount)
        }
    }

    private fun awaitShelf(
        api: JellyfinNativeApi,
        session: NativeSession,
        manifest: PageManifest,
        discoverySession: DiscoverySession
    ): DiscoveryShelf {
        val latch = CountDownLatch(1)
        var value: DiscoveryShelf? = null
        val request = DiscoveryManager.loadPage(api, session, manifest, discoverySession) {
            value = it
            latch.countDown()
        }
        try {
            assertTrue("scoped library load timed out", latch.await(5, TimeUnit.SECONDS))
            return requireNotNull(value)
        } finally {
            request.cancel()
        }
    }
}
