package com.piggie.tv.data.api

import com.piggie.tv.auth.AuthSessionCoordinator
import com.piggie.tv.auth.AuthenticationRejection
import com.piggie.tv.data.models.NativeSession
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JellyfinImageAuthenticationInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var session: NativeSession
    private val executors = mutableListOf<ExecutorService>()

    @Before
    fun setUp() {
        AuthSessionCoordinator.resetForTests()
        server = MockWebServer()
        server.start()
        session = NativeSession(
            token = "image-token",
            serverId = "server",
            userId = "user",
            userName = "Viewer",
            serverUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        executors.forEach(ExecutorService::shutdownNow)
        AuthSessionCoordinator.resetForTests()
        server.shutdown()
    }

    @Test
    fun `current authenticated image 401 publishes rejection and preserves response`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("image rejected"))
        validate(session)
        val rejections = AtomicInteger()
        val listener: (AuthenticationRejection) -> Unit = { rejection ->
            if (AuthSessionCoordinator.rejectionMatches(rejection, session)) {
                rejections.incrementAndGet()
            }
        }
        AuthSessionCoordinator.addAuthenticationRejectionListener(listener)

        imageClient().newCall(imageRequest("/poster")).execute().use { response ->
            assertEquals(401, response.code)
            assertEquals("image rejected", response.body?.string())
        }

        assertEquals(1, rejections.get())
        val recorded = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(session.token, recorded.getHeader("X-MediaBrowser-Token"))
        assertTrue(recorded.getHeader("Authorization").orEmpty().contains(session.token))
        AuthSessionCoordinator.removeAuthenticationRejectionListener(listener)
    }

    @Test
    fun `delayed image 401 from before logout cannot reject a same-token relogin`() {
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestStarted.countDown()
                releaseResponse.await(5, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(401)
            }
        }
        validate(session)
        val rejections = AtomicInteger()
        val listener: (AuthenticationRejection) -> Unit = { rejections.incrementAndGet() }
        AuthSessionCoordinator.addAuthenticationRejectionListener(listener)
        val response = submit {
            imageClient().newCall(imageRequest("/old-poster")).execute().use { it.code }
        }

        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        AuthSessionCoordinator.invalidateAll()
        validate(session)
        releaseResponse.countDown()

        assertEquals(401, response.get(5, TimeUnit.SECONDS))
        assertEquals(0, rejections.get())
        assertTrue(AuthSessionCoordinator.isValidated(session))
        AuthSessionCoordinator.removeAuthenticationRejectionListener(listener)
    }

    @Test
    fun `simultaneous image 401s publish one recovery for the epoch`() {
        val requestCount = 3
        val requestsReady = CountDownLatch(requestCount)
        val releaseResponses = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestsReady.countDown()
                releaseResponses.await(5, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(401)
            }
        }
        validate(session)
        val rejections = AtomicInteger()
        val listener: (AuthenticationRejection) -> Unit = { rejections.incrementAndGet() }
        AuthSessionCoordinator.addAuthenticationRejectionListener(listener)
        val client = imageClient()
        val executor = Executors.newFixedThreadPool(requestCount).also(executors::add)
        val responses = List(requestCount) { index ->
            executor.submit<Int> {
                client.newCall(imageRequest("/poster-$index")).execute().use { it.code }
            }
        }

        assertTrue(requestsReady.await(2, TimeUnit.SECONDS))
        releaseResponses.countDown()

        assertEquals(listOf(401, 401, 401), responses.map { it.get(5, TimeUnit.SECONDS) })
        assertEquals(1, rejections.get())
        AuthSessionCoordinator.removeAuthenticationRejectionListener(listener)
    }

    @Test
    fun `image 404 and other non-401 responses preserve authentication`() {
        validate(session)
        val rejections = rejectionCounter()

        listOf(403, 404, 503).forEach { statusCode ->
            server.enqueue(MockResponse().setResponseCode(statusCode))
            imageClient().newCall(imageRequest("/status-$statusCode")).execute().use { response ->
                assertEquals(statusCode, response.code)
            }
        }

        assertEquals(0, rejections.first.get())
        assertTrue(AuthSessionCoordinator.isValidated(session))
        AuthSessionCoordinator.removeAuthenticationRejectionListener(rejections.second)
    }

    @Test
    fun `image network failure preserves the current session`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        validate(session)
        val rejections = rejectionCounter()

        val error = runCatching {
            imageClient().newCall(imageRequest("/offline")).execute().use { }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(0, rejections.first.get())
        assertTrue(AuthSessionCoordinator.isValidated(session))
        AuthSessionCoordinator.removeAuthenticationRejectionListener(rejections.second)
    }

    @Test
    fun `image cancellation preserves the current session`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        validate(session)
        val rejections = rejectionCounter()
        val call = imageClient().newCall(imageRequest("/cancel"))
        val result = submit {
            runCatching { call.execute().use { it.code } }.exceptionOrNull()
        }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        call.cancel()

        assertTrue(result.get(5, TimeUnit.SECONDS) is IOException)
        assertEquals(0, rejections.first.get())
        assertTrue(AuthSessionCoordinator.isValidated(session))
        AuthSessionCoordinator.removeAuthenticationRejectionListener(rejections.second)
    }

    @Test
    fun `foreign-origin image 401 receives no credentials and no rejection`() {
        val foreignServer = MockWebServer()
        foreignServer.start()
        try {
            foreignServer.enqueue(MockResponse().setResponseCode(401))
            validate(session)
            val rejections = rejectionCounter()

            val request = Request.Builder().url(foreignServer.url("/poster")).build()
            imageClient().newCall(request).execute().use { response ->
                assertEquals(401, response.code)
            }

            val recorded = requireNotNull(foreignServer.takeRequest(1, TimeUnit.SECONDS))
            assertNull(recorded.getHeader("Authorization"))
            assertNull(recorded.getHeader("X-Emby-Authorization"))
            assertNull(recorded.getHeader("X-MediaBrowser-Token"))
            assertEquals(0, rejections.first.get())
            assertTrue(AuthSessionCoordinator.isValidated(session))
            AuthSessionCoordinator.removeAuthenticationRejectionListener(rejections.second)
        } finally {
            foreignServer.shutdown()
        }
    }

    private fun imageClient(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .addNetworkInterceptor(
            JellyfinImageAuthenticationInterceptor(
                sessionProvider = { session.takeIf(AuthSessionCoordinator::isValidated) },
                authorizationProvider = { token -> "MediaBrowser Token=\"$token\"" },
            )
        )
        .build()

    private fun imageRequest(path: String): Request = Request.Builder()
        .url(server.url(path))
        .build()

    private fun rejectionCounter(): Pair<AtomicInteger, (AuthenticationRejection) -> Unit> {
        val count = AtomicInteger()
        val listener: (AuthenticationRejection) -> Unit = { count.incrementAndGet() }
        AuthSessionCoordinator.addAuthenticationRejectionListener(listener)
        return count to listener
    }

    private fun validate(candidate: NativeSession) {
        val attempt = AuthSessionCoordinator.beginAttempt()
        assertTrue(AuthSessionCoordinator.commitAuthenticated(attempt, candidate) { true })
    }

    private fun <T> submit(action: () -> T): Future<T> {
        val executor = Executors.newSingleThreadExecutor().also(executors::add)
        return executor.submit(Callable { action() })
    }
}
