package com.piggie.tv.data.session

import android.content.Context
import android.content.SharedPreferences

class NativeSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ptv_settings", Context.MODE_PRIVATE)

    var playbackQuality: String
        get() = prefs.getString("playback_quality", "Auto") ?: "Auto"
        set(value) = prefs.edit().putString("playback_quality", value).apply()

    var subtitlePreference: String
        get() = prefs.getString("subtitle_pref", "Default") ?: "Default"
        set(value) = prefs.edit().putString("subtitle_pref", value).apply()

    var audioLanguage: String
        get() = prefs.getString("audio_lang", "Default") ?: "Default"
        set(value) = prefs.edit().putString("audio_lang", value).apply()
}
