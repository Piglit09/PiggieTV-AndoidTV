package com.piggie.tv.data.api

import com.piggie.tv.auth.AuthSessionCoordinator
import com.piggie.tv.auth.AuthenticationRejection
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
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
class JellyfinNativeApiBinaryAuthenticationTest {
    private lateinit var server: MockWebServer
    private lateinit var session: NativeSession
    private val executors = mutableListOf<ExecutorService>()

    @Before
    fun setUp() {
        AuthSessionCoordinator.resetForTests()
        server = MockWebServer()
        server.start()
        session = NativeSession(
            token = "binary-token",
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
    fun `all binary entry points publish a current-session 401 without retaining its body`() {
        val item = MediaItem(
            id = "poster",
            title = "Poster",
            type = "Movie",
            year = null,
            imageTag = "tag",
            seriesName = null,
            episodeLabel = null,
            playbackPositionTicks = 0L,
            runtimeTicks = 0L,
        )
        val operations = listOf<(JellyfinNativeApi) -> Unit>(
            { api -> api.loadPageBytes(session, "book", 4) },
            { api -> api.downloadFile(session, "book") { input -> input.readBytes() } },
            { api -> api.probeImage(session, item, MediaCardPresentation.POSTER) },
        )

        operations.forEach { operation ->
            AuthSessionCoordinator.resetForTests()
            validate(session)
            val rejections = AtomicInteger()
            val listener: (AuthenticationRejection) -> Unit = { rejection ->
                if (AuthSessionCoordinator.rejectionMatches(rejection, session)) {
                    rejections.incrementAndGet()
                }
            }
            AuthSessionCoordinator.addAuthenticationRejectionListener(listener)
            server.enqueue(MockResponse().setResponseCode(401).setBody("private binary error"))

            val error = assertThrows(HttpRequestFailure::class.java) {
                operation(JellyfinNativeApi(RuntimeEnvironment.getApplication()))
            }

            assertEquals(401, error.statusCode)
            assertEquals("", error.responseBody)
            assertEquals(1, rejections.get())
            AuthSessionCoordinator.removeAuthenticationRejectionListener(listener)
        }
    }

    @Test
    fun `delayed binary 401 from before logout cannot reject a same-token relogin`() {
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
        val request = submitFailure {
            JellyfinNativeApi(RuntimeEnvironment.getApplication())
                .loadPageBytes(session, "book", 1)
        }

        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        AuthSessionCoordinator.invalidateAll()
        validate(session)
        releaseResponse.countDown()

        val error = request.get(5, TimeUnit.SECONDS)
        assertTrue(error is HttpRequestFailure && error.statusCode == 401)
        assertEquals(0, rejections.get())
        assertTrue(AuthSessionCoordinator.isValidated(session))
        AuthSessionCoordinator.removeAuthenticationRejectionListener(listener)
    }

    @Test
    fun `binary network failure preserves the current session`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        validate(session)
        val rejections = rejectionCounter()

        val error = runCatching {
            JellyfinNativeApi(RuntimeEnvironment.getApplication())
                .loadPageBytes(session, "book", 2)
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(0, rejections.first.get())
        assertTrue(AuthSessionCoordinator.isValidated(session))
        AuthSessionCoordinator.removeAuthenticationRejectionListener(rejections.second)
    }

    @Test
    fun `binary cancellation preserves the current session`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        validate(session)
        val rejections = rejectionCounter()
        val scope = NativeRequestScope()
        val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
        val request = submitFailure {
            api.withRequestScope(scope) {
                api.loadPageBytes(session, "book", 3)
            }
        }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        scope.cancel()

        assertTrue(request.get(5, TimeUnit.SECONDS) is IOException)
        assertEquals(0, rejections.first.get())
        assertTrue(AuthSessionCoordinator.isValidated(session))
        AuthSessionCoordinator.removeAuthenticationRejectionListener(rejections.second)
    }

    @Test
    fun `binary non-401 HTTP failures preserve the current session`() {
        validate(session)
        val rejections = rejectionCounter()
        val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())

        listOf(403, 404, 503).forEach { statusCode ->
            server.enqueue(MockResponse().setResponseCode(statusCode))
            val error = assertThrows(HttpRequestFailure::class.java) {
                api.downloadFile(session, "book") { input -> input.readBytes() }
            }
            assertEquals(statusCode, error.statusCode)
        }

        assertEquals(0, rejections.first.get())
        assertTrue(AuthSessionCoordinator.isValidated(session))
        AuthSessionCoordinator.removeAuthenticationRejectionListener(rejections.second)
    }

    @Test
    fun `simultaneous API image and binary 401s publish one logical recovery`() {
        val requestsReady = CountDownLatch(3)
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
        val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
        val imageClient = OkHttpClient.Builder()
            .addNetworkInterceptor(
                JellyfinImageAuthenticationInterceptor(
                    sessionProvider = { session.takeIf(AuthSessionCoordinator::isValidated) },
                    authorizationProvider = { token -> "MediaBrowser Token=\"$token\"" },
                )
            )
            .build()
        val executor = Executors.newFixedThreadPool(3).also(executors::add)
        val apiFailure = executor.submit<Throwable?> {
            runCatching { api.validateSession(session) }.exceptionOrNull()
        }
        val binaryFailure = executor.submit<Throwable?> {
            runCatching { api.loadPageBytes(session, "book", 5) }.exceptionOrNull()
        }
        val imageStatus = executor.submit<Int> {
            val request = Request.Builder().url(server.url("/poster")).build()
            imageClient.newCall(request).execute().use { it.code }
        }

        assertTrue(requestsReady.await(2, TimeUnit.SECONDS))
        releaseResponses.countDown()

        assertTrue(apiFailure.get(5, TimeUnit.SECONDS) is HttpRequestFailure)
        assertTrue(binaryFailure.get(5, TimeUnit.SECONDS) is HttpRequestFailure)
        assertEquals(401, imageStatus.get(5, TimeUnit.SECONDS))
        assertEquals(1, rejections.get())
        assertTrue(AuthSessionCoordinator.isValidated(session))
        AuthSessionCoordinator.removeAuthenticationRejectionListener(listener)
    }

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

    private fun submitFailure(action: () -> Unit): Future<Throwable?> {
        val executor = Executors.newSingleThreadExecutor().also(executors::add)
        return executor.submit(
            Callable {
                try {
                    action()
                    null
                } catch (error: Throwable) {
                    error
                }
            }
        )
    }
}
