package com.piggie.tv.data.discovery

import com.piggie.tv.data.api.DebugDiscoveryFaultInjector
import com.piggie.tv.data.api.DiscoveryFaultMode
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.NativeSession
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DiscoveryRouteCancellationIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var api: JellyfinNativeApi
    private lateinit var nativeSession: NativeSession
    private lateinit var discoverySession: DiscoverySession

    @Before
    fun setUp() {
        DiscoveryManager.clearForLogout()
        DebugDiscoveryFaultInjector.configure(null, DiscoveryFaultMode.NORMAL)
        server = MockWebServer()
        server.start()
        api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
        nativeSession = NativeSession(
            token = "token",
            serverId = "server",
            userId = "user",
            userName = "User",
            serverUrl = server.url("/").toString().removeSuffix("/")
        )
        discoverySession = DiscoveryManager.getSession(api, nativeSession)
    }

    @After
    fun tearDown() {
        DebugDiscoveryFaultInjector.configure(null, DiscoveryFaultMode.NORMAL)
        DiscoveryManager.clearForLogout()
        server.shutdown()
    }

    @Test
    fun hiddenRouteCancellationCancelsItsInFlightCall() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val delivered = Collections.synchronizedList(mutableListOf<DiscoveryShelf>())
        val request = load(DiscoveryPage.HOME, delivered::add)
        val generation = DiscoveryManager.currentGenerationId(DiscoveryPage.HOME)
        assertNotNull("the shelf request never reached the server", server.takeRequest(5, TimeUnit.SECONDS))

        request.cancel()

        assertTrue("the canceled shelf worker did not finish", awaitNoActiveShelfLoads())
        assertTrue("a hidden route must not receive a shelf result", delivered.isEmpty())
        val terminal = diagnostic(DiscoveryPage.HOME, generation)
        assertEquals(ShelfStatus.CANCELED_HIDDEN_ROUTE, terminal.status)
        assertEquals(DiscoveryFinalState.CANCELED_HIDDEN_ROUTE, terminal.finalState)
        assertEquals("hidden-route", terminal.failure)
        assertTrue("the diagnostic must distinguish in-flight cancellation", terminal.networkRequestStarted)
    }

    @Test
    fun canceledResultIsIgnoredEvenWhenTheServerHadAcceptedTheRequest() {
        server.enqueue(
            MockResponse()
                .setHeadersDelay(500, TimeUnit.MILLISECONDS)
                .setResponseCode(200)
                .setBody(itemsBody("stale"))
        )
        val callback = CountDownLatch(1)
        val delivered = Collections.synchronizedList(mutableListOf<DiscoveryShelf>())
        val request = load(DiscoveryPage.HOME) {
            delivered += it
            callback.countDown()
        }
        assertNotNull("the shelf request never reached the server", server.takeRequest(5, TimeUnit.SECONDS))

        request.cancel()

        assertFalse("canceled work must not invoke the shelf callback", callback.await(800, TimeUnit.MILLISECONDS))
        assertTrue(delivered.isEmpty())
        assertTrue("the canceled shelf worker did not finish", awaitNoActiveShelfLoads())
    }

    @Test
    fun returningToRouteStartsFreshCurrentGeneration() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setResponseCode(200).setBody(itemsBody("current")))
        val firstDelivered = Collections.synchronizedList(mutableListOf<DiscoveryShelf>())
        val firstRequest = load(DiscoveryPage.HOME, firstDelivered::add)
        val firstGeneration = DiscoveryManager.currentGenerationId(DiscoveryPage.HOME)
        assertNotNull("the first route request never reached the server", server.takeRequest(5, TimeUnit.SECONDS))
        firstRequest.cancel()
        assertTrue("the first route request did not stop", awaitNoActiveShelfLoads())

        val restarted = awaitShelf(DiscoveryPage.HOME)
        val restartedGeneration = restarted.diagnostic.generationId

        assertTrue("route restart must allocate a newer generation", restartedGeneration > firstGeneration)
        assertEquals(restartedGeneration, DiscoveryManager.currentGenerationId(DiscoveryPage.HOME))
        assertEquals(ShelfStatus.READY, restarted.status)
        assertEquals(listOf("current"), restarted.items.map { it.id })
        assertTrue("the canceled generation must remain silent", firstDelivered.isEmpty())
        assertEquals("restart should make exactly one replacement request", 2, server.requestCount)
    }

    @Test
    fun newerGenerationReplacesPriorGenerationForResultApplication() {
        server.enqueue(
            MockResponse()
                .setHeadersDelay(600, TimeUnit.MILLISECONDS)
                .setResponseCode(200)
                .setBody(itemsBody("old"))
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(itemsBody("new")))
        val applied = Collections.synchronizedList(mutableListOf<DiscoveryShelf>())
        val oldDelivered = CountDownLatch(1)
        val newDelivered = CountDownLatch(1)
        val oldRequest = load(DiscoveryPage.HOME) { shelf ->
            applyIfCurrent(shelf, applied)
            oldDelivered.countDown()
        }
        val oldGeneration = DiscoveryManager.currentGenerationId(DiscoveryPage.HOME)
        assertNotNull("the old generation never reached the server", server.takeRequest(5, TimeUnit.SECONDS))

        val newRequest = load(DiscoveryPage.HOME) { shelf ->
            applyIfCurrent(shelf, applied)
            newDelivered.countDown()
        }
        val newGeneration = DiscoveryManager.currentGenerationId(DiscoveryPage.HOME)

        try {
            assertTrue("the replacement generation did not complete", newDelivered.await(5, TimeUnit.SECONDS))
            assertTrue("the deliberately late generation did not complete", oldDelivered.await(5, TimeUnit.SECONDS))
            assertTrue(newGeneration > oldGeneration)
            assertEquals(
                "only the current generation may reach the route adapter",
                listOf("new"),
                synchronized(applied) { applied.flatMap { it.items }.map { it.id } }
            )
        } finally {
            oldRequest.cancel()
            newRequest.cancel()
        }
    }

    @Test
    fun expectedCancellationNeverBecomesAnErrorState() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val delivered = Collections.synchronizedList(mutableListOf<DiscoveryShelf>())
        val request = load(DiscoveryPage.HOME, delivered::add)
        val generation = DiscoveryManager.currentGenerationId(DiscoveryPage.HOME)
        assertNotNull("the shelf request never reached the server", server.takeRequest(5, TimeUnit.SECONDS))

        request.cancel()

        assertTrue("the canceled shelf worker did not finish", awaitNoActiveShelfLoads())
        assertTrue(delivered.none { it.status in ERROR_STATES })
        val samples = DiscoveryManager.recentDiagnostics().filter {
            it.page == DiscoveryPage.HOME &&
                it.generationId == generation &&
                it.shelfId == HOME_SHELF_ID
        }
        assertTrue(samples.isNotEmpty())
        assertTrue("expected cancellation must not be classified as an error", samples.none { it.status in ERROR_STATES })
        assertEquals(ShelfStatus.CANCELED_HIDDEN_ROUTE, samples.last().status)
    }

    private fun load(
        page: DiscoveryPage,
        onShelf: (DiscoveryShelf) -> Unit
    ): DiscoveryPageRequest = DiscoveryManager.loadPage(
        api = api,
        nativeSession = nativeSession,
        manifest = manifest(page),
        discoverySession = discoverySession,
        onShelf = onShelf
    )

    private fun awaitShelf(page: DiscoveryPage): DiscoveryShelf {
        val latch = CountDownLatch(1)
        var delivered: DiscoveryShelf? = null
        val request = load(page) {
            delivered = it
            latch.countDown()
        }
        try {
            assertTrue("the restarted route did not deliver a shelf", latch.await(5, TimeUnit.SECONDS))
            return requireNotNull(delivered)
        } finally {
            request.cancel()
        }
    }

    private fun applyIfCurrent(
        shelf: DiscoveryShelf,
        applied: MutableList<DiscoveryShelf>
    ) {
        if (shelf.diagnostic.generationId == DiscoveryManager.currentGenerationId(shelf.diagnostic.page)) {
            applied += shelf
        }
    }

    private fun diagnostic(page: DiscoveryPage, generation: Long): ShelfDiagnostic =
        DiscoveryManager.recentDiagnostics().last {
            it.page == page && it.generationId == generation && it.shelfId == HOME_SHELF_ID
        }

    private fun manifest(page: DiscoveryPage): PageManifest {
        require(page == DiscoveryPage.HOME) { "This fixture currently covers the Home route" }
        val definition = ShelfDefinition(
            id = HOME_SHELF_ID,
            type = DiscoveryShelfType.RECENTLY_ADDED,
            title = "Cancellation proof",
            presentation = MediaCardPresentation.POSTER,
            itemTypes = listOf("Movie"),
            filter = DiscoveryFilter(DiscoveryFilterType.RECENTLY_ADDED)
        )
        return PageManifest(page, 1, listOf(definition))
    }

    private fun awaitNoActiveShelfLoads(timeoutMs: Long = 5_000): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (DiscoveryManager.activeShelfLoadCount() == 0) return true
            Thread.yield()
        }
        return DiscoveryManager.activeShelfLoadCount() == 0
    }

    private fun itemsBody(id: String) =
        """{"Items":[{"Id":"$id","Name":"Movie $id","Type":"Movie"}]}"""

    private companion object {
        const val HOME_SHELF_ID = "home.added"
        val ERROR_STATES = setOf(
            ShelfStatus.TIMEOUT,
            ShelfStatus.HTTP_ERROR,
            ShelfStatus.INVALID_QUERY,
            ShelfStatus.MISSING_LIBRARY,
            ShelfStatus.FAILED_RENDER,
            ShelfStatus.RENDER_ERROR
        )
    }
}
