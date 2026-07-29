package com.piggie.tv.core

import android.os.Build
import com.piggie.tv.BuildConfig
import com.piggietv.core.PtvClientCapability
import com.piggietv.core.PtvClientCapabilityReport

object PtvCorePlatformAdapter {
    fun capabilityReport(): PtvClientCapabilityReport {
        val isFireTv = Build.MANUFACTURER.equals("Amazon", ignoreCase = true) ||
            Build.MODEL.startsWith("AFT", ignoreCase = true)
        return PtvClientCapabilityReport(
            platform = if (isFireTv) "fire-tv" else "android-tv",
            deviceClass = "tv",
            capabilities = mapOf(
                PtvClientCapability.VIDEO_PLAYBACK.wireName to true,
                PtvClientCapability.AUDIO_PLAYBACK.wireName to true,
                PtvClientCapability.BACKGROUND_AUDIO.wireName to true,
                PtvClientCapability.DOWNLOADS.wireName to false,
                PtvClientCapability.ANDROID_AUTO.wireName to false,
                PtvClientCapability.TV_FOCUS_NAVIGATION.wireName to true,
                PtvClientCapability.POINTER_INPUT.wireName to false,
                PtvClientCapability.TOUCH_INPUT.wireName to false,
                PtvClientCapability.KEYBOARD_INPUT.wireName to true,
                PtvClientCapability.REMOTE_INPUT.wireName to true,
                PtvClientCapability.READER.wireName to false,
                PtvClientCapability.COMIC_PAGING.wireName to false,
                PtvClientCapability.NATIVE_NOTIFICATIONS.wireName to true,
                PtvClientCapability.LOCK_SCREEN_CONTROLS.wireName to true,
                PtvClientCapability.HARDWARE_DECODING.wireName to true,
                PtvClientCapability.TELEMETRY.wireName to BuildConfig.ENABLE_DIAGNOSTICS,
                PtvClientCapability.OFFLINE_CACHE.wireName to true,
                PtvClientCapability.QUICK_CONNECT.wireName to true,
                PtvClientCapability.DEEP_LINKS.wireName to true,
            ),
        )
    }
}
