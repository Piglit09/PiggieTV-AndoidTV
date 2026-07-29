package com.piggie.tv.data.session

import android.content.Context
import com.piggie.tv.data.models.NativeSession

class ReadingSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ptv_reading_prefs", Context.MODE_PRIVATE)

    private fun key(session: NativeSession, itemId: String, subKey: String): String =
        "${session.serverId}_${session.userId}_${itemId}_$subKey"

    fun isRtl(session: NativeSession, itemId: String): Boolean =
        prefs.getBoolean(key(session, itemId, "rtl"), false)

    fun setRtl(session: NativeSession, itemId: String, rtl: Boolean) =
        prefs.edit().putBoolean(key(session, itemId, "rtl"), rtl).apply()

    fun getLastPage(session: NativeSession, itemId: String): Int =
        prefs.getInt(key(session, itemId, "page"), 0)

    fun setLastPage(session: NativeSession, itemId: String, page: Int) =
        prefs.edit().putInt(key(session, itemId, "page"), page).apply()

    fun getFitMode(session: NativeSession, itemId: String): String =
        prefs.getString(key(session, itemId, "fit_mode"), "FIT_PAGE") ?: "FIT_PAGE"

    fun setFitMode(session: NativeSession, itemId: String, fitMode: String) =
        prefs.edit().putString(key(session, itemId, "fit_mode"), fitMode).apply()
}
