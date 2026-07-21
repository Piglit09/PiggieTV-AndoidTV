package com.piggie.tv.data.session

import com.piggie.tv.data.api.SessionOrigin
import com.piggie.tv.data.models.NativeSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecureSessionStoreTest {

    @Test
    fun testSessionPersistence() {
        val context = RuntimeEnvironment.getApplication()
        val store = SecureSessionStore(context)
        val session = NativeSession("token", "server", "user", "name", "https://ptv.io")
        
        store.save(session, SessionOrigin.PASSWORD)
        val loaded = store.read()
        
        assertNotNull(loaded)
        assertEquals("token", loaded?.token)
        assertEquals(SessionOrigin.PASSWORD, store.origin())
    }

    @Test
    fun testSessionClear() {
        val context = RuntimeEnvironment.getApplication()
        val store = SecureSessionStore(context)
        val session = NativeSession("t", "s", "u", "n", "https://ptv.io")
        
        store.save(session, SessionOrigin.STORED)
        store.clear()
        
        assertNull(store.read())
    }

    @Test
    fun testDefaultOrigin() {
        val context = RuntimeEnvironment.getApplication()
        val store = SecureSessionStore(context)
        assertEquals(SessionOrigin.STORED, store.origin())
    }

    @Test
    fun testPartialSessionHandling() {
        val context = RuntimeEnvironment.getApplication()
        val store = SecureSessionStore(context)
        // Manual partial prefs manipulation
        context.getSharedPreferences("ptv_secure_session", 0).edit().putString("token", "t").apply()
        assertNull(store.read()) // Should return null if any required field is missing
    }
}
