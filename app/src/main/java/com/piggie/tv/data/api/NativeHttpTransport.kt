package com.piggie.tv.data.api

import android.os.SystemClock
import okhttp3.Call
import okhttp3.Connection
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PtvNetworkTrace
import com.piggie.tv.diagnostics.PtvRedactor

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
    val requestWriteMs: Long? = null,
    val timeToFirstByteMs: Long? = null,
    val responseReadMs: Long? = null,
    val responseBytes: Long? = null,
    val responseStatus: Int?,
    val failurePhase: String?,
    val exceptionClass: String?,
    val rootCauseClass: String?,
    val debugDelayMs: Long? = null
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
            " requestWriteMs=" + (requestWriteMs ?: 0) +
            " timeToFirstByteMs=" + (timeToFirstByteMs ?: 0) +
            " responseReadMs=" + (responseReadMs ?: 0) +
            " responseBytes=" + (responseBytes ?: 0) +
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

private class RequestTraceHolder {
    val diagnostic = AtomicReference<SafeNetworkDiagnostic?>(null)
}

class NativeHttpTransport internal constructor(
    private val authorization: (String?) -> String,
    baseClient: OkHttpClient
) {
    constructor(authorization: (String?) -> String) : this(
        authorization,
        PtvHttpClientOwner.metadataBaseClient
    )

    private val latest = AtomicReference<SafeNetworkDiagnostic?>(null)
    private val lastForThread = ThreadLocal<SafeNetworkDiagnostic?>()
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    internal val client = baseClient.newBuilder()
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
                latest,
                call.request().tag(RequestTraceHolder::class.java)
            )
        }
        .build()

    fun latestDiagnostic(): SafeNetworkDiagnostic? = latest.get()
    fun consumeThreadDiagnostic(): SafeNetworkDiagnostic? = lastForThread.get().also { lastForThread.remove() }

    fun execute(
        endpoint: String,
        method: String,
        body: String?,
        token: String?,
        requestScope: NativeRequestScope? = null
    ): String {
        val endpointUrl = requireNotNull(endpoint.toHttpUrlOrNull()) { "Invalid endpoint URL" }
        val syntheticStarted = SystemClock.elapsedRealtime()
        var debugDelayMs = 0L
        try {
            val synthetic = DebugDiscoveryFaultInjector.beforeRequest(DebugDiscoveryFaultInjector.category(endpoint))
            debugDelayMs = DebugDiscoveryFaultInjector.consumeAppliedDelayMs() ?: 0L
            if (synthetic != null) {
                val diagnostic = SafeNetworkDiagnostic(
                    method, sanitize(endpointUrl), SystemClock.elapsedRealtime() - syntheticStarted,
                    null, null, null,
                    timeToFirstByteMs = 0,
                    responseReadMs = 0,
                    responseBytes = synthetic.toByteArray(Charsets.UTF_8).size.toLong(),
                    responseStatus = 200,
                    failurePhase = null,
                    exceptionClass = null,
                    rootCauseClass = null,
                    debugDelayMs = debugDelayMs.takeIf { it > 0 }
                )
                latest.set(diagnostic)
                lastForThread.set(diagnostic)
                return synthetic
            }
        } catch (error: Throwable) {
            debugDelayMs = DebugDiscoveryFaultInjector.consumeAppliedDelayMs() ?: debugDelayMs
            val diagnostic = SafeNetworkDiagnostic(
                method, sanitize(endpointUrl), SystemClock.elapsedRealtime() - syntheticStarted,
                null, null, null,
                responseStatus = (error as? HttpRequestFailure)?.statusCode,
                failurePhase = when (error) {
                    is DebugInjectedDisconnectDuringBodyException -> "response-body"
                    is DebugInjectedDisconnectBeforeHeadersException -> "connect"
                    is java.net.SocketTimeoutException -> "call-timeout"
                    is HttpRequestFailure -> "response-headers"
                    else -> "debug-fault"
                },
                exceptionClass = error::class.java.simpleName,
                rootCauseClass = rootCause(error)::class.java.simpleName,
                debugDelayMs = debugDelayMs.takeIf { it > 0 }
            )
            latest.set(diagnostic)
            lastForThread.set(diagnostic)
            throw error
        }
        val traceHolder = RequestTraceHolder()
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
            .tag(RequestTraceHolder::class.java, traceHolder)
        if (body != null) {
            requestBuilder.method(method, body.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            requestBuilder.method(method, null)
        }
        if (requestScope?.isCancelled == true) throw IOException("Request scope was cancelled")
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
            if (traceHolder.diagnostic.get() == null) {
                val diagnostic = SafeNetworkDiagnostic(
                    method = method,
                    url = sanitize(endpointUrl),
                    totalMs = SystemClock.elapsedRealtime() - syntheticStarted,
                    dnsMs = null,
                    connectMs = null,
                    tlsMs = null,
                    responseStatus = (error as? HttpRequestFailure)?.statusCode,
                    failurePhase = "early-failure",
                    exceptionClass = error::class.java.simpleName,
                    rootCauseClass = rootCause(error)::class.java.simpleName,
                    debugDelayMs = debugDelayMs.takeIf { it > 0 }
                )
                traceHolder.diagnostic.set(diagnostic)
                latest.set(diagnostic)
            }
            throw error
        } finally {
            var diagnostic = traceHolder.diagnostic.get()
            if (diagnostic != null && debugDelayMs > 0 && diagnostic.debugDelayMs == null) {
                diagnostic = diagnostic.copy(
                    totalMs = diagnostic.totalMs?.plus(debugDelayMs),
                    debugDelayMs = debugDelayMs
                )
                traceHolder.diagnostic.set(diagnostic)
                latest.set(diagnostic)
            }
            lastForThread.set(diagnostic)
            activeCalls -= call
            requestScope?.unregister(call)
        }
    }

    fun cancelInFlight() {
        activeCalls.forEach(Call::cancel)
    }

    fun download(endpoint: String, token: String?, action: (java.io.InputStream) -> Unit) {
        download(endpoint, token, null, action)
    }

    fun download(
        endpoint: String,
        token: String?,
        requestScope: NativeRequestScope?,
        action: (java.io.InputStream) -> Unit
    ) {
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
        val call = client.newCall(request)
        if (requestScope != null && !requestScope.register(call)) {
            call.cancel()
            throw IOException("Request scope was cancelled")
        }
        activeCalls += call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw HttpRequestFailure(response.code, response.body?.string().orEmpty())
                response.body?.byteStream()?.use(action) ?: throw IOException("Empty response body")
            }
        } catch (error: Throwable) {
            // Update diagnostics similarly if needed
            throw error
        } finally {
            activeCalls -= call
            requestScope?.unregister(call)
        }
    }

    private class SafeTimingListener(
        private val method: String,
        private val url: String,
        private val latest: AtomicReference<SafeNetworkDiagnostic?>,
        private val holder: RequestTraceHolder?
    ) : EventListener() {
        private val startedAtEpochMs = System.currentTimeMillis()
        private val startedAt = SystemClock.elapsedRealtime()
        private val published = AtomicBoolean(false)
        private var networkStarted = false
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
        private var requestWriteCompletedAt: Long? = null
        private var responseHeadersStartedAt: Long? = null
        private var responseHeadersDuration: Long? = null
        private var firstByteMs: Long? = null
        private var responseBodyStartedAt: Long? = null
        private var responseReadDuration: Long? = null
        private var requestBytes: Long? = null
        private var responseBytes: Long? = null

        override fun callStart(call: Call) {
            networkStarted = PtvDiagnosticsManager.networkStarted() > 0
        }

        override fun dnsStart(call: Call, domainName: String) {
            phase = "dns"
            dnsStartedAt = SystemClock.elapsedRealtime()
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            dnsDuration = (dnsDuration ?: 0L) + (elapsed(dnsStartedAt) ?: 0L)
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
            tlsDuration = (tlsDuration ?: 0L) + (elapsed(tlsStartedAt) ?: 0L)
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            val wholeConnect = elapsed(connectStartedAt) ?: 0L
            val tcpOnly = (wholeConnect - (tlsDuration ?: 0L)).coerceAtLeast(0L)
            connectDuration = (connectDuration ?: 0L) + tcpOnly
        }

        override fun requestHeadersStart(call: Call) {
            phase = "write"
            requestStartedAt = SystemClock.elapsedRealtime()
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            requestBytes = byteCount
            requestWriteDuration = elapsed(requestStartedAt)
            requestWriteCompletedAt = SystemClock.elapsedRealtime()
        }

        override fun requestHeadersEnd(call: Call, request: Request) {
            if (requestWriteDuration == null) requestWriteDuration = elapsed(requestStartedAt)
            if (requestWriteCompletedAt == null) requestWriteCompletedAt = SystemClock.elapsedRealtime()
        }

        override fun responseHeadersStart(call: Call) {
            phase = "read"
            responseHeadersStartedAt = SystemClock.elapsedRealtime()
            firstByteMs = SystemClock.elapsedRealtime() - (requestWriteCompletedAt ?: startedAt)
            responseBodyStartedAt = SystemClock.elapsedRealtime()
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            status = response.code
            responseHeadersDuration = elapsed(responseHeadersStartedAt)
        }

        override fun responseBodyStart(call: Call) = Unit

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            responseBytes = byteCount
            responseReadDuration = elapsed(responseBodyStartedAt)
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
            val diagnostic = SafeNetworkDiagnostic(
                    method = method,
                    url = url,
                    totalMs = total,
                    dnsMs = dnsDuration,
                    connectMs = connectDuration,
                    tlsMs = tlsDuration,
                    requestWriteMs = requestWriteDuration,
                    timeToFirstByteMs = firstByteMs,
                    responseReadMs = responseReadDuration,
                    responseBytes = responseBytes,
                    responseStatus = status,
                    failurePhase = error?.let { phase },
                    exceptionClass = error?.javaClass?.simpleName,
                    rootCauseClass = error?.let { rootCause(it)::class.java.simpleName }
                )
            latest.set(diagnostic)
            holder?.diagnostic?.set(diagnostic)
            if (networkStarted) PtvDiagnosticsManager.recordNetwork(
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

        fun sanitize(url: HttpUrl): String = PtvRedactor.endpoint(url.toString())

        fun rootCause(error: Throwable): Throwable {
            var current = error
            while (current.cause != null && current.cause !== current) current = current.cause!!
            return current
        }
    }
}
