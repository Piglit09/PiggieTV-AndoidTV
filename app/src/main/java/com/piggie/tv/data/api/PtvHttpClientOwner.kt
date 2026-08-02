package com.piggie.tv.data.api

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Process-wide ownership for Jellyfin metadata HTTP resources.
 *
 * NativeHttpTransport derives a lightweight child client so credentials, diagnostics, and
 * cancellation remain transport-local while connections and dispatcher state can be reused.
 */
internal object PtvHttpClientOwner {
    val metadataBaseClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build()
    }
}
