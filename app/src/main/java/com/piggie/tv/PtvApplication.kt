package com.piggie.tv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PerformanceMonitor
import com.piggie.tv.core.PtvCoreRuntime
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.discovery.DiscoveryManager
import com.piggie.tv.data.playback.PlaybackOriginPolicy
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import com.piggie.tv.util.CrashReporter
import okhttp3.OkHttpClient

class PtvApplication : Application(), Application.ActivityLifecycleCallbacks, ImageLoaderFactory {
    private val sessionStore by lazy { SecureSessionStore(this) }
    private val imageApi by lazy { JellyfinNativeApi(this) }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.init(this)
        TvRenderingRuntime.initialize(this)
        PtvDiagnosticsManager.initialize(this)
        registerActivityLifecycleCallbacks(this)
        PtvCoreRuntime.initialize(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        PtvCoreRuntime.initialize(activity.applicationContext)
        PtvDiagnosticsManager.attachActivity(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        PerformanceMonitor.detach(activity)
        PtvDiagnosticsManager.detachActivity(activity)
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DiscoveryManager.onTrimMemory(level)
    }

    /**
     * Authenticates Jellyfin artwork through headers so access tokens never enter image URLs,
     * cache keys, diagnostics, or screenshots.
     */
    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                val original = chain.request()
                val session = sessionStore.read()
                val request = original.newBuilder()
                    .removeHeader("Authorization")
                    .removeHeader("X-Emby-Authorization")
                    .removeHeader("X-MediaBrowser-Token")
                    .apply {
                        if (
                            session != null &&
                            PlaybackOriginPolicy.shouldAttachCredentials(
                                session.serverUrl,
                                original.url.toString()
                            )
                        ) {
                            val authorization = imageApi.authorization(session.token)
                            header("Authorization", authorization)
                            header("X-Emby-Authorization", authorization)
                            header("X-MediaBrowser-Token", session.token)
                        }
                    }
                    .build()
                chain.proceed(request)
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(false)
            .build()
    }
}
