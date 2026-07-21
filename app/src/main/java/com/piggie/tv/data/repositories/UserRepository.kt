package com.piggie.tv.data.repositories

import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.SecureSessionStore

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
    }
}
