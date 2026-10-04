package com.piggie.tv.data.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.piggie.tv.data.api.SessionOrigin
import com.piggie.tv.data.models.NativeSession
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

sealed interface SessionReadResult {
    data class Available(
        val session: NativeSession,
        val origin: SessionOrigin
    ) : SessionReadResult

    data object Missing : SessionReadResult

    data object InvalidCleared : SessionReadResult

    data object StorageFailure : SessionReadResult
}

/**
 * Persists non-sensitive identity metadata separately from an authenticated, encrypted token.
 *
 * All mutations are serialized across store instances because authentication activities, the
 * application runtime, playback services, and logout surfaces each construct their own store.
 */
class SecureSessionStore internal constructor(
    context: Context,
    private val tokenCipher: SessionTokenCipher
) {
    constructor(context: Context) : this(
        context,
        createTokenCipher(context.applicationContext)
    )

    private val applicationContext = context.applicationContext
    private val metadataPrefs = applicationContext.getSharedPreferences(
        METADATA_PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val credentialPrefs = applicationContext.getSharedPreferences(
        CREDENTIAL_PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val legacyPrefs = applicationContext.getSharedPreferences(
        LEGACY_PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val authGuardPrefs = applicationContext.getSharedPreferences(
        AUTH_GUARD_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * Writes and verifies a new record before making it active. A failed staged write leaves the
     * previous active session untouched. The return value must be checked before entering the app.
     */
    fun save(session: NativeSession, origin: SessionOrigin): Boolean = synchronized(PROCESS_LOCK) {
        try {
            saveLocked(session, origin)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Loads secure state, completes a legacy migration when needed, and never throws to callers.
     */
    fun readResult(): SessionReadResult = synchronized(PROCESS_LOCK) {
        try {
            readLocked()
        } catch (_: Throwable) {
            SessionReadResult.StorageFailure
        }
    }

    /** Compatibility bridge for existing consumers while authentication adopts readResult(). */
    fun read(): NativeSession? = (readResult() as? SessionReadResult.Available)?.session

    fun origin(): SessionOrigin = synchronized(PROCESS_LOCK) {
        try {
            activeRecordId()
                ?.let(::readMetadata)
                ?.origin
                ?: readLegacyState().originOrDefault()
        } catch (_: Throwable) {
            SessionOrigin.STORED
        }
    }

    /**
     * Synchronously removes secure, metadata, and legacy state and verifies the in-memory view is
     * empty. The return value must be checked before treating logout as complete.
     */
    fun clear(): Boolean = synchronized(PROCESS_LOCK) {
        try {
            clearLocked()
        } catch (_: Throwable) {
            false
        }
    }

    private fun readLocked(): SessionReadResult {
        when (clearBarrierStatus()) {
            SecureClearGuardStatus.ARMED -> return if (finishPendingClearLocked()) {
                    SessionReadResult.Missing
                } else {
                    SessionReadResult.StorageFailure
                }

            SecureClearGuardStatus.UNAVAILABLE -> return SessionReadResult.StorageFailure
            SecureClearGuardStatus.DISARMED -> Unit
        }

        val activeId = activeRecordId()
        if (activeId != null) {
            when (val secure = readSecureRecord(activeId)) {
                is SecureRecordRead.Available -> {
                    if (legacyPrefs.all.isNotEmpty() && !legacyPrefs.edit().clear().commit()) {
                        return SessionReadResult.StorageFailure
                    }
                    cleanupInactiveRecords(activeId)
                    return SessionReadResult.Available(secure.session, secure.origin)
                }

                SecureRecordRead.StorageFailure -> return SessionReadResult.StorageFailure
                SecureRecordRead.Corrupt -> {
                    val legacy = readLegacyState()
                    if (legacy is LegacyState.Complete) return migrateLegacyLocked(legacy)
                    return clearInvalidStateLocked()
                }
            }
        }

        return when (val legacy = readLegacyState()) {
            is LegacyState.Complete -> migrateLegacyLocked(legacy)
            LegacyState.Invalid -> clearInvalidStateLocked()
            LegacyState.Missing -> {
                if (metadataPrefs.all.isNotEmpty() || credentialPrefs.all.isNotEmpty()) {
                    clearInvalidStateLocked()
                } else {
                    SessionReadResult.Missing
                }
            }
        }
    }

    private fun migrateLegacyLocked(legacy: LegacyState.Complete): SessionReadResult {
        if (!saveLocked(legacy.session, legacy.origin)) {
            return SessionReadResult.StorageFailure
        }
        val activeId = activeRecordId() ?: return SessionReadResult.StorageFailure
        return when (val secure = readSecureRecord(activeId)) {
            is SecureRecordRead.Available -> {
                if (legacyPrefs.all.isNotEmpty()) {
                    // saveLocked normally completes this step. Retrying it handles an interrupted
                    // migration where the secure representation was already activated.
                    if (!legacyPrefs.edit().clear().commit()) {
                        return SessionReadResult.StorageFailure
                    }
                }
                SessionReadResult.Available(secure.session, secure.origin)
            }

            SecureRecordRead.Corrupt -> clearInvalidStateLocked()
            SecureRecordRead.StorageFailure -> SessionReadResult.StorageFailure
        }
    }

    private fun saveLocked(session: NativeSession, origin: SessionOrigin): Boolean {
        if (clearBarrierStatus() != SecureClearGuardStatus.DISARMED) return false
        if (!session.isComplete()) return false

        val previousActiveId = activeRecordId()
        val recordId = UUID.randomUUID().toString()
        val metadata = SessionMetadata.from(recordId, session, origin)
        val encryptedToken = try {
            tokenCipher.encrypt(
                session.token.toByteArray(Charsets.UTF_8),
                additionalAuthenticatedData(metadata)
            )
        } catch (_: Throwable) {
            return false
        }
        if (!encryptedToken.isStructurallyValid()) return false

        if (!writeCredentials(recordId, encryptedToken)) {
            removeRecord(recordId)
            return false
        }
        if (!writeMetadata(metadata)) {
            removeRecord(recordId)
            return false
        }
        if (!verifyRecord(recordId, session, origin)) {
            removeRecord(recordId)
            return false
        }

        if (!metadataPrefs.edit().putString(KEY_ACTIVE_RECORD_ID, recordId).commit()) {
            restoreActiveRecord(previousActiveId)
            removeRecord(recordId)
            return false
        }
        if (activeRecordId() != recordId || !verifyRecord(recordId, session, origin)) {
            restoreActiveRecord(previousActiveId)
            removeRecord(recordId)
            return false
        }

        // The secure representation is active and verified before the legacy plaintext is
        // removed. If cleanup fails, the secure record remains available for a later retry, but
        // callers receive failure and must not authenticate from the legacy value.
        if (legacyPrefs.all.isNotEmpty() && !legacyPrefs.edit().clear().commit()) {
            return false
        }

        cleanupInactiveRecords(recordId)
        return true
    }

    private fun readSecureRecord(recordId: String): SecureRecordRead {
        val metadata = readMetadata(recordId) ?: return SecureRecordRead.Corrupt
        val encryptedToken = readCredentials(recordId) ?: return SecureRecordRead.Corrupt
        val token = try {
            tokenCipher.decrypt(encryptedToken, additionalAuthenticatedData(metadata))
                .toString(Charsets.UTF_8)
        } catch (_: SessionTokenCorruptException) {
            return SecureRecordRead.Corrupt
        } catch (_: Throwable) {
            return SecureRecordRead.StorageFailure
        }
        val session = metadata.toSession(token)
        return if (session.isComplete()) {
            SecureRecordRead.Available(session, metadata.origin)
        } else {
            SecureRecordRead.Corrupt
        }
    }

    private fun verifyRecord(
        recordId: String,
        expectedSession: NativeSession,
        expectedOrigin: SessionOrigin
    ): Boolean = when (val record = readSecureRecord(recordId)) {
        is SecureRecordRead.Available ->
            record.session == expectedSession && record.origin == expectedOrigin

        SecureRecordRead.Corrupt,
        SecureRecordRead.StorageFailure -> false
    }

    private fun writeCredentials(
        recordId: String,
        encryptedToken: EncryptedSessionToken
    ): Boolean = credentialPrefs.edit()
        .putInt(recordKey(recordId, KEY_FORMAT_VERSION), STORAGE_FORMAT_VERSION)
        .putString(
            recordKey(recordId, KEY_INITIALIZATION_VECTOR),
            Base64.encodeToString(encryptedToken.initializationVector, Base64.NO_WRAP)
        )
        .putString(
            recordKey(recordId, KEY_CIPHERTEXT),
            Base64.encodeToString(encryptedToken.ciphertext, Base64.NO_WRAP)
        )
        .commit()

    private fun readCredentials(recordId: String): EncryptedSessionToken? {
        val values = credentialPrefs.all
        if (values[recordKey(recordId, KEY_FORMAT_VERSION)] != STORAGE_FORMAT_VERSION) return null
        val encodedIv = values[recordKey(recordId, KEY_INITIALIZATION_VECTOR)] as? String ?: return null
        val encodedCiphertext = values[recordKey(recordId, KEY_CIPHERTEXT)] as? String ?: return null
        return runCatching {
            EncryptedSessionToken(
                ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP),
                initializationVector = Base64.decode(encodedIv, Base64.NO_WRAP)
            )
        }.getOrNull()?.takeIf { it.isStructurallyValid() }
    }

    private fun writeMetadata(metadata: SessionMetadata): Boolean = metadataPrefs.edit()
        .putString(recordKey(metadata.recordId, KEY_SERVER_ID), metadata.serverId)
        .putString(recordKey(metadata.recordId, KEY_USER_ID), metadata.userId)
        .putString(recordKey(metadata.recordId, KEY_USER_NAME), metadata.userName)
        .putString(recordKey(metadata.recordId, KEY_SERVER_URL), metadata.serverUrl)
        .putBoolean(recordKey(metadata.recordId, KEY_IS_ADMINISTRATOR), metadata.isAdministrator)
        .putString(recordKey(metadata.recordId, KEY_ORIGIN), metadata.origin.name)
        .commit()

    private fun readMetadata(recordId: String): SessionMetadata? {
        val values = metadataPrefs.all
        val storedOrigin = values[recordKey(recordId, KEY_ORIGIN)] as? String ?: return null
        val origin = SessionOrigin.entries.firstOrNull { it.name == storedOrigin } ?: return null
        return SessionMetadata(
            recordId = recordId,
            serverId = values[recordKey(recordId, KEY_SERVER_ID)] as? String ?: return null,
            userId = values[recordKey(recordId, KEY_USER_ID)] as? String ?: return null,
            userName = values[recordKey(recordId, KEY_USER_NAME)] as? String ?: return null,
            serverUrl = values[recordKey(recordId, KEY_SERVER_URL)] as? String ?: return null,
            isAdministrator = values[recordKey(recordId, KEY_IS_ADMINISTRATOR)] as? Boolean ?: return null,
            origin = origin
        )
    }

    private fun readLegacyState(): LegacyState {
        val values = legacyPrefs.all
        if (values.isEmpty()) return LegacyState.Missing
        val token = values[LEGACY_KEY_TOKEN] as? String
        val serverId = values[KEY_SERVER_ID] as? String
        val userId = values[KEY_USER_ID] as? String
        val userName = values[KEY_USER_NAME] as? String
        val serverUrl = values[KEY_SERVER_URL] as? String
        if (
            token == null ||
            serverId == null ||
            userId == null ||
            userName == null ||
            serverUrl == null
        ) {
            return LegacyState.Invalid
        }
        val session = NativeSession(
            token = token,
            serverId = serverId,
            userId = userId,
            userName = userName,
            serverUrl = serverUrl,
            isAdministrator = values[KEY_IS_ADMINISTRATOR] as? Boolean ?: false
        )
        if (!session.isComplete()) return LegacyState.Invalid
        val origin = (values[KEY_ORIGIN] as? String)
            ?.let { stored -> SessionOrigin.entries.firstOrNull { it.name == stored } }
            ?: SessionOrigin.STORED
        return LegacyState.Complete(session, origin)
    }

    private fun additionalAuthenticatedData(metadata: SessionMetadata): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(STORAGE_FORMAT_VERSION)
            listOf(
                metadata.recordId,
                metadata.serverUrl,
                metadata.serverId,
                metadata.userId,
                metadata.userName,
                metadata.isAdministrator.toString(),
                metadata.origin.name
            ).forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size)
                output.write(bytes)
            }
        }
        return buffer.toByteArray()
    }

    private fun clearInvalidStateLocked(): SessionReadResult =
        if (clearLocked()) SessionReadResult.InvalidCleared else SessionReadResult.StorageFailure

    private fun clearLocked(): Boolean {
        // Arm a durable fail-closed tombstone before touching credentials. If any following
        // mutation fails, startup will retry this clear and must not restore the old session.
        runCatching {
            authGuardPrefs.edit().putBoolean(KEY_CLEAR_PENDING, true).commit()
        }
        runCatching { tokenCipher.armClearGuard() }
        val barrierArmed = clearBarrierStatus() == SecureClearGuardStatus.ARMED
        val authenticationCleared = clearAuthenticationMaterialLocked()
        val guardCleared = if (authenticationCleared) clearAuthGuardLocked() else false
        // Complete removal is independently safe. Otherwise at least one verified durable guard
        // must remain armed so a fresh process cannot restore surviving material.
        return authenticationCleared && guardCleared &&
            (barrierArmed || clearBarrierStatus() == SecureClearGuardStatus.DISARMED)
    }

    private fun finishPendingClearLocked(): Boolean {
        if (!clearAuthenticationMaterialLocked()) return false
        return clearAuthGuardLocked()
    }

    private fun clearAuthenticationMaterialLocked(): Boolean {
        // Do not short-circuit: every auth-state location must be attempted even if one write
        // reports an I/O failure.
        val keyDestroyed = runCatching { tokenCipher.destroy() }.getOrDefault(false)
        val credentialsCleared = runCatching { credentialPrefs.edit().clear().commit() }.getOrDefault(false)
        val metadataCleared = runCatching { metadataPrefs.edit().clear().commit() }.getOrDefault(false)
        val legacyCleared = runCatching { legacyPrefs.edit().clear().commit() }.getOrDefault(false)
        val verifiedEmpty = runCatching {
            credentialPrefs.all.isEmpty() &&
                metadataPrefs.all.isEmpty() &&
                legacyPrefs.all.isEmpty()
        }.getOrDefault(false)
        return keyDestroyed &&
            credentialsCleared &&
            metadataCleared &&
            legacyCleared &&
            verifiedEmpty
    }

    private fun clearAuthGuardLocked(): Boolean {
        val preferenceGuardCleared = runCatching {
            authGuardPrefs.edit().clear().commit() && authGuardPrefs.all.isEmpty()
        }.getOrDefault(false)
        val keyGuardCleared = runCatching { tokenCipher.disarmClearGuard() }.getOrDefault(false)
        return preferenceGuardCleared &&
            keyGuardCleared &&
            clearBarrierStatus() == SecureClearGuardStatus.DISARMED
    }

    private fun clearBarrierStatus(): SecureClearGuardStatus {
        val preferenceStatus = runCatching {
            if (authGuardPrefs.all.isNotEmpty()) {
                SecureClearGuardStatus.ARMED
            } else {
                SecureClearGuardStatus.DISARMED
            }
        }.getOrDefault(SecureClearGuardStatus.UNAVAILABLE)
        val keyStatus = runCatching { tokenCipher.clearGuardStatus() }
            .getOrDefault(SecureClearGuardStatus.UNAVAILABLE)
        return when {
            preferenceStatus == SecureClearGuardStatus.ARMED ||
                keyStatus == SecureClearGuardStatus.ARMED -> SecureClearGuardStatus.ARMED
            preferenceStatus == SecureClearGuardStatus.UNAVAILABLE ||
                keyStatus == SecureClearGuardStatus.UNAVAILABLE -> SecureClearGuardStatus.UNAVAILABLE
            else -> SecureClearGuardStatus.DISARMED
        }
    }

    private fun activeRecordId(): String? =
        metadataPrefs.all[KEY_ACTIVE_RECORD_ID] as? String

    private fun restoreActiveRecord(recordId: String?) {
        runCatching {
            metadataPrefs.edit().apply {
                if (recordId == null) remove(KEY_ACTIVE_RECORD_ID) else putString(KEY_ACTIVE_RECORD_ID, recordId)
            }.commit()
        }
    }

    private fun removeRecord(recordId: String) {
        removeKeysWithPrefix(metadataPrefs, "$recordId.")
        removeKeysWithPrefix(credentialPrefs, "$recordId.")
    }

    private fun cleanupInactiveRecords(activeRecordId: String) {
        removeKeysExceptActive(metadataPrefs, activeRecordId, preserveActivePointer = true)
        removeKeysExceptActive(credentialPrefs, activeRecordId, preserveActivePointer = false)
    }

    private fun removeKeysWithPrefix(prefs: SharedPreferences, prefix: String) {
        runCatching {
            val editor = prefs.edit()
            var changed = false
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach {
                editor.remove(it)
                changed = true
            }
            if (changed) editor.commit()
        }
    }

    private fun removeKeysExceptActive(
        prefs: SharedPreferences,
        activeRecordId: String,
        preserveActivePointer: Boolean
    ) {
        runCatching {
            val activePrefix = "$activeRecordId."
            val editor = prefs.edit()
            var changed = false
            prefs.all.keys.forEach { key ->
                val keep = key.startsWith(activePrefix) ||
                    (preserveActivePointer && key == KEY_ACTIVE_RECORD_ID)
                if (!keep) {
                    editor.remove(key)
                    changed = true
                }
            }
            if (changed) editor.commit()
        }
    }

    private fun recordKey(recordId: String, field: String) = "$recordId.$field"

    private fun EncryptedSessionToken.isStructurallyValid(): Boolean =
        initializationVector.size == GCM_INITIALIZATION_VECTOR_BYTES &&
            ciphertext.size > GCM_AUTHENTICATION_TAG_BYTES

    private data class SessionMetadata(
        val recordId: String,
        val serverId: String,
        val userId: String,
        val userName: String,
        val serverUrl: String,
        val isAdministrator: Boolean,
        val origin: SessionOrigin
    ) {
        fun toSession(token: String) = NativeSession(
            token = token,
            serverId = serverId,
            userId = userId,
            userName = userName,
            serverUrl = serverUrl,
            isAdministrator = isAdministrator
        )

        companion object {
            fun from(recordId: String, session: NativeSession, origin: SessionOrigin) =
                SessionMetadata(
                    recordId = recordId,
                    serverId = session.serverId,
                    userId = session.userId,
                    userName = session.userName,
                    serverUrl = session.serverUrl,
                    isAdministrator = session.isAdministrator,
                    origin = origin
                )
        }
    }

    private sealed interface SecureRecordRead {
        data class Available(
            val session: NativeSession,
            val origin: SessionOrigin
        ) : SecureRecordRead

        data object Corrupt : SecureRecordRead
        data object StorageFailure : SecureRecordRead
    }

    private sealed interface LegacyState {
        data class Complete(
            val session: NativeSession,
            val origin: SessionOrigin
        ) : LegacyState

        data object Missing : LegacyState
        data object Invalid : LegacyState

        fun originOrDefault(): SessionOrigin =
            (this as? Complete)?.origin ?: SessionOrigin.STORED
    }

    companion object {
        internal const val LEGACY_PREFS_NAME = "ptv_secure_session"
        internal const val METADATA_PREFS_NAME = "ptv_session_metadata"
        internal const val CREDENTIAL_PREFS_NAME = "ptv_secure_credentials"
        internal const val AUTH_GUARD_PREFS_NAME = "ptv_auth_guard"
        internal const val KEY_ACTIVE_RECORD_ID = "activeRecordId"
        internal const val KEY_SERVER_ID = "serverId"
        internal const val KEY_USER_ID = "userId"
        internal const val KEY_USER_NAME = "userName"
        internal const val KEY_SERVER_URL = "serverUrl"
        internal const val KEY_IS_ADMINISTRATOR = "isAdministrator"
        internal const val KEY_ORIGIN = "origin"
        internal const val KEY_CIPHERTEXT = "ciphertext"
        internal const val KEY_INITIALIZATION_VECTOR = "initializationVector"
        internal const val LEGACY_KEY_TOKEN = "token"

        internal const val KEY_CLEAR_PENDING = "clearPending"
        private const val KEY_FORMAT_VERSION = "formatVersion"
        private const val STORAGE_FORMAT_VERSION = 1
        private const val GCM_INITIALIZATION_VECTOR_BYTES = 12
        private const val GCM_AUTHENTICATION_TAG_BYTES = 16
        private const val KEY_ALIAS_SUFFIX = ".ptv.session.token.v1"
        private val PROCESS_LOCK = Any()

        @Volatile
        private var tokenCipherFactoryForTests: ((Context) -> SessionTokenCipher)? = null

        internal fun installTokenCipherFactoryForTests(
            factory: ((Context) -> SessionTokenCipher)?
        ) = synchronized(PROCESS_LOCK) {
            tokenCipherFactoryForTests = factory
        }

        private fun createTokenCipher(context: Context): SessionTokenCipher =
            tokenCipherFactoryForTests?.invoke(context)
                ?: AndroidKeystoreSessionTokenCipher(context.packageName + KEY_ALIAS_SUFFIX)
    }
}
