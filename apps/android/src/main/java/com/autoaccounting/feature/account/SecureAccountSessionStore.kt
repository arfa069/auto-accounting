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

    fun save(credentials: AccountCredentials): Boolean {
        val saved = runCatching {
            val encrypted = cipher.encrypt(credentials.encode())
            preferences.edit()
                .putString(ENCRYPTED_SESSION_KEY, encrypted.toBase64())
                .commit()
        }.getOrDefault(false)
        if (!saved) preferences.edit().remove(ENCRYPTED_SESSION_KEY).commit()
        return saved
    }

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
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        val nicknameBytes = nickname?.toByteArray(Charsets.UTF_8)
        val avatarUrlBytes = avatarUrl?.toByteArray(Charsets.UTF_8)
        val rawPhoneBytes = rawPhone?.toByteArray(Charsets.UTF_8)
        val accountUuidBytes = accountUuid?.toByteArray(Charsets.UTF_8)
        require(tokenBytes.isNotEmpty())

        val primaryTypeBytes = primaryIdentifier?.type?.name?.toByteArray(Charsets.UTF_8)
        val primaryValBytes = primaryIdentifier?.value?.toByteArray(Charsets.UTF_8)

        val identsData = identifiers.map { id ->
            val tBytes = id.type.name.toByteArray(Charsets.UTF_8)
            val vBytes = id.value.toByteArray(Charsets.UTF_8)
            Triple(tBytes, vBytes, id.verified)
        }

        var identsSize = Int.SIZE_BYTES
        for ((t, v, _) in identsData) {
            identsSize += t.encodedSize() + v.encodedSize() + 1
        }

        val totalSize = 1 +
            Long.SIZE_BYTES +
            accountUuidBytes.encodedSize() +
            primaryTypeBytes.encodedSize() +
            primaryValBytes.encodedSize() +
            identsSize +
            rawPhoneBytes.encodedSize() +
            tokenBytes.encodedSize() +
            1 +
            nicknameBytes.encodedSize() +
            avatarUrlBytes.encodedSize() +
            Long.SIZE_BYTES * 2

        val buf = ByteBuffer.allocate(totalSize)
            .put(SESSION_FORMAT_VERSION_V5)
            .putLong(accountId ?: NULL_LONG_FIELD)
            .putNullableUtf8(accountUuidBytes)
            .putNullableUtf8(primaryTypeBytes)
            .putNullableUtf8(primaryValBytes)

        buf.putInt(identsData.size)
        for ((t, v, ver) in identsData) {
            buf.putSizedUtf8(t)
            buf.putSizedUtf8(v)
            buf.put(if (ver) 1.toByte() else 0.toByte())
        }

        buf.putNullableUtf8(rawPhoneBytes)
        buf.putSizedUtf8(tokenBytes)
        buf.put(if (wechatLinked) 1.toByte() else 0.toByte())
        buf.putNullableUtf8(nicknameBytes)
        buf.putNullableUtf8(avatarUrlBytes)
        buf.putLong(deletionState.requestedAtEpochMillis ?: NULL_LONG_FIELD)
        buf.putLong(deletionState.finalDeletionAtEpochMillis ?: NULL_LONG_FIELD)

        return buf.array()
    }

    private fun ByteArray.decodeCredentials(): AccountCredentials {
        val buffer = ByteBuffer.wrap(this)
        return when (buffer.get()) {
            SESSION_FORMAT_VERSION_V1 -> buffer.decodeVersionOne()
            SESSION_FORMAT_VERSION_V2 -> buffer.decodeVersionTwo()
            SESSION_FORMAT_VERSION_V3 -> buffer.decodeVersionThree()
            SESSION_FORMAT_VERSION_V4 -> buffer.decodeVersionFour()
            SESSION_FORMAT_VERSION_V5 -> buffer.decodeVersionFive()
            else -> error("Unsupported account session format")
        }
    }

    private fun ByteBuffer.decodeVersionOne(): AccountCredentials {
        val phone = readSizedUtf8()
        val token = readSizedUtf8()
        require(!hasRemaining() && phone.isNotBlank() && token.isNotBlank())
        return AccountCredentials(phone = phone, token = token)
    }

    private fun ByteBuffer.decodeVersionTwo(): AccountCredentials {
        val phone = readNullableSizedUtf8()
        val token = readSizedUtf8()
        val wechatLinked = get().toInt() != 0
        val nickname = readNullableSizedUtf8()
        val avatarUrl = readNullableSizedUtf8()
        require(!hasRemaining() && token.isNotBlank())
        require(phone?.isNotBlank() == true || wechatLinked)
        return AccountCredentials(
            phone = phone,
            token = token,
            wechatLinked = wechatLinked,
            nickname = nickname,
            avatarUrl = avatarUrl
        )
    }

    private fun ByteBuffer.decodeVersionThree(): AccountCredentials =
        decodeVersionThreeToFive(
            accountId = null,
            accountUuid = null,
            includesDeletionState = false
        )

    private fun ByteBuffer.decodeVersionFour(): AccountCredentials =
        decodeVersionThreeToFive(
            accountId = getLong().takeUnless { it == NULL_LONG_FIELD },
            accountUuid = null,
            includesDeletionState = true
        )

    private fun ByteBuffer.decodeVersionFive(): AccountCredentials =
        decodeVersionThreeToFive(
            accountId = getLong().takeUnless { it == NULL_LONG_FIELD },
            accountUuid = readNullableSizedUtf8(),
            includesDeletionState = true
        )

    private fun ByteBuffer.decodeVersionThreeToFive(
        accountId: Long?,
        accountUuid: String?,
        includesDeletionState: Boolean
    ): AccountCredentials {
        val primaryTypeStr = readNullableSizedUtf8()
        val primaryValStr = readNullableSizedUtf8()
        val primaryIdentifier = if (primaryTypeStr != null && primaryValStr != null) {
            com.autoaccounting.api.AccountIdentifierContract(
                type = com.autoaccounting.api.AccountIdentifierTypeContract.valueOf(primaryTypeStr),
                value = primaryValStr
            )
        } else null

        val identsCount = int
        require(identsCount in 0..100)
        val idents = ArrayList<com.autoaccounting.api.AccountIdentifierContract>(identsCount)
        repeat(identsCount) {
            val tStr = readSizedUtf8()
            val vStr = readSizedUtf8()
            val verified = get().toInt() != 0
            idents.add(
                com.autoaccounting.api.AccountIdentifierContract(
                    type = com.autoaccounting.api.AccountIdentifierTypeContract.valueOf(tStr),
                    value = vStr,
                    verified = verified
                )
            )
        }

        val rawPhone = readNullableSizedUtf8()
        val token = readSizedUtf8()
        val wechatLinked = get().toInt() != 0
        val nickname = readNullableSizedUtf8()
        val avatarUrl = readNullableSizedUtf8()
        val deletionState = if (includesDeletionState) {
            AccountDeletionUiState(
                requestedAtEpochMillis = getLong().takeUnless { it == NULL_LONG_FIELD },
                finalDeletionAtEpochMillis = getLong().takeUnless { it == NULL_LONG_FIELD }
            )
        } else {
            AccountDeletionUiState()
        }
        require(!hasRemaining() && token.isNotBlank())

        return AccountCredentials(
            accountId = accountId,
            accountUuid = accountUuid,
            primaryIdentifier = primaryIdentifier,
            identifiers = idents,
            rawPhone = rawPhone,
            token = token,
            deletionState = deletionState,
            wechatLinked = wechatLinked,
            nickname = nickname,
            avatarUrl = avatarUrl
        )
    }

    private fun ByteBuffer.readSizedUtf8(): String {
        val size = int
        require(size in 1..remaining())
        return ByteArray(size).also(::get).toString(Charsets.UTF_8)
    }

    private fun ByteBuffer.readNullableSizedUtf8(): String? {
        val size = int
        if (size == NULL_FIELD_SIZE) return null
        require(size in 0..remaining())
        return ByteArray(size).also(::get).toString(Charsets.UTF_8)
    }

    private fun ByteBuffer.putSizedUtf8(value: ByteArray): ByteBuffer = putInt(value.size).put(value)

    private fun ByteBuffer.putNullableUtf8(value: ByteArray?): ByteBuffer {
        return if (value == null) putInt(NULL_FIELD_SIZE) else putSizedUtf8(value)
    }

    private companion object {
        const val SECURE_SESSION_PREFERENCES = "account_session_secure"
        const val ENCRYPTED_SESSION_KEY = "encrypted_session"
        const val SESSION_FORMAT_VERSION_V1: Byte = 1
        const val SESSION_FORMAT_VERSION_V2: Byte = 2
        const val SESSION_FORMAT_VERSION_V3: Byte = 3
        const val SESSION_FORMAT_VERSION_V4: Byte = 4
        const val SESSION_FORMAT_VERSION_V5: Byte = 5
        const val NULL_FIELD_SIZE = -1
        const val NULL_LONG_FIELD = Long.MIN_VALUE
    }
}

private fun ByteArray?.encodedSize(): Int = Int.SIZE_BYTES + (this?.size ?: 0)

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
