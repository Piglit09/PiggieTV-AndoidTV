package com.piggie.tv.data.api

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import com.piggie.tv.BuildConfig
import com.piggie.tv.util.LocalFixtureNetworkGuard
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
            .addInterceptor(LocalFixtureNetworkGuard)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(!BuildConfig.LOCAL_FIXTURE_ONLY)
            .followSslRedirects(!BuildConfig.LOCAL_FIXTURE_ONLY)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build()
    }
}
