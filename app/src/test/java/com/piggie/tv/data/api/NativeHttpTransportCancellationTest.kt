package com.piggie.tv.data.api

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeHttpTransportCancellationTest {
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
    fun cancelInFlightRemainsIsolatedBetweenTransportsSharingOneBaseClient() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val baseClient = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        val firstTransport = NativeHttpTransport({ "first" }, baseClient)
        val secondTransport = NativeHttpTransport({ "second" }, baseClient)

        val first = submitFailure {
            firstTransport.execute(server.url("/first").toString(), "GET", null, null)
        }
        val second = submitFailure {
            secondTransport.execute(server.url("/second").toString(), "GET", null, null)
        }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

        firstTransport.cancelInFlight()

        assertIsCancellation(first)
        assertFalse("Second transport call must remain active", second.isDone)

        secondTransport.cancelInFlight()
        assertIsCancellation(second)
    }

    @Test
    fun cancellingRequestScopeAbortsDownloadCall() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val transport = NativeHttpTransport({ "authorization" })
        val scope = NativeRequestScope()
        val download = submitFailure {
            transport.download(server.url("/download").toString(), null, scope) { input ->
                input.readBytes()
            }
        }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

        scope.cancel()

        assertIsCancellation(download)
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
