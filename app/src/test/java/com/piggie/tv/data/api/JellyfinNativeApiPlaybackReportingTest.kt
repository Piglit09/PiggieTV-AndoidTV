package com.piggie.tv.data.api

import com.piggie.tv.auth.AuthSessionCoordinator
import com.piggie.tv.data.models.NativeSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        AuthSessionCoordinator.resetForTests()
        cancelPendingPlaybackReports()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        AuthSessionCoordinator.resetForTests()
        cancelPendingPlaybackReports()
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
        validate(session)
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
        validate(session)
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
        validate(session)

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
    fun `logout cancels a playback report already waiting on transport`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val session = NativeSession(
            token = "token",
            serverId = "server",
            userId = "user",
            userName = "Viewer",
            serverUrl = server.url("/").toString().trimEnd('/')
        )
        val completed = CountDownLatch(1)
        var success = true
        validate(session)

        JellyfinNativeApi(RuntimeEnvironment.getApplication()).reportStopped(
            session,
            "movie",
            "play-1",
            130_000L
        ) { result ->
            success = result
            completed.countDown()
        }
        requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))

        AuthSessionCoordinator.invalidateAll()
        cancelPendingPlaybackReports()

        assertTrue("Active report was not cancelled", completed.await(2, TimeUnit.SECONDS))
        assertEquals(false, success)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `authenticated 401 publishes rejection for the current credential`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val session = NativeSession(
            token = "token",
            serverId = "server",
            userId = "user",
            userName = "Viewer",
            serverUrl = server.url("/").toString().trimEnd('/')
        )
        validate(session)
        val rejected = CountDownLatch(1)
        val listener: (com.piggie.tv.auth.AuthenticationRejection) -> Unit = {
            if (AuthSessionCoordinator.rejectionMatches(it, session)) rejected.countDown()
        }
        AuthSessionCoordinator.addAuthenticationRejectionListener(listener)

        runCatching {
            JellyfinNativeApi(RuntimeEnvironment.getApplication()).validateSession(session)
        }

        assertTrue("Authenticated 401 was not published", rejected.await(1, TimeUnit.SECONDS))
        AuthSessionCoordinator.removeAuthenticationRejectionListener(listener)
    }

    @Test
    fun `reading progress is not sent after authentication is invalidated`() {
        val session = NativeSession(
            token = "token",
            serverId = "server",
            userId = "user",
            userName = "Viewer",
            serverUrl = server.url("/").toString().trimEnd('/')
        )
        validate(session)
        AuthSessionCoordinator.invalidateAll()

        JellyfinNativeApi(RuntimeEnvironment.getApplication()).reportReadingProgress(
            session,
            "book",
            12,
        )

        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `reading shelves stop issuing requests after logout`() {
        server.enqueue(
            MockResponse()
                .setBody("{\"Items\":[]}")
                .setBodyDelay(300, TimeUnit.MILLISECONDS)
        )
        val session = NativeSession(
            token = "token",
            serverId = "server",
            userId = "user",
            userName = "Viewer",
            serverUrl = server.url("/").toString().trimEnd('/')
        )
        validate(session)
        val completed = CountDownLatch(1)

        Thread {
            JellyfinNativeApi(RuntimeEnvironment.getApplication())
                .loadReadingHomeIncrementally(session) {}
            completed.countDown()
        }.start()
        requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        AuthSessionCoordinator.invalidateAll()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(1, server.requestCount)
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
        validate(session)
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

    private fun validate(session: NativeSession) {
        val attempt = AuthSessionCoordinator.beginAttempt()
        assertTrue(AuthSessionCoordinator.commitAuthenticated(attempt, session) { true })
    }
}
