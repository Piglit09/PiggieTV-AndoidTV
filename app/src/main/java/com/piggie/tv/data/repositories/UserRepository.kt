package com.piggie.tv.data.repositories

import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.data.discovery.DiscoveryManager
import com.piggie.tv.ui.player.MediaDetailsSeedStore

class UserRepository(
    private val api: JellyfinNativeApi,
    private val store: SecureSessionStore
) {
    fun getCurrentSession(): NativeSession? = store.read()

    fun validateAndRefresh(session: NativeSession): NativeSession {
        val updated = api.validateSession(session)
        // Future: refresh if needed
        return updated
    }

    fun signOut() {
        store.clear()
        DiscoveryManager.clearForLogout()
        MediaDetailsSeedStore.clear()
    }
}
