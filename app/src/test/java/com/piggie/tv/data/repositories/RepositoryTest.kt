package com.piggie.tv.data.repositories

import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.SecureSessionStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RepositoryTest {

    @Test
    fun testUserRepositorySignOutClearsStore() {
        val context = RuntimeEnvironment.getApplication()
        val store = SecureSessionStore(context)
        val api = JellyfinNativeApi(context)
        val repo = UserRepository(api, store)
        
        val session = NativeSession("t", "s", "u", "n", "https://server.com")
        store.save(session, com.piggie.tv.data.api.SessionOrigin.PASSWORD)
        
        assertNotNull(repo.getCurrentSession())
        repo.signOut()
        assertNull(repo.getCurrentSession())
    }

    @Test
    fun testMediaRepositoryDelegation() {
        // Just verify basic setup
        val context = RuntimeEnvironment.getApplication()
        val api = JellyfinNativeApi(context)
        val repo = MediaRepository(api)
        
        assertNotNull(repo)
    }
}
