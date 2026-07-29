package com.piggie.tv.core

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.piggie.tv.BuildConfig
import com.piggie.tv.data.session.NativeSettings
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.util.PTVLog
import com.piggietv.core.PtvCacheEnvelope
import com.piggietv.core.PtvClientCapabilityReport
import com.piggietv.core.PtvCoreError
import com.piggietv.core.PtvCoreErrorKind
import com.piggietv.core.PtvCoreInitializationResult
import com.piggietv.core.PtvCoreInitializationStep
import com.piggietv.core.PtvCoreResult
import com.piggietv.core.PtvCoreScope
import com.piggietv.core.PtvCoreScopeKind
import com.piggietv.core.PtvDiagnosticEvent
import com.piggietv.core.PtvFeatureFlagDocument
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class PtvCoreTvRuntimeSnapshot(
    val capabilityReport: PtvClientCapabilityReport? = null,
    val cacheEnvelope: PtvCacheEnvelope<Map<String, String>>? = null,
    val diagnosticEvent: PtvDiagnosticEvent? = null,
    val featureFlags: PtvFeatureFlagDocument? = null,
    val settings: Map<String, Any?> = emptyMap(),
)

object PtvCoreRuntime {
    private val lock = Any()

    @Volatile
    private var coordinator: PtvCoreRuntimeCoordinator? = null

    @Volatile
    private var state = PtvCoreTvRuntimeSnapshot()

    fun initialize(context: Context): PtvCoreInitializationResult =
        getOrCreateCoordinator(context.applicationContext).initialize()

    fun snapshot(): PtvCoreTvRuntimeSnapshot = state

    private fun getOrCreateCoordinator(context: Context): PtvCoreRuntimeCoordinator {
        coordinator?.let { return it }
        return synchronized(lock) {
            coordinator ?: createCoordinator(context).also { coordinator = it }
        }
    }

    private fun createCoordinator(context: Context): PtvCoreRuntimeCoordinator {
        val listenerRegistry = PtvCoreListenerRegistry()
        val nativeSettings = NativeSettings(context)
        val secureSessionStore = SecureSessionStore(context)
        val sharedPreferences = context.getSharedPreferences("ptv_settings", Context.MODE_PRIVATE)
        val deviceScope = {
            PtvCoreScope(
                PtvCoreScopeKind.DEVICE,
                deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    ?: context.packageName,
            )
        }
        val refreshSettings = {
            val values = mapOf<String, Any?>(
                "autoplayNextEpisode" to nativeSettings.autoplayNextEpisode,
                "diagnosticsEnabled" to nativeSettings.diagnosticsEnabled,
                "diagnosticsOverlayEnabled" to nativeSettings.diagnosticsOverlayEnabled,
                "connectionSpeed" to nativeSettings.connectionSpeed,
            )
            state = state.copy(
                featureFlags = PtvFeatureFlagDocument(
                    schemaVersion = 1,
                    scope = deviceScope(),
                    values = values.filterValues { it is Boolean }.mapValues { it.value as Boolean },
                    updatedAt = timestamp(),
                ),
                settings = values,
            )
        }

        return PtvCoreRuntimeCoordinator(
            handlers = mapOf(
                PtvCoreInitializationStep.ENVIRONMENT to {
                    state = state.copy(capabilityReport = PtvCorePlatformAdapter.capabilityReport())
                    success()
                },
                PtvCoreInitializationStep.DEVICE_IDENTITY to {
                    deviceScope()
                    success()
                },
                PtvCoreInitializationStep.SESSION to {
                    // SecureSessionStore and the existing login activities remain authoritative.
                    secureSessionStore.read()
                    success()
                },
                PtvCoreInitializationStep.SETTINGS to {
                    refreshSettings()
                    listenerRegistry.register("tv-settings") {
                        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshSettings() }
                        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
                        val disposer: () -> Unit = {
                            sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
                        }
                        disposer
                    }
                    success()
                },
                PtvCoreInitializationStep.CACHE to {
                    val cacheDirectory = context.cacheDir
                    if (cacheDirectory.exists() || cacheDirectory.mkdirs()) {
                        val now = System.currentTimeMillis()
                        state = state.copy(
                            cacheEnvelope = PtvCacheEnvelope(
                                schemaVersion = 1,
                                key = "core-runtime-capabilities",
                                scope = deviceScope(),
                                createdAt = timestamp(now),
                                expiresAt = timestamp(now + 24 * 60 * 60 * 1000L),
                                payload = mapOf("owner" to "existing-android-tv-cache"),
                            ),
                        )
                        success()
                    } else {
                        failure("CACHE_DIRECTORY_UNAVAILABLE", PtvCoreErrorKind.CACHE)
                    }
                },
                PtvCoreInitializationStep.API to {
                    // JellyfinNativeApi and NativeHttpTransport remain authoritative.
                    success()
                },
                PtvCoreInitializationStep.DIAGNOSTICS to {
                    val capabilities = state.capabilityReport ?: PtvCorePlatformAdapter.capabilityReport()
                    val event = PtvDiagnosticEvent(
                        category = "startup",
                        name = "core-runtime-initialized",
                        appVersion = BuildConfig.VERSION_NAME,
                        platform = capabilities.platform,
                        deviceClass = capabilities.deviceClass,
                        sessionId = "existing-tv-session",
                        timestamp = timestamp(),
                        success = true,
                        metadata = mapOf("adapter" to "android-tv"),
                    )
                    state = state.copy(diagnosticEvent = event)
                    PTVLog.i("PTV Core runtime initialized contract=${event.contractVersion}")
                    success()
                },
                PtvCoreInitializationStep.NAVIGATION to {
                    // NativePtvShell, Activities, and the TV focus executor continue to own navigation.
                    success()
                },
                PtvCoreInitializationStep.PLAYBACK to {
                    // Existing Media3/native playback, notifications, and lock controls remain authoritative.
                    success()
                },
            ),
            listenerRegistry = listenerRegistry,
        )
    }

    internal fun disposeAndResetForTests() {
        synchronized(lock) {
            coordinator?.disposeAndReset()
            coordinator = null
            state = PtvCoreTvRuntimeSnapshot()
        }
    }

    private fun success(): PtvCoreResult<Unit> = PtvCoreResult.Success(Unit)

    private fun failure(code: String, kind: PtvCoreErrorKind): PtvCoreResult<Unit> =
        PtvCoreResult.Failure(
            PtvCoreError(
                kind = kind,
                code = code,
                message = "PiggieTV core initialization was degraded.",
                retryable = true,
                timestamp = timestamp(),
                operation = "core-runtime",
            ),
        )

    private fun timestamp(value: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(value))
}
