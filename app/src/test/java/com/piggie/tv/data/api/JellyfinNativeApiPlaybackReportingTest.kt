package com.piggie.tv.data.api

import com.piggie.tv.data.models.NativeSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class JellyfinNativeApiPlaybackReportingTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `playing progress and stopped reports are delivered in submission order`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
        val session = NativeSession(
            token = "token",
            serverId = "server",
            userId = "user",
            userName = "Viewer",
            serverUrl = server.url("/").toString().trimEnd('/')
        )
        val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
        val stopped = CountDownLatch(1)
        var stoppedSucceeded = false

        api.reportPlaying(session, "movie", "play-1", 0L)
        val playingRequest = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        api.reportProgress(session, "movie", "play-1", 120_000L, false)
        val progressRequest = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        api.reportStopped(session, "movie", "play-1", 130_000L) { success ->
            stoppedSucceeded = success
            stopped.countDown()
        }

        assertTrue("Stopped report did not complete", stopped.await(5, TimeUnit.SECONDS))
        assertTrue(stoppedSucceeded)
        val requests = listOf(
            playingRequest,
            progressRequest,
            requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        )
        assertEquals(
            listOf(
                "/Sessions/Playing",
                "/Sessions/Playing/Progress",
                "/Sessions/Playing/Stopped"
            ),
            requests.map { it.requestUrl?.encodedPath }
        )
        assertEquals(
            listOf(0L, 120_000L, 130_000L),
            requests.map { JSONObject(it.body.readUtf8()).getLong("PositionTicks") }
        )
    }

    @Test
    fun `stopped report supersedes progress waiting behind a slow request`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("ok")
                .setBodyDelay(1, TimeUnit.SECONDS)
        )
        server.enqueue(MockResponse().setResponseCode(204))
        val session = NativeSession(
            token = "token",
            serverId = "server",
            userId = "user",
            userName = "Viewer",
            serverUrl = server.url("/").toString().trimEnd('/')
        )
        val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
        val completed = CountDownLatch(1)

        api.reportPlaying(session, "movie", "slow-play", 0L)
        val playing = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        api.reportProgress(session, "movie", "slow-play", 100_000L, false)
        api.reportProgress(session, "movie", "slow-play", 200_000L, false)
        api.reportStopped(session, "movie", "slow-play", 300_000L) {
            completed.countDown()
        }

        assertTrue(completed.await(5, TimeUnit.SECONDS))
        val stopped = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("/Sessions/Playing", playing.requestUrl?.encodedPath)
        assertEquals("/Sessions/Playing/Stopped", stopped.requestUrl?.encodedPath)
        assertEquals(2, server.requestCount)
        assertEquals(300_000L, JSONObject(stopped.body.readUtf8()).getLong("PositionTicks"))
    }

    @Test
    fun `stopped report exposes transport failure to retry policy`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error again"))
        val session = NativeSession(
            token = "token",
            serverId = "server",
            userId = "user",
            userName = "Viewer",
            serverUrl = server.url("/").toString().trimEnd('/')
        )
        val completed = CountDownLatch(1)
        var success = true

        JellyfinNativeApi(RuntimeEnvironment.getApplication()).reportStopped(
            session,
            "movie",
            "play-1",
            130_000L
        ) { result ->
            success = result
            completed.countDown()
        }

        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(false, success)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `failed old stop retries before a new session starts`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("retry"))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        val session = NativeSession(
            token = "token",
            serverId = "server",
            userId = "user",
            userName = "Viewer",
            serverUrl = server.url("/").toString().trimEnd('/')
        )
        val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
        val stopped = CountDownLatch(1)

        api.reportStopped(session, "old-movie", "old-play", 100L) {
            stopped.countDown()
        }
        api.reportPlaying(session, "new-movie", "new-play", 0L)

        assertTrue(stopped.await(5, TimeUnit.SECONDS))
        val requests = List(3) {
            requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        }
        assertEquals(
            listOf(
                "/Sessions/Playing/Stopped",
                "/Sessions/Playing/Stopped",
                "/Sessions/Playing"
            ),
            requests.map { it.requestUrl?.encodedPath }
        )
    }
}
