package com.autoaccounting.feature.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal sealed interface AccountSessionRestoreResult {
    data object Empty : AccountSessionRestoreResult
    data class Restored(val credentials: AccountCredentials) : AccountSessionRestoreResult
    data object Corrupted : AccountSessionRestoreResult
}

internal interface AccountSessionCipher {
    fun encrypt(plainText: ByteArray): ByteArray
    fun decrypt(cipherText: ByteArray): ByteArray
}

internal class SecureAccountSessionStore(
    context: Context,
    private val cipher: AccountSessionCipher = AndroidKeystoreAccountSessionCipher()
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        SECURE_SESSION_PREFERENCES,
        Context.MODE_PRIVATE
    )

    fun save(credentials: AccountCredentials): Boolean = runCatching {
        val encrypted = cipher.encrypt(credentials.encode())
        preferences.edit()
            .putString(ENCRYPTED_SESSION_KEY, encrypted.toBase64())
            .commit()
    }.getOrDefault(false)

    fun restore(): AccountSessionRestoreResult {
        val encoded = preferences.getString(ENCRYPTED_SESSION_KEY, null)
            ?: return AccountSessionRestoreResult.Empty
        return try {
            val credentials = cipher.decrypt(encoded.fromBase64()).decodeCredentials()
            AccountSessionRestoreResult.Restored(credentials)
        } catch (_: RuntimeException) {
            preferences.edit().remove(ENCRYPTED_SESSION_KEY).commit()
            AccountSessionRestoreResult.Corrupted
        } catch (_: java.security.GeneralSecurityException) {
            preferences.edit().remove(ENCRYPTED_SESSION_KEY).commit()
            AccountSessionRestoreResult.Corrupted
        }
    }

    fun clear(): Boolean = preferences.edit().remove(ENCRYPTED_SESSION_KEY).commit()

    private fun AccountCredentials.encode(): ByteArray {
        val phoneBytes = phone.toByteArray(Charsets.UTF_8)
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        require(phoneBytes.isNotEmpty() && tokenBytes.isNotEmpty())
        return ByteBuffer.allocate(1 + Int.SIZE_BYTES + phoneBytes.size + Int.SIZE_BYTES + tokenBytes.size)
            .put(SESSION_FORMAT_VERSION)
            .putInt(phoneBytes.size)
            .put(phoneBytes)
            .putInt(tokenBytes.size)
            .put(tokenBytes)
            .array()
    }

    private fun ByteArray.decodeCredentials(): AccountCredentials {
        val buffer = ByteBuffer.wrap(this)
        require(buffer.get() == SESSION_FORMAT_VERSION)
        val phone = buffer.readSizedUtf8()
        val token = buffer.readSizedUtf8()
        require(!buffer.hasRemaining() && phone.isNotBlank() && token.isNotBlank())
        return AccountCredentials(phone = phone, token = token)
    }

    private fun ByteBuffer.readSizedUtf8(): String {
        val size = int
        require(size in 1..remaining())
        return ByteArray(size).also(::get).toString(Charsets.UTF_8)
    }

    private companion object {
        const val SECURE_SESSION_PREFERENCES = "account_session_secure"
        const val ENCRYPTED_SESSION_KEY = "encrypted_session"
        const val SESSION_FORMAT_VERSION: Byte = 1
    }
}

internal class AndroidKeystoreAccountSessionCipher : AccountSessionCipher {
    override fun encrypt(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(AUTHENTICATED_DATA)
        val encrypted = cipher.doFinal(plainText)
        return ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
    }

    override fun decrypt(cipherText: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(cipherText)
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in 12..32 && buffer.remaining() > ivSize)
        val iv = ByteArray(ivSize).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(AUTHENTICATED_DATA)
        return cipher.doFinal(encrypted)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "auto_accounting_account_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val AUTHENTICATED_DATA = "auto-accounting-account-session-v1".toByteArray(Charsets.UTF_8)
    }
}

private fun ByteArray.toBase64(): String = android.util.Base64.encodeToString(
    this,
    android.util.Base64.NO_WRAP
)

private fun String.fromBase64(): ByteArray = android.util.Base64.decode(
    this,
    android.util.Base64.NO_WRAP
)
