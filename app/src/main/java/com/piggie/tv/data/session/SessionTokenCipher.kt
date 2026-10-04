package com.piggie.tv.data.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class EncryptedSessionToken(
    val ciphertext: ByteArray,
    val initializationVector: ByteArray
)

internal enum class SecureClearGuardStatus { ARMED, DISARMED, UNAVAILABLE }

internal interface SessionTokenCipher {
    fun encrypt(token: ByteArray, additionalAuthenticatedData: ByteArray): EncryptedSessionToken

    fun decrypt(
        encryptedToken: EncryptedSessionToken,
        additionalAuthenticatedData: ByteArray
    ): ByteArray

    /** Removes the non-exportable key so residual ciphertext cannot survive logout. */
    fun destroy(): Boolean = true

    /** Independent durable marker used when preference writes are unavailable during logout. */
    fun armClearGuard(): Boolean = true
    fun clearGuardStatus(): SecureClearGuardStatus = SecureClearGuardStatus.DISARMED
    fun disarmClearGuard(): Boolean = true
}

internal class SessionTokenCorruptException(cause: Throwable? = null) : Exception(cause)

internal class SessionTokenStorageException(cause: Throwable? = null) : Exception(cause)

/**
 * Keeps the token encryption key non-exportable in Android Keystore. The encrypted token itself
 * can safely live in private preferences because it is authenticated AES-GCM ciphertext.
 */
internal class AndroidKeystoreSessionTokenCipher(
    private val keyAlias: String
) : SessionTokenCipher {
    override fun encrypt(
        token: ByteArray,
        additionalAuthenticatedData: ByteArray
    ): EncryptedSessionToken = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(additionalAuthenticatedData)
        EncryptedSessionToken(
            ciphertext = cipher.doFinal(token),
            initializationVector = cipher.iv
        )
    } catch (error: GeneralSecurityException) {
        throw SessionTokenStorageException(error)
    }

    override fun decrypt(
        encryptedToken: EncryptedSessionToken,
        additionalAuthenticatedData: ByteArray
    ): ByteArray {
        val key = try {
            existingKey() ?: throw SessionTokenCorruptException()
        } catch (error: SessionTokenCorruptException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw SessionTokenStorageException(error)
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, encryptedToken.initializationVector)
            )
            cipher.updateAAD(additionalAuthenticatedData)
            cipher.doFinal(encryptedToken.ciphertext)
        } catch (error: AEADBadTagException) {
            throw SessionTokenCorruptException(error)
        } catch (error: KeyPermanentlyInvalidatedException) {
            throw SessionTokenCorruptException(error)
        } catch (error: GeneralSecurityException) {
            throw SessionTokenStorageException(error)
        }
    }

    override fun destroy(): Boolean = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        !keyStore.containsAlias(keyAlias)
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: java.io.IOException) {
        false
    }

    override fun armClearGuard(): Boolean = runCatching {
        getOrCreateKey(clearGuardAlias)
        clearGuardStatus() == SecureClearGuardStatus.ARMED
    }.getOrDefault(false)

    override fun clearGuardStatus(): SecureClearGuardStatus = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(clearGuardAlias)) {
            SecureClearGuardStatus.ARMED
        } else {
            SecureClearGuardStatus.DISARMED
        }
    } catch (_: Throwable) {
        SecureClearGuardStatus.UNAVAILABLE
    }

    override fun disarmClearGuard(): Boolean = runCatching {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(clearGuardAlias)) keyStore.deleteEntry(clearGuardAlias)
        !keyStore.containsAlias(clearGuardAlias)
    }.getOrDefault(false)

    private fun getOrCreateKey(alias: String = keyAlias): SecretKey {
        existingKey(alias)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(KEY_SIZE_BITS)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
            }
            .generateKey()
    }

    private fun existingKey(alias: String = keyAlias): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (!keyStore.containsAlias(alias)) return null
        return keyStore.getKey(alias, null) as? SecretKey
            ?: throw SessionTokenCorruptException()
    }

    private val clearGuardAlias: String
        get() = "$keyAlias.clear-pending"

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
