package com.piggie.tv.data.api

import com.piggie.tv.data.models.NativeSession
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class JellyfinNativeApiSearchTest {
    private lateinit var server: MockWebServer
    private lateinit var api: JellyfinNativeApi
    private lateinit var session: NativeSession

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
        session = NativeSession(
            token = "token",
            serverId = "server",
            userId = "user",
            userName = "Piggie",
            serverUrl = server.url("/").toString().trimEnd('/')
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun allSearchConsolidatesTaxonomyHintsBehindPrimaryMedia() {
        server.dispatcher = taxonomyDispatcher()

        val results = api.search(session, " rock ")

        assertEquals(
            listOf("Movie", "Genre", "MusicGenre", "Studio"),
            results.map { it.type }
        )
        assertEquals(listOf("movie", "genre", "music-genre", "studio"), results.map { it.id })

        val requests = takeRequests(2).associateBy { it.requestUrl?.encodedPath }
        assertEquals(
            setOf("/Users/user/Items", "/Search/Hints"),
            requests.keys
        )
        val mediaUrl = requireNotNull(requests["/Users/user/Items"]?.requestUrl)
        assertEquals("rock", mediaUrl.queryParameter("SearchTerm"))
        assertEquals(
            "Movie,Series,MusicArtist,MusicAlbum,Audio,Person",
            mediaUrl.queryParameter("IncludeItemTypes")
        )
        val taxonomyUrl = requireNotNull(requests["/Search/Hints"]?.requestUrl)
        assertEquals("rock", taxonomyUrl.queryParameter("SearchTerm"))
        assertEquals("user", taxonomyUrl.queryParameter("UserId"))
        assertEquals(
            "Genre,MusicGenre,Studio",
            taxonomyUrl.queryParameter("IncludeItemTypes")
        )
        assertEquals("false", taxonomyUrl.queryParameter("IncludeMedia"))
    }

    @Test
    fun genreAndStudioScopesOnlyRequestTheirCanonicalBranches() {
        server.dispatcher = taxonomyDispatcher()

        val genres = api.search(
            session = session,
            query = "rock",
            itemTypes = emptyList(),
            includeGenres = true,
            includeStudios = false
        )

        assertEquals(listOf("Genre", "MusicGenre"), genres.map { it.type })
        assertEquals(
            "/Search/Hints",
            requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).requestUrl?.encodedPath
        )

        val studios = api.search(
            session = session,
            query = "a24",
            itemTypes = emptyList(),
            includeGenres = false,
            includeStudios = true
        )

        assertEquals(listOf("Studio"), studios.map { it.type })
        assertEquals(
            "/Search/Hints",
            requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).requestUrl?.encodedPath
        )
    }

    @Test
    fun failedTaxonomyBranchesDoNotDiscardSuccessfulMediaSearch() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl?.encodedPath == "/Users/user/Items") {
                    jsonResponse(itemsJson("movie", "Movie", "Rocky"))
                } else {
                    MockResponse().setResponseCode(503).setBody("unavailable")
                }
        }

        val results = api.search(session, "rock")

        assertEquals(listOf("movie"), results.map { it.id })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun slowBranchCannotDelayOrDiscardCompletedResults() {
        warmSearchTransport()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (
                request.requestUrl?.encodedPath
            ) {
                "/Users/user/Items" -> jsonResponse(itemsJson("movie", "Movie", "Rocky"))
                "/Search/Hints" -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val snapshots = mutableListOf<List<String>>()
        val startedAt = System.nanoTime()

        val results = api.searchWithinBudget(
            session = session,
            query = "rock",
            resultBudgetMs = 400L,
            onPartialResults = { partial -> snapshots.add(partial.map { it.id }) }
        )
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertEquals(listOf("movie"), results.map { it.id })
        assertEquals(results.map { it.id }, snapshots.last())
        assertEquals(3, server.requestCount)
        assertTrue("Search exceeded its result budget: ${elapsedMs}ms", elapsedMs < 2_000L)
    }

    @Test
    fun budgetTimeoutWithoutACompletedBranchIsNotReportedAsNoResults() {
        warmSearchTransport()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }

        val failure = assertThrows(SocketTimeoutException::class.java) {
            api.searchWithinBudget(
                session = session,
                query = "rock",
                resultBudgetMs = 250L
            )
        }

        assertTrue(failure.message.orEmpty().contains("timed out"))
    }

    @Test
    fun allSearchSerializesServerBranchesToProtectThePrimaryResult() {
        warmSearchTransport()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val concurrent = active.incrementAndGet()
                peak.updateAndGet { current -> maxOf(current, concurrent) }
                return try {
                    Thread.sleep(150L)
                    when (request.requestUrl?.encodedPath) {
                        "/Users/user/Items" -> jsonResponse(itemsJson("movie", "Movie", "Rocky"))
                        "/Search/Hints" -> jsonResponse(taxonomyHintsJson())
                        else -> MockResponse().setResponseCode(404)
                    }
                } finally {
                    active.decrementAndGet()
                }
            }
        }

        val results = api.searchWithinBudget(
            session = session,
            query = "rock",
            resultBudgetMs = 2_000L
        )

        assertEquals(4, results.size)
        assertEquals(1, peak.get())
    }

    @Test
    fun repeatedCategoryScopesReuseTheBriefConsolidatedCache() {
        server.dispatcher = taxonomyDispatcher()

        val genres = api.search(
            session = session,
            query = "rock",
            itemTypes = emptyList(),
            includeGenres = true,
            includeStudios = false
        )
        val studios = api.search(
            session = session,
            query = " ROCK ",
            itemTypes = emptyList(),
            includeGenres = false,
            includeStudios = true
        )

        assertEquals(listOf("Genre", "MusicGenre"), genres.map { it.type })
        assertEquals(listOf("Studio"), studios.map { it.type })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun searchFailsWhenEveryRequestedBranchFails() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(503).setBody("unavailable")
        }

        val failure = assertThrows(Throwable::class.java) {
            api.search(
                session = session,
                query = "a24",
                itemTypes = emptyList(),
                includeGenres = false,
                includeStudios = true
            )
        }

        assertTrue(failure is HttpRequestFailure)
    }

    private fun taxonomyDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (
            request.requestUrl?.encodedPath
        ) {
            "/Users/user/Items" -> jsonResponse(itemsJson("movie", "Movie", "Rocky"))
            "/Search/Hints" -> jsonResponse(taxonomyHintsJson())
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun takeRequests(count: Int): List<RecordedRequest> = List(count) {
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    }

    private fun warmSearchTransport() {
        server.enqueue(jsonResponse(itemsJson("warm", "Movie", "Warm up")))
        api.searchWithinBudget(
            session = session,
            query = "warm",
            itemTypes = listOf("Movie"),
            includeGenres = false,
            includeStudios = false,
            resultBudgetMs = 10_000L
        )
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun itemsJson(id: String, type: String, name: String): String =
        """{"Items":[{"Id":"$id","Type":"$type","Name":"$name"}]}"""

    private fun taxonomyHintsJson(): String =
        """{"SearchHints":[
            {"Id":"genre","Type":"Genre","Name":"Rock","PrimaryImageTag":"genre-tag"},
            {"Id":"music-genre","Type":"MusicGenre","Name":"Rock"},
            {"Id":"studio","Type":"Studio","Name":"A24"}
        ]}""".trimIndent()
}
