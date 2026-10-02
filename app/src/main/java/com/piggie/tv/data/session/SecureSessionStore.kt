package com.piggie.tv.data.session

import android.content.Context
import com.piggie.tv.data.api.SessionOrigin
import com.piggie.tv.data.models.NativeSession

class SecureSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("ptv_secure_session", Context.MODE_PRIVATE)

    fun save(session: NativeSession, origin: SessionOrigin) {
        prefs.edit()
            .putString("token", session.token)
            .putString("serverId", session.serverId)
            .putString("userId", session.userId)
            .putString("userName", session.userName)
            .putString("serverUrl", session.serverUrl)
            .putBoolean("isAdministrator", session.isAdministrator)
            .putString("origin", origin.name)
            .apply()
    }

    fun read(): NativeSession? {
        val token = prefs.getString("token", null) ?: return null
        val serverId = prefs.getString("serverId", null) ?: return null
        val userId = prefs.getString("userId", null) ?: return null
        val userName = prefs.getString("userName", null) ?: return null
        val serverUrl = prefs.getString("serverUrl", null) ?: return null
        val isAdministrator = prefs.getBoolean("isAdministrator", false)
        return NativeSession(token, serverId, userId, userName, serverUrl, isAdministrator)
    }

    fun origin(): SessionOrigin = runCatching {
        SessionOrigin.valueOf(prefs.getString("origin", SessionOrigin.STORED.name) ?: SessionOrigin.STORED.name)
    }.getOrElse { SessionOrigin.STORED }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
