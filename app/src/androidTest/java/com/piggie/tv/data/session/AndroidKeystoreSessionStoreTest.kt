package com.piggie.tv.data.session

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.piggie.tv.data.api.SessionOrigin
import com.piggie.tv.data.models.NativeSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Device-level proof that the production Android Keystore cipher works on the supported API. */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSessionStoreTest {
    private lateinit var testContext: Context
    private lateinit var tokenCipherAlias: String
    private lateinit var store: SecureSessionStore

    @Before
    fun setUp() {
        // Instrumentation executes with the target app UID on some Fire TV releases, so the test
        // package Context cannot reliably write its own preferences. Keep the writable debug-app
        // Context, but redirect every store preference to a unique test-only name. The debug
        // application ID is distinct from production and no existing debug login is touched.
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        check(targetContext.packageName.endsWith(".debug")) {
            "Keystore verification requires the isolated debug application"
        }
        val preferencePrefix = "ptv_auth_android_test_${UUID.randomUUID()}_"
        testContext = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences(preferencePrefix + name, mode)
        }
        tokenCipherAlias = "${targetContext.packageName}.ptv.session.android-test.${UUID.randomUUID()}"
        clearTestPreferences()
        store = newStore()
        assertTrue(store.clear())
    }

    @After
    fun tearDown() {
        store.clear()
        clearTestPreferences()
        AndroidKeystoreSessionTokenCipher(tokenCipherAlias).apply {
            disarmClearGuard()
            destroy()
        }
    }

    @Test
    fun androidKeystoreRoundTripAndLogoutContainNoPlaintextToken() {
        val session = session("device-private-token")

        assertTrue(store.save(session, SessionOrigin.PASSWORD))
        assertEquals(session, (store.readResult() as SessionReadResult.Available).session)
        assertNoPlaintextToken(session.token)

        assertTrue(store.clear())
        assertEquals(SessionReadResult.Missing, newStore().readResult())

        val replacement = session("replacement-after-key-deletion")
        assertTrue(store.save(replacement, SessionOrigin.PASSWORD))
        assertEquals(replacement, (store.readResult() as SessionReadResult.Available).session)
        assertNoPlaintextToken(replacement.token)
    }

    @Test
    fun legacyTokenMigratesThroughProductionCipherBeforePlaintextRemoval() {
        val session = session("legacy-device-token")
        val legacy = testContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        assertTrue(
            legacy.edit()
                .putString("token", session.token)
                .putString("serverId", session.serverId)
                .putString("userId", session.userId)
                .putString("userName", session.userName)
                .putString("serverUrl", session.serverUrl)
                .putBoolean("isAdministrator", session.isAdministrator)
                .putString("origin", SessionOrigin.STORED.name)
                .commit()
        )

        val restored = store.readResult() as SessionReadResult.Available

        assertEquals(session, restored.session)
        assertTrue(legacy.all.isEmpty())
        assertNoPlaintextToken(session.token)
    }

    @Test
    fun keystoreLogoutGuardPersistsAcrossFreshCipherInstances() {
        val alias = "${testContext.packageName}.ptv.test-clear-guard.${UUID.randomUUID()}"
        val first = AndroidKeystoreSessionTokenCipher(alias)
        val fresh = AndroidKeystoreSessionTokenCipher(alias)
        try {
            assertTrue(first.disarmClearGuard())
            assertTrue(first.armClearGuard())
            assertEquals(SecureClearGuardStatus.ARMED, fresh.clearGuardStatus())
            assertTrue(fresh.disarmClearGuard())
            assertEquals(SecureClearGuardStatus.DISARMED, first.clearGuardStatus())
        } finally {
            fresh.disarmClearGuard()
            first.destroy()
        }
    }

    private fun assertNoPlaintextToken(token: String) {
        listOf(METADATA_PREFS, CREDENTIAL_PREFS, LEGACY_PREFS, AUTH_GUARD_PREFS).forEach { name ->
            val values = testContext.getSharedPreferences(name, Context.MODE_PRIVATE).all.values
            assertFalse(values.any { value -> value?.toString()?.contains(token) == true })
        }
    }

    private fun clearTestPreferences() {
        listOf(METADATA_PREFS, CREDENTIAL_PREFS, LEGACY_PREFS, AUTH_GUARD_PREFS).forEach { name ->
            testContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun newStore(): SecureSessionStore = SecureSessionStore(
        testContext,
        AndroidKeystoreSessionTokenCipher(tokenCipherAlias)
    )

    private fun session(token: String) = NativeSession(
        token = token,
        serverId = "device-server",
        userId = "device-user",
        userName = "Keystore Test",
        serverUrl = "https://keystore.test",
    )

    private companion object {
        const val METADATA_PREFS = "ptv_session_metadata"
        const val CREDENTIAL_PREFS = "ptv_secure_credentials"
        const val LEGACY_PREFS = "ptv_secure_session"
        const val AUTH_GUARD_PREFS = "ptv_auth_guard"
    }
}
