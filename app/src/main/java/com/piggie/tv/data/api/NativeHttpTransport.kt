package com.piggie.tv.data.api

import android.os.SystemClock
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PtvNetworkTrace

/**
 * Cancels only the calls explicitly launched inside one UI/work scope.
 *
 * Registration checks cancellation both before and after adding the call so a call cannot slip
 * through the race between a lifecycle cancellation and OkHttp call creation.
 */
class NativeRequestScope {
    private val cancelled = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()

    val isCancelled: Boolean
        get() = cancelled.get()

    internal fun register(call: Call): Boolean {
        if (cancelled.get()) return false
        calls += call
        if (cancelled.get()) {
            calls -= call
            call.cancel()
            return false
        }
        return true
    }

    internal fun unregister(call: Call) {
        calls -= call
    }

    fun cancel() {
        cancelled.set(true)
        calls.forEach(Call::cancel)
        calls.clear()
    }
}

data class SafeNetworkDiagnostic(
    val method: String,
    val url: String,
    val totalMs: Long?,
    val dnsMs: Long?,
    val connectMs: Long?,
    val tlsMs: Long?,
    val responseStatus: Int?,
    val failurePhase: String?,
    val exceptionClass: String?,
    val rootCauseClass: String?
) {
    fun summary(): String {
        val status = responseStatus?.toString() ?: "none"
        val failure = failurePhase ?: "none"
        return method + " " + url + " status=" + status + " totalMs=" + (totalMs ?: 0) + " failure=" + failure
    }

    fun timingDetails(): String =
        "dnsMs=" + (dnsMs ?: 0) +
            " connectMs=" + (connectMs ?: 0) +
            " tlsMs=" + (tlsMs ?: 0) +
            " totalMs=" + (totalMs ?: 0) +
            " phase=" + (failurePhase ?: "complete") +
            " exception=" + (exceptionClass ?: "none") +
            " root=" + (rootCauseClass ?: "none")
}

private data class CredentialScope(
    val scheme: String,
    val host: String,
    val port: Int,
    val authorization: String,
    val token: String?
) {
    fun matches(url: HttpUrl): Boolean =
        scheme == url.scheme &&
            host.equals(url.host, ignoreCase = true) &&
            port == url.port
}

class NativeHttpTransport(private val authorization: (String?) -> String) {
    private val latest = AtomicReference<SafeNetworkDiagnostic?>(null)
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .addNetworkInterceptor { chain ->
            val original = chain.request()
            val scope = original.tag(CredentialScope::class.java)
            val request = original.newBuilder()
                .removeHeader("Authorization")
                .removeHeader("X-Emby-Authorization")
                .removeHeader("X-MediaBrowser-Token")
                .apply {
                    if (scope?.matches(original.url) == true) {
                        header("Authorization", scope.authorization)
                        header("X-Emby-Authorization", scope.authorization)
                        scope.token
                            ?.takeIf(String::isNotBlank)
                            ?.let { header("X-MediaBrowser-Token", it) }
                    }
                }
                .build()
            chain.proceed(request)
        }
        .eventListenerFactory { call ->
            SafeTimingListener(
                call.request().method,
                sanitize(call.request().url),
                latest
            )
        }
        .build()

    fun latestDiagnostic(): SafeNetworkDiagnostic? = latest.get()

    fun execute(
        endpoint: String,
        method: String,
        body: String?,
        token: String?,
        requestScope: NativeRequestScope? = null
    ): String {
        val endpointUrl = requireNotNull(endpoint.toHttpUrlOrNull()) { "Invalid endpoint URL" }
        val requestBuilder = Request.Builder()
            .url(endpointUrl)
            .header("Accept", "application/json")
            .tag(
                CredentialScope::class.java,
                CredentialScope(
                    endpointUrl.scheme,
                    endpointUrl.host,
                    endpointUrl.port,
                    authorization(token),
                    token
                )
            )
        if (body != null) {
            requestBuilder.method(method, body.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            requestBuilder.method(method, null)
        }
        val call = client.newCall(requestBuilder.build())
        if (requestScope != null && !requestScope.register(call)) {
            call.cancel()
            throw IOException("Request scope was cancelled")
        }
        activeCalls += call
        return try {
            call.execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw HttpRequestFailure(response.code, responseBody)
                responseBody
            }
        } catch (error: Throwable) {
            val diag = latest.get()
            if (diag == null || diag.url != endpoint.substringBefore('?')) {
                latest.set(
                    SafeNetworkDiagnostic(
                        method = method,
                        url = endpoint.substringBefore('?'),
                        totalMs = SystemClock.elapsedRealtime(), // approximate
                        dnsMs = null,
                        connectMs = null,
                        tlsMs = null,
                        responseStatus = null,
                        failurePhase = "early-failure",
                        exceptionClass = error::class.java.simpleName,
                        rootCauseClass = rootCause(error)::class.java.simpleName
                    )
                )
            }
            throw error
        } finally {
            activeCalls -= call
            requestScope?.unregister(call)
        }
    }

    fun cancelInFlight() {
        activeCalls.forEach(Call::cancel)
    }

    fun download(endpoint: String, token: String?, action: (java.io.InputStream) -> Unit) {
        val endpointUrl = requireNotNull(endpoint.toHttpUrlOrNull()) { "Invalid endpoint URL" }
        val request = Request.Builder()
            .url(endpointUrl)
            .tag(
                CredentialScope::class.java,
                CredentialScope(
                    endpointUrl.scheme,
                    endpointUrl.host,
                    endpointUrl.port,
                    authorization(token),
                    token
                )
            )
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw HttpRequestFailure(response.code, response.body?.string().orEmpty())
                response.body?.byteStream()?.use(action) ?: throw IOException("Empty response body")
            }
        } catch (error: Throwable) {
            // Update diagnostics similarly if needed
            throw error
        }
    }

    private class SafeTimingListener(
        private val method: String,
        private val url: String,
        private val latest: AtomicReference<SafeNetworkDiagnostic?>
    ) : EventListener() {
        private val startedAtEpochMs = System.currentTimeMillis()
        private val startedAt = SystemClock.elapsedRealtime()
        private val published = AtomicBoolean(false)
        @Suppress("unused") private val inFlightOrdinal = PtvDiagnosticsManager.networkStarted()
        private var phase = "connect"
        private var dnsStartedAt: Long? = null
        private var dnsDuration: Long? = null
        private var connectStartedAt: Long? = null
        private var connectDuration: Long? = null
        private var tlsStartedAt: Long? = null
        private var tlsDuration: Long? = null
        private var status: Int? = null
        private var requestStartedAt: Long? = null
        private var requestWriteDuration: Long? = null
        private var responseHeadersStartedAt: Long? = null
        private var responseHeadersDuration: Long? = null
        private var firstByteMs: Long? = null
        private var requestBytes: Long? = null
        private var responseBytes: Long? = null

        override fun dnsStart(call: Call, domainName: String) {
            phase = "dns"
            dnsStartedAt = SystemClock.elapsedRealtime()
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            dnsDuration = elapsed(dnsStartedAt)
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            phase = "connect"
            connectStartedAt = SystemClock.elapsedRealtime()
        }

        override fun secureConnectStart(call: Call) {
            phase = "tls"
            tlsStartedAt = SystemClock.elapsedRealtime()
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            tlsDuration = elapsed(tlsStartedAt)
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            connectDuration = elapsed(connectStartedAt)
        }

        override fun requestHeadersStart(call: Call) {
            phase = "write"
            requestStartedAt = SystemClock.elapsedRealtime()
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            requestBytes = byteCount
            requestWriteDuration = elapsed(requestStartedAt)
        }

        override fun responseHeadersStart(call: Call) {
            phase = "read"
            responseHeadersStartedAt = SystemClock.elapsedRealtime()
            firstByteMs = SystemClock.elapsedRealtime() - startedAt
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            status = response.code
            responseHeadersDuration = elapsed(responseHeadersStartedAt)
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            responseBytes = byteCount
        }

        override fun callEnd(call: Call) {
            publish(null)
        }

        override fun callFailed(call: Call, ioe: IOException) {
            publish(ioe)
        }

        private fun publish(error: Throwable?) {
            if (!published.compareAndSet(false, true)) return
            val total = SystemClock.elapsedRealtime() - startedAt
            latest.set(
                SafeNetworkDiagnostic(
                    method = method,
                    url = url,
                    totalMs = total,
                    dnsMs = dnsDuration,
                    connectMs = connectDuration,
                    tlsMs = tlsDuration,
                    responseStatus = status,
                    failurePhase = error?.let { phase },
                    exceptionClass = error?.javaClass?.simpleName,
                    rootCauseClass = error?.let { rootCause(it)::class.java.simpleName }
                )
            )
            PtvDiagnosticsManager.recordNetwork(
                PtvNetworkTrace(
                    method = method,
                    endpoint = url,
                    startedAtMs = startedAtEpochMs,
                    dnsMs = dnsDuration,
                    connectMs = connectDuration,
                    tlsMs = tlsDuration,
                    requestWriteMs = requestWriteDuration,
                    responseHeadersMs = responseHeadersDuration,
                    firstByteMs = firstByteMs,
                    totalMs = total,
                    status = status,
                    requestBytes = requestBytes,
                    responseBytes = responseBytes,
                    exception = error?.let { rootCause(it).javaClass.simpleName }
                )
            )
        }

        private fun elapsed(started: Long?): Long? =
            started?.let { SystemClock.elapsedRealtime() - it }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun sanitize(url: HttpUrl): String = url.newBuilder().query(null).build().toString()

        fun rootCause(error: Throwable): Throwable {
            var current = error
            while (current.cause != null && current.cause !== current) current = current.cause!!
            return current
        }
    }
}
