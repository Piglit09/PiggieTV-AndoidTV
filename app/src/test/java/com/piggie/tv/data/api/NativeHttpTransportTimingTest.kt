package com.piggie.tv.data.api

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NativeHttpTransportTimingTest {
    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() {
        DebugDiscoveryFaultInjector.configure(null, DiscoveryFaultMode.NORMAL)
        server.shutdown()
    }

    @Test fun getTimingSeparatesHeadersFromBodyAndReportsBytes() {
        val body = "{\"Items\":[]}"
        server.enqueue(
            MockResponse()
                .setHeadersDelay(80, TimeUnit.MILLISECONDS)
                .setBodyDelay(90, TimeUnit.MILLISECONDS)
                .setBody(body)
        )
        val transport = NativeHttpTransport({ "redacted" }, OkHttpClient())
        assertEquals(body, transport.execute(server.url("/Shows/NextUp?UserId=secret").toString(), "GET", null, null))
        val timing = requireNotNull(transport.consumeThreadDiagnostic())

        assertNotNull(timing.requestWriteMs)
        assertNotNull(timing.timeToFirstByteMs)
        assertNotNull(timing.responseReadMs)
        assertEquals(body.toByteArray().size.toLong(), timing.responseBytes)
        assertTrue((timing.totalMs ?: 0L) >= (timing.timeToFirstByteMs ?: 0L) + (timing.responseReadMs ?: 0L))
        assertEquals("/Shows/NextUp", timing.url)
        assertFalse(timing.url.contains("secret"))
        assertNull(timing.debugDelayMs)
    }

    @Test fun simultaneousCallsKeepRequestLocalDiagnostics() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/slow" -> MockResponse().setHeadersDelay(120, TimeUnit.MILLISECONDS).setBody("slow")
                else -> MockResponse().setBody("fast")
            }
        }
        val transport = NativeHttpTransport({ "redacted" }, OkHttpClient())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val slow = pool.submit<SafeNetworkDiagnostic> {
                transport.execute(server.url("/slow").toString(), "GET", null, null)
                requireNotNull(transport.consumeThreadDiagnostic())
            }
            val fast = pool.submit<SafeNetworkDiagnostic> {
                transport.execute(server.url("/fast").toString(), "GET", null, null)
                requireNotNull(transport.consumeThreadDiagnostic())
            }
            assertEquals("/slow", slow.get(5, TimeUnit.SECONDS).url)
            assertEquals("/fast", fast.get(5, TimeUnit.SECONDS).url)
        } finally {
            pool.shutdownNow()
        }
    }
}
