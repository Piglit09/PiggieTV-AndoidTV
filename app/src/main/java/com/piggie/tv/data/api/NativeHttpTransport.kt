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
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

class NativeHttpTransport(private val authorization: (String?) -> String) {
    private val latest = AtomicReference<SafeNetworkDiagnostic?>(null)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .eventListenerFactory { call ->
            SafeTimingListener(
                call.request().method,
                sanitize(call.request().url),
                latest
            )
        }
        .build()

    fun latestDiagnostic(): SafeNetworkDiagnostic? = latest.get()

    fun execute(endpoint: String, method: String, body: String?, token: String?): String {
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("Authorization", authorization(token))
            .header("X-Emby-Authorization", authorization(token))
        if (!token.isNullOrBlank()) requestBuilder.header("X-MediaBrowser-Token", token)
        if (body != null) {
            requestBuilder.method(method, body.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            requestBuilder.method(method, null)
        }
        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
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
        }
    }

    private class SafeTimingListener(
        private val method: String,
        private val url: String,
        private val latest: AtomicReference<SafeNetworkDiagnostic?>
    ) : EventListener() {
        private val startedAt = SystemClock.elapsedRealtime()
        private var phase = "connect"
        private var dnsStartedAt: Long? = null
        private var dnsDuration: Long? = null
        private var connectStartedAt: Long? = null
        private var connectDuration: Long? = null
        private var tlsStartedAt: Long? = null
        private var tlsDuration: Long? = null
        private var status: Int? = null

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
        }

        override fun responseHeadersStart(call: Call) {
            phase = "read"
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            status = response.code
        }

        override fun callEnd(call: Call) {
            publish(null)
        }

        override fun callFailed(call: Call, ioe: IOException) {
            publish(ioe)
        }

        private fun publish(error: Throwable?) {
            latest.set(
                SafeNetworkDiagnostic(
                    method = method,
                    url = url,
                    totalMs = SystemClock.elapsedRealtime() - startedAt,
                    dnsMs = dnsDuration,
                    connectMs = connectDuration,
                    tlsMs = tlsDuration,
                    responseStatus = status,
                    failurePhase = error?.let { phase },
                    exceptionClass = error?.javaClass?.simpleName,
                    rootCauseClass = error?.let { rootCause(it)::class.java.simpleName }
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
