package com.piggie.tv.data.discovery

import com.piggie.tv.data.api.DebugDiscoveryFaultInjector
import com.piggie.tv.data.api.DiscoveryFaultMode
import com.piggie.tv.data.api.DiscoveryEndpointCategory
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.NativeSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DiscoveryManagerCacheIntegrationTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        DiscoveryManager.clearForLogout()
        DebugDiscoveryFaultInjector.configure(null, DiscoveryFaultMode.NORMAL)
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        DebugDiscoveryFaultInjector.configure(null, DiscoveryFaultMode.NORMAL)
        DiscoveryManager.clearForLogout()
        server.shutdown()
    }

    @Test fun routeReturnUsesRealCachedShelfAndAvoidsSecondHttpRequest() {
        val body = """{"Items":[{"Id":"movie-1","Name":"Movie","Type":"Movie","ImageTags":{"Primary":"tag"}}]}"""
        server.enqueue(MockResponse().setBody(body).setResponseCode(200))
        server.enqueue(MockResponse().setBody(body).setResponseCode(200))
        val context = RuntimeEnvironment.getApplication()
        val api = JellyfinNativeApi(context)
        val nativeSession = NativeSession("token", "server", "user", "User", server.url("/").toString().removeSuffix("/"))
        val discoverySession = DiscoveryManager.getSession(api, nativeSession)
        val definition = ShelfDefinition(
            "home.added",
            DiscoveryShelfType.RECENTLY_ADDED,
            "Cache Proof",
            MediaCardPresentation.POSTER,
            listOf("Movie"),
            filter = DiscoveryFilter(DiscoveryFilterType.RECENTLY_ADDED)
        )
        val manifest = PageManifest(DiscoveryPage.HOME, 1, listOf(definition))

        val first = awaitShelf { callback ->
            DiscoveryManager.loadPage(api, nativeSession, manifest, discoverySession, callback)
        }
        assertFalse(first.diagnostic.cacheHit)
        assertTrue((first.diagnostic.cacheEntryBytes ?: 0L) > 0L)
        assertEquals(1, server.requestCount)

        val returned = awaitShelf { callback ->
            DiscoveryManager.loadPage(api, nativeSession, manifest, discoverySession, callback)
        }
        assertTrue(returned.diagnostic.cacheHit)
        assertTrue(returned.diagnostic.requestAvoided)
        assertFalse(returned.diagnostic.networkRequestStarted)
        assertEquals(1, server.requestCount)

        DebugDiscoveryFaultInjector.configure(
            DiscoveryEndpointCategory.USER_ITEMS,
            DiscoveryFaultMode.HTTP_502,
            definition.id,
            bypassFreshCache = true
        )
        val refreshLatch = CountDownLatch(2)
        val refreshed = mutableListOf<DiscoveryShelf>()
        val refreshRequest = DiscoveryManager.loadPage(api, nativeSession, manifest, discoverySession) { shelf ->
            synchronized(refreshed) { refreshed += shelf }
            DiscoveryManager.recordAdapterState(shelf, 0)
            refreshLatch.countDown()
        }
        assertTrue("cached content and refresh failure did not both arrive", refreshLatch.await(5, TimeUnit.SECONDS))
        refreshRequest.cancel()
        val refreshValues = synchronized(refreshed) { refreshed.toList() }
        assertTrue(refreshValues.first().diagnostic.cacheHit)
        assertTrue(refreshValues.first().diagnostic.cacheRefreshBypassed)
        assertEquals(ShelfStatus.HTTP_ERROR, refreshValues.last().status)
        assertEquals(1, refreshValues.last().items.size)
        assertEquals(1, DiscoveryManager.currentAggregate(DiscoveryPage.HOME).failed)
        assertEquals(1, DiscoveryManager.currentAggregate(DiscoveryPage.HOME).renderedCards)
        assertEquals(1, server.requestCount)

        DebugDiscoveryFaultInjector.configure(null, DiscoveryFaultMode.NORMAL)
        DiscoveryManager.clearForLogout()
        val newScope = DiscoveryManager.getSession(api, nativeSession)
        val afterClear = awaitShelf { callback ->
            DiscoveryManager.loadPage(api, nativeSession, manifest, newScope, callback)
        }
        assertFalse(afterClear.diagnostic.cacheHit)
        assertEquals(2, server.requestCount)
    }

    @Test fun timeoutThenRetryLoadsContentAndReplacesTheFailedAggregateAttempt() {
        val body = """{"Items":[{"Id":"movie-1","Name":"Movie","Type":"Movie","ImageTags":{"Primary":"tag"}}]}"""
        server.enqueue(MockResponse().setBody(body).setResponseCode(200))
        val context = RuntimeEnvironment.getApplication()
        val api = JellyfinNativeApi(context)
        val nativeSession = NativeSession("token", "server", "user", "User", server.url("/").toString().removeSuffix("/"))
        val discoverySession = DiscoveryManager.getSession(api, nativeSession)
        val definition = ShelfDefinition(
            "home.added",
            DiscoveryShelfType.RECENTLY_ADDED,
            "Retry Proof",
            MediaCardPresentation.POSTER,
            listOf("Movie"),
            filter = DiscoveryFilter(DiscoveryFilterType.RECENTLY_ADDED)
        )
        val manifest = PageManifest(DiscoveryPage.HOME, 1, listOf(definition))
        DebugDiscoveryFaultInjector.configure(
            DiscoveryEndpointCategory.USER_ITEMS,
            DiscoveryFaultMode.SUCCESS_AFTER_MANUAL_RETRY,
            definition.id
        )
        val failure = CountDownLatch(1)
        val content = CountDownLatch(1)
        val values = java.util.Collections.synchronizedList(mutableListOf<DiscoveryShelf>())

        val request = DiscoveryManager.loadPage(api, nativeSession, manifest, discoverySession) { shelf ->
            values += shelf
            DiscoveryManager.recordAdapterState(shelf, 0)
            when (shelf.status) {
                ShelfStatus.TIMEOUT -> failure.countDown()
                ShelfStatus.READY -> content.countDown()
                else -> Unit
            }
        }
        try {
            assertTrue("injected timeout did not complete", failure.await(5, TimeUnit.SECONDS))
            val timedOut = DiscoveryManager.currentDiagnostics(DiscoveryPage.HOME).single()
            val failedAggregate = DiscoveryManager.currentAggregate(DiscoveryPage.HOME)
            assertEquals(DiscoveryFinalState.TIMEOUT, timedOut.finalState)
            assertEquals(0, timedOut.attemptId)
            assertEquals(1, failedAggregate.failed)
            assertEquals(0, failedAggregate.content)
            assertEquals(0, server.requestCount)

            request.retryShelf(definition.id)

            assertTrue("manual retry did not load content", content.await(5, TimeUnit.SECONDS))
            val latest = DiscoveryManager.currentDiagnostics(DiscoveryPage.HOME).single()
            val recoveredAggregate = DiscoveryManager.currentAggregate(DiscoveryPage.HOME)
            assertEquals(ShelfStatus.READY, latest.status)
            assertEquals(DiscoveryFinalState.CONTENT, latest.finalState)
            assertEquals(1, latest.attemptId)
            assertEquals(1, recoveredAggregate.content)
            assertEquals(0, recoveredAggregate.failed)
            assertEquals(1, recoveredAggregate.requested)
            assertEquals(1, recoveredAggregate.renderedCards)
            assertEquals(1, server.requestCount)
            assertEquals(2, DebugDiscoveryFaultInjector.snapshot().attempts)
            assertEquals(1, DebugDiscoveryFaultInjector.snapshot().applied)
            assertEquals(listOf(ShelfStatus.TIMEOUT, ShelfStatus.READY), synchronized(values) { values.map(DiscoveryShelf::status) })
        } finally {
            request.cancel()
        }
    }

    @Test fun randomGenreRerollsPastIneligibleRawResultsAndStopsAtEligibleGenre() {
        val wrongType = """{"Items":[{"Id":"series-1","Name":"Series","Type":"Series"}]}"""
        val eligible = """{"Items":[{"Id":"movie-1","Name":"Movie","Type":"Movie"}]}"""
        server.enqueue(MockResponse().setBody("""{"Items":[]}""").setResponseCode(200))
        server.enqueue(MockResponse().setBody(wrongType).setResponseCode(200))
        server.enqueue(MockResponse().setBody(eligible).setResponseCode(200))
        val context = RuntimeEnvironment.getApplication()
        val api = JellyfinNativeApi(context)
        val nativeSession = NativeSession("token", "server", "user", "User", server.url("/").toString().removeSuffix("/"))
        val discoverySession = DiscoveryManager.getSession(api, nativeSession)
        val definition = ShelfDefinition(
            "home.genre.1",
            DiscoveryShelfType.RANDOM_GENRE,
            "Horror",
            MediaCardPresentation.POSTER,
            listOf("Movie"),
            filter = DiscoveryFilter(DiscoveryFilterType.RANDOM_GENRE, "Horror")
        )
        var value: DiscoveryShelf? = null

        DiscoveryManager.loadShelf(
            api = api,
            nativeSession = nativeSession,
            definition = definition,
            discoverySession = discoverySession,
            launchInBackground = false,
            onSuccess = { value = it },
            onError = { throw AssertionError("unexpected random-genre error", it) }
        )

        val shelf = requireNotNull(value)
        assertEquals(ShelfStatus.READY, shelf.status)
        assertEquals("Science Fiction", shelf.definition.title)
        assertEquals("Science Fiction", shelf.definition.filter.value)
        assertEquals("Science Fiction", shelf.diagnostic.query?.filters?.get("Genres"))
        assertEquals(listOf("movie-1"), shelf.items.map { it.id })
        assertEquals("Science Fiction", discoverySession.genreByShelf[definition.id])
        assertEquals(3, server.requestCount)
        assertEquals(
            listOf("Horror", "Comedy", "Science Fiction"),
            (1..3).map { server.takeRequest(1, TimeUnit.SECONDS)?.requestUrl?.queryParameter("Genres") }
        )
    }

    private fun awaitShelf(start: ((DiscoveryShelf) -> Unit) -> DiscoveryPageRequest): DiscoveryShelf {
        val latch = CountDownLatch(1)
        var value: DiscoveryShelf? = null
        val request = start {
            value = it
            latch.countDown()
        }
        assertTrue("shelf did not complete", latch.await(5, TimeUnit.SECONDS))
        request.cancel()
        return requireNotNull(value)
    }
}
