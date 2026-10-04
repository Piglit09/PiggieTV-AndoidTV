package com.piggie.tv.auth

import android.app.Application
import android.widget.EditText
import com.piggie.tv.R
import com.piggie.tv.data.session.EncryptedSessionToken
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.data.session.SessionTokenCipher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class MainActivityPasswordLifecycleTest {
    private lateinit var application: Application
    private lateinit var store: SecureSessionStore
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        AuthSessionCoordinator.resetForTests()
        SecureSessionStore.installTokenCipherFactoryForTests { PassthroughTokenCipher }
        application = RuntimeEnvironment.getApplication()
        store = SecureSessionStore(application)
        store.clear()
        application.getSharedPreferences("ptv_auth", 0).edit().clear().commit()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.destroy() } }
        controller = null
        store.clear()
        AuthSessionCoordinator.resetForTests()
        SecureSessionStore.installTokenCipherFactoryForTests(null)
    }

    @Test
    fun passwordIsNeverSavedInViewStateAndIsWipedWhenScreenStops() {
        val password = requireNotNull(controller).get().findViewById<EditText>(R.id.password_input)
        assertFalse(password.isSaveEnabled)
        password.setText("do-not-retain")

        requireNotNull(controller).pause().stop()

        assertEquals("", password.text.toString())
    }

    @Test
    fun legacyServerUrlContainingCredentialsIsDeletedBeforeDisplay() {
        requireNotNull(controller).destroy()
        application.getSharedPreferences("ptv_auth", 0)
            .edit()
            .putString("last_server", "https://legacy-user:legacy-password@example.test")
            .commit()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        val server = requireNotNull(controller).get().findViewById<EditText>(R.id.server_input)

        assertEquals("", server.text.toString())
        assertFalse(
            application.getSharedPreferences("ptv_auth", 0).contains("last_server")
        )
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
