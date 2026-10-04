package com.piggie.tv.data.repositories

import com.piggie.tv.auth.AuthSessionCoordinator
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.EncryptedSessionToken
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.data.session.SessionTokenCipher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RepositoryTest {
    @Before
    fun setUpSecureStore() {
        AuthSessionCoordinator.resetForTests()
        SecureSessionStore.installTokenCipherFactoryForTests { PassthroughTokenCipher }
    }

    @After
    fun tearDownSecureStore() {
        AuthSessionCoordinator.resetForTests()
        SecureSessionStore(RuntimeEnvironment.getApplication()).clear()
        SecureSessionStore.installTokenCipherFactoryForTests(null)
    }

    @Test
    fun testUserRepositorySignOutClearsStore() {
        val context = RuntimeEnvironment.getApplication()
        val store = SecureSessionStore(context)
        val api = JellyfinNativeApi(context)
        val repo = UserRepository(api, store)
        
        val session = NativeSession("t", "s", "u", "n", "https://server.com")
        assertTrue(store.save(session, com.piggie.tv.data.api.SessionOrigin.PASSWORD))

        // Durable credentials alone are not enough after process recreation.
        assertNull(repo.getCurrentSession())
        val attempt = AuthSessionCoordinator.beginAttempt()
        assertTrue(AuthSessionCoordinator.commitAuthenticated(attempt, session) { true })
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

    private object PassthroughTokenCipher : SessionTokenCipher {
        override fun encrypt(
            token: ByteArray,
            additionalAuthenticatedData: ByteArray,
        ) = EncryptedSessionToken(token + ByteArray(16), ByteArray(12))

        override fun decrypt(
            encryptedToken: EncryptedSessionToken,
            additionalAuthenticatedData: ByteArray,
        ): ByteArray = encryptedToken.ciphertext.copyOfRange(
            0,
            encryptedToken.ciphertext.size - 16,
        )
    }
}
