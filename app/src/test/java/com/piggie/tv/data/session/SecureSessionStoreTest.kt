package com.piggie.tv.data.session

import android.content.Context
import android.util.Base64
import com.piggie.tv.data.api.SessionOrigin
import com.piggie.tv.data.models.NativeSession
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecureSessionStoreTest {
    private lateinit var context: Context
    private lateinit var cipher: FakeSessionTokenCipher
    private lateinit var store: SecureSessionStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SecureSessionStore.installTokenCipherFactoryForTests(null)
        clearPreferences()
        cipher = FakeSessionTokenCipher()
        store = SecureSessionStore(context, cipher)
    }

    @After
    fun tearDown() {
        SecureSessionStore.installTokenCipherFactoryForTests(null)
        clearPreferences()
    }

    @Test
    fun secureSessionPersistsMetadataSeparatelyWithoutPlaintextToken() {
        val session = session(token = "private-access-token", administrator = true)

        assertTrue(store.save(session, SessionOrigin.PASSWORD))

        val available = store.readResult() as SessionReadResult.Available
        assertEquals(session, available.session)
        assertEquals(SessionOrigin.PASSWORD, available.origin)
        assertEquals(SessionOrigin.PASSWORD, store.origin())
        assertEquals(session, store.read())

        val metadataValues = preferences(SecureSessionStore.METADATA_PREFS_NAME).all.values
        val credentialValues = preferences(SecureSessionStore.CREDENTIAL_PREFS_NAME).all.values
        assertTrue(metadataValues.contains(session.serverUrl))
        assertFalse(metadataValues.contains(session.token))
        assertFalse(credentialValues.contains(session.token))
        assertTrue(preferences(SecureSessionStore.LEGACY_PREFS_NAME).all.isEmpty())
        assertTrue(preferences(SecureSessionStore.AUTH_GUARD_PREFS_NAME).all.isEmpty())
    }

    @Test
    fun failedLogoutRemainsFailClosedAcrossStoreRecreationUntilCleanupSucceeds() {
        assertTrue(store.save(session("logout-must-not-return"), SessionOrigin.PASSWORD))
        cipher.failDestroy = true

        assertFalse(store.clear())
        assertTrue(preferences(SecureSessionStore.AUTH_GUARD_PREFS_NAME).all.isNotEmpty())
        assertFalse(store.save(session("stale-callback"), SessionOrigin.QUICK_CONNECT))
        assertEquals(
            SessionReadResult.StorageFailure,
            SecureSessionStore(context, cipher).readResult(),
        )

        cipher.failDestroy = false
        assertEquals(SessionReadResult.Missing, SecureSessionStore(context, cipher).readResult())
        assertTrue(preferences(SecureSessionStore.AUTH_GUARD_PREFS_NAME).all.isEmpty())
    }

    @Test
    fun keystoreGuardAloneBlocksRestoreWhenPreferenceTombstoneIsUnavailable() {
        assertTrue(store.save(session("guard-fallback-token"), SessionOrigin.PASSWORD))
        cipher.guardArmed = true
        cipher.failDestroy = true
        preferences(SecureSessionStore.AUTH_GUARD_PREFS_NAME).edit().clear().commit()

        assertEquals(SessionReadResult.StorageFailure, store.readResult())
        assertTrue(cipher.guardArmed)

        cipher.failDestroy = false
        assertEquals(SessionReadResult.Missing, SecureSessionStore(context, cipher).readResult())
        assertFalse(cipher.guardArmed)
    }

    @Test
    fun completeLegacySessionMigratesThenRemovesPlaintext() {
        writeLegacySession(session("legacy-token"), SessionOrigin.QUICK_CONNECT)

        val available = store.readResult() as SessionReadResult.Available

        assertEquals("legacy-token", available.session.token)
        assertEquals(SessionOrigin.QUICK_CONNECT, available.origin)
        assertTrue(preferences(SecureSessionStore.LEGACY_PREFS_NAME).all.isEmpty())
        assertTrue(preferences(SecureSessionStore.CREDENTIAL_PREFS_NAME).all.isNotEmpty())
    }

    @Test
    fun migrationVerificationFailurePreservesLegacyAndDoesNotAuthenticate() {
        writeLegacySession(session("legacy-survives"), SessionOrigin.PASSWORD)
        cipher.failDecryptWithStorageError = true

        assertEquals(SessionReadResult.StorageFailure, store.readResult())
        assertEquals(
            "legacy-survives",
            preferences(SecureSessionStore.LEGACY_PREFS_NAME)
                .getString(SecureSessionStore.LEGACY_KEY_TOKEN, null)
        )
        assertNull(store.read())
    }

    @Test
    fun interruptedMigrationWithBothRepresentationsFinishesLegacyCleanup() {
        val session = session("already-secure")
        assertTrue(store.save(session, SessionOrigin.STORED))
        writeLegacySession(session, SessionOrigin.STORED)

        val available = store.readResult() as SessionReadResult.Available

        assertEquals(session, available.session)
        assertTrue(preferences(SecureSessionStore.LEGACY_PREFS_NAME).all.isEmpty())
    }

    @Test
    fun partialLegacyTokenIsClearedAsInvalid() {
        assertTrue(
            preferences(SecureSessionStore.LEGACY_PREFS_NAME)
                .edit()
                .putString(SecureSessionStore.LEGACY_KEY_TOKEN, "orphan-token")
                .commit()
        )

        assertEquals(SessionReadResult.InvalidCleared, store.readResult())
        assertNull(store.read())
        assertTrue(preferences(SecureSessionStore.LEGACY_PREFS_NAME).all.isEmpty())
    }

    @Test
    fun corruptedCiphertextIsClearedAndCannotRestore() {
        assertTrue(store.save(session("token"), SessionOrigin.STORED))
        val credentials = preferences(SecureSessionStore.CREDENTIAL_PREFS_NAME)
        val ciphertextKey = credentials.all.keys.single { it.endsWith(".${SecureSessionStore.KEY_CIPHERTEXT}") }
        assertTrue(
            credentials.edit()
                .putString(
                    ciphertextKey,
                    Base64.encodeToString(ByteArray(40) { 0x5a }, Base64.NO_WRAP)
                )
                .commit()
        )

        assertEquals(SessionReadResult.InvalidCleared, store.readResult())
        assertTrue(credentials.all.isEmpty())
        assertTrue(preferences(SecureSessionStore.METADATA_PREFS_NAME).all.isEmpty())
    }

    @Test
    fun authenticatedMetadataTamperingFailsClosedThroughAad() {
        assertTrue(store.save(session("bound-token"), SessionOrigin.STORED))
        val metadata = preferences(SecureSessionStore.METADATA_PREFS_NAME)
        val serverUrlKey = metadata.all.keys.single { it.endsWith(".${SecureSessionStore.KEY_SERVER_URL}") }
        assertTrue(metadata.edit().putString(serverUrlKey, "https://attacker.invalid").commit())

        assertEquals(SessionReadResult.InvalidCleared, store.readResult())
        assertTrue(metadata.all.isEmpty())
        assertTrue(preferences(SecureSessionStore.CREDENTIAL_PREFS_NAME).all.isEmpty())
    }

    @Test
    fun failedReplacementPreservesPreviouslyVerifiedSession() {
        val original = session("original-token")
        assertTrue(store.save(original, SessionOrigin.PASSWORD))
        cipher.failEncrypt = true

        assertFalse(store.save(session("replacement-token"), SessionOrigin.QUICK_CONNECT))
        assertEquals(original, (store.readResult() as SessionReadResult.Available).session)
    }

    @Test
    fun concurrentStoreInstancesSerializeOneLegacyMigration() {
        val legacy = session("concurrent-token")
        writeLegacySession(legacy, SessionOrigin.QUICK_CONNECT)
        val executor = Executors.newFixedThreadPool(4)

        try {
            val results = List(8) {
                executor.submit<SessionReadResult> {
                    SecureSessionStore(context, cipher).readResult()
                }
            }.map { it.get(5, TimeUnit.SECONDS) }

            assertTrue(results.all { it is SessionReadResult.Available })
            assertTrue(results.all { (it as SessionReadResult.Available).session == legacy })
            assertTrue(preferences(SecureSessionStore.LEGACY_PREFS_NAME).all.isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun clearSynchronouslyRemovesSecureMetadataAndLegacyState() {
        assertTrue(store.save(session("logout-token"), SessionOrigin.PASSWORD))
        writeLegacySession(session("old-legacy-token"), SessionOrigin.STORED)

        assertTrue(store.clear())

        val freshStore = SecureSessionStore(context, cipher)
        assertEquals(SessionReadResult.Missing, freshStore.readResult())
        assertNull(freshStore.read())
        assertTrue(preferences(SecureSessionStore.CREDENTIAL_PREFS_NAME).all.isEmpty())
        assertTrue(preferences(SecureSessionStore.METADATA_PREFS_NAME).all.isEmpty())
        assertTrue(preferences(SecureSessionStore.LEGACY_PREFS_NAME).all.isEmpty())
    }

    @Test
    fun missingSessionUsesStoredOriginDefault() {
        assertEquals(SessionReadResult.Missing, store.readResult())
        assertEquals(SessionOrigin.STORED, store.origin())
    }

    private fun session(
        token: String,
        administrator: Boolean = false
    ) = NativeSession(
        token = token,
        serverId = "server-id",
        userId = "user-id",
        userName = "Piggie",
        serverUrl = "https://ptv.example",
        isAdministrator = administrator
    )

    private fun writeLegacySession(session: NativeSession, origin: SessionOrigin) {
        assertTrue(
            preferences(SecureSessionStore.LEGACY_PREFS_NAME).edit()
                .putString(SecureSessionStore.LEGACY_KEY_TOKEN, session.token)
                .putString(SecureSessionStore.KEY_SERVER_ID, session.serverId)
                .putString(SecureSessionStore.KEY_USER_ID, session.userId)
                .putString(SecureSessionStore.KEY_USER_NAME, session.userName)
                .putString(SecureSessionStore.KEY_SERVER_URL, session.serverUrl)
                .putBoolean(SecureSessionStore.KEY_IS_ADMINISTRATOR, session.isAdministrator)
                .putString(SecureSessionStore.KEY_ORIGIN, origin.name)
                .commit()
        )
    }

    private fun preferences(name: String) =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun clearPreferences() {
        if (!::context.isInitialized) context = RuntimeEnvironment.getApplication()
        listOf(
            SecureSessionStore.LEGACY_PREFS_NAME,
            SecureSessionStore.METADATA_PREFS_NAME,
            SecureSessionStore.CREDENTIAL_PREFS_NAME,
            SecureSessionStore.AUTH_GUARD_PREFS_NAME,
        ).forEach { name -> preferences(name).edit().clear().commit() }
    }

    private class FakeSessionTokenCipher : SessionTokenCipher {
        var failEncrypt = false
        var failDecryptWithStorageError = false
        var failDestroy = false
        var guardArmed = false

        override fun encrypt(
            token: ByteArray,
            additionalAuthenticatedData: ByteArray
        ): EncryptedSessionToken {
            if (failEncrypt) throw SessionTokenStorageException()
            val iv = digest(additionalAuthenticatedData + token).copyOf(12)
            val mask = digest(additionalAuthenticatedData + iv)
            val encryptedBody = ByteArray(token.size) { index ->
                (token[index].toInt() xor mask[index % mask.size].toInt()).toByte()
            }
            val tag = digest(additionalAuthenticatedData + iv + token).copyOf(16)
            return EncryptedSessionToken(encryptedBody + tag, iv)
        }

        override fun decrypt(
            encryptedToken: EncryptedSessionToken,
            additionalAuthenticatedData: ByteArray
        ): ByteArray {
            if (failDecryptWithStorageError) throw SessionTokenStorageException()
            if (encryptedToken.ciphertext.size <= 16) throw SessionTokenCorruptException()
            val bodySize = encryptedToken.ciphertext.size - 16
            val encryptedBody = encryptedToken.ciphertext.copyOfRange(0, bodySize)
            val actualTag = encryptedToken.ciphertext.copyOfRange(bodySize, encryptedToken.ciphertext.size)
            val mask = digest(additionalAuthenticatedData + encryptedToken.initializationVector)
            val token = ByteArray(encryptedBody.size) { index ->
                (encryptedBody[index].toInt() xor mask[index % mask.size].toInt()).toByte()
            }
            val expectedTag = digest(
                additionalAuthenticatedData + encryptedToken.initializationVector + token
            ).copyOf(16)
            if (!MessageDigest.isEqual(expectedTag, actualTag)) throw SessionTokenCorruptException()
            return token
        }

        override fun destroy(): Boolean = !failDestroy

        override fun armClearGuard(): Boolean {
            guardArmed = true
            return true
        }

        override fun clearGuardStatus(): SecureClearGuardStatus =
            if (guardArmed) SecureClearGuardStatus.ARMED else SecureClearGuardStatus.DISARMED

        override fun disarmClearGuard(): Boolean {
            guardArmed = false
            return true
        }

        private fun digest(value: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(value)
    }
}
