package com.piggie.tv.util

import com.piggie.tv.BuildConfig
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Bounds the disposable visual-validation build to the two exact local fixture addresses. */
internal object LocalFixtureNetworkGuard : Interceptor {
    internal fun allowsFixtureUrl(url: HttpUrl): Boolean =
        url.scheme == "http" &&
            (url.host == "127.0.0.1" || url.host == "10.16.0.125") &&
            url.port == 18896 &&
            url.username.isEmpty() &&
            url.password.isEmpty()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (BuildConfig.LOCAL_FIXTURE_ONLY && !allowsFixtureUrl(request.url)) {
            throw IOException("Local fixture allows only http://127.0.0.1:18896 or http://10.16.0.125:18896")
        }
        return chain.proceed(request)
    }
}
