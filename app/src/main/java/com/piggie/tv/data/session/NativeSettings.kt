package com.piggie.tv.data.session

import android.content.Context
import android.content.SharedPreferences
import com.piggie.tv.data.playback.ConnectionSpeed

class NativeSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ptv_settings", Context.MODE_PRIVATE)

    var connectionSpeed: String
        get() {
            val stored = prefs.getString(KEY_CONNECTION_SPEED, null)
            if (stored != null) return ConnectionSpeed.fromStored(stored).wireValue

            val migrated = ConnectionSpeed.migrateLegacy(
                prefs.getString(KEY_LEGACY_PLAYBACK_QUALITY, null)
            )
            prefs.edit().putString(KEY_CONNECTION_SPEED, migrated).apply()
            return migrated
        }
        set(value) {
            val normalized = ConnectionSpeed.fromStored(value).wireValue
            prefs.edit()
                .putString(KEY_CONNECTION_SPEED, normalized)
                // Retain a readable legacy value for downgrade compatibility.
                .putString(KEY_LEGACY_PLAYBACK_QUALITY, normalized)
                .apply()
        }

    @Deprecated("Use connectionSpeed; resolution labels are not a playback guarantee.")
    var playbackQuality: String
        get() = connectionSpeed
        set(value) {
            connectionSpeed = value
        }

    var subtitlePreference: String
        get() = prefs.getString("subtitle_pref", "Default") ?: "Default"
        set(value) = prefs.edit().putString("subtitle_pref", value).apply()

    var audioLanguage: String
        get() = prefs.getString("audio_lang", "Default") ?: "Default"
        set(value) = prefs.edit().putString("audio_lang", value).apply()

    /** Series playback defaults to continuous play, matching TV expectations. */
    var autoplayNextEpisode: Boolean
        get() = prefs.getBoolean("autoplay_next_episode", true)
        set(value) = prefs.edit().putBoolean("autoplay_next_episode", value).apply()

    var diagnosticsEnabled: Boolean
        get() = prefs.getBoolean("diagnostics_enabled", com.piggie.tv.BuildConfig.DEBUG)
        set(value) = prefs.edit().putBoolean("diagnostics_enabled", value).apply()

    var diagnosticsOverlayEnabled: Boolean
        get() = prefs.getBoolean("diagnostics_overlay_enabled", false)
        set(value) = prefs.edit().putBoolean("diagnostics_overlay_enabled", value).apply()

    var notifyBetaReleases: Boolean
        get() = prefs.getBoolean("notify_beta_releases", false)
        set(value) = prefs.edit().putBoolean("notify_beta_releases", value).apply()

    var reduceMotion: Boolean
        get() = prefs.getBoolean("reduce_motion", false)
        set(value) = prefs.edit().putBoolean("reduce_motion", value).apply()

    private companion object {
        const val KEY_CONNECTION_SPEED = "connection_speed"
        const val KEY_LEGACY_PLAYBACK_QUALITY = "playback_quality"
    }
}
