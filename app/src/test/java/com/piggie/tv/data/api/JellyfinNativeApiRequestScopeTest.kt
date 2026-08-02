package com.piggie.tv.data.api

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class JellyfinNativeApiRequestScopeTest {
    private lateinit var server: MockWebServer
    private val executors = mutableListOf<ExecutorService>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        executors.forEach(ExecutorService::shutdownNow)
        server.shutdown()
    }

    @Test
    fun withRequestScopePropagatesCancellationToApiRequest() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
        val scope = NativeRequestScope()
        val request = submitFailure {
            api.withRequestScope(scope) {
                api.validateServer(server.url("/").toString())
            }
        }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

        scope.cancel()

        assertIsCancellation(request)
    }

    @Test
    fun nestedScopeRestoresCancelledOuterScope() {
        server.enqueue(
            MockResponse().setBody(
                """{"ServerName":"unexpected","Version":"1.0"}"""
            )
        )
        val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
        val outer = NativeRequestScope().also(NativeRequestScope::cancel)
        val inner = NativeRequestScope()
        var failure: Throwable? = null

        api.withRequestScope(outer) {
            api.withRequestScope(inner) {
                // The nested scope intentionally performs no request.
            }
            failure = try {
                api.validateServer(server.url("/").toString())
                null
            } catch (error: Throwable) {
                error
            }
        }

        assertTrue("Restored outer scope must reject the request", failure is IOException)
        assertNull("A pre-cancelled scope must fail before network I/O", server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun throwingActionClearsScopeForFollowingRequest() {
        server.enqueue(
            MockResponse().setBody(
                """{"ServerName":"test-server","Version":"1.0"}"""
            )
        )
        val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
        val scope = NativeRequestScope()

        try {
            api.withRequestScope(scope) {
                throw IllegalStateException("sentinel")
            }
        } catch (_: IllegalStateException) {
            // Expected: the assertion below proves cleanup happened in finally.
        }

        scope.cancel()
        val result = api.validateServer(server.url("/").toString())

        assertEquals("test-server", result.name)
        assertEquals("1.0", result.version)
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
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

    private fun assertIsCancellation(future: Future<Throwable?>) {
        val error = future.get(5, TimeUnit.SECONDS)
        assertNotNull("Expected request cancellation to fail the call", error)
        assertTrue("Expected IOException but was ${error?.javaClass?.name}", error is IOException)
    }
}
