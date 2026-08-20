package com.bks.data.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object PassphraseAesGcm {
    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val ITERATIONS = 120_000
    const val KEY_BITS = 256
    const val TAG_BITS = 128

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private val secureRandom = SecureRandom()

    fun encrypt(
        plainText: ByteArray,
        passphrase: CharArray,
        salt: ByteArray = ByteArray(SALT_BYTES).also(secureRandom::nextBytes),
        iv: ByteArray = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "Passphrase is required" }
        require(salt.size == SALT_BYTES) { "Invalid salt" }
        require(iv.size == IV_BYTES) { "Invalid IV" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            keyFromPassphrase(passphrase, salt),
            GCMParameterSpec(TAG_BITS, iv)
        )
        return salt + iv + cipher.doFinal(plainText)
    }

    fun decrypt(payload: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "Passphrase is required" }
        require(payload.size > SALT_BYTES + IV_BYTES) { "Invalid encrypted payload" }
        val salt = payload.copyOfRange(0, SALT_BYTES)
        val iv = payload.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val encrypted = payload.copyOfRange(SALT_BYTES + IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyFromPassphrase(passphrase, salt),
            GCMParameterSpec(TAG_BITS, iv)
        )
        return cipher.doFinal(encrypted)
    }

    private fun keyFromPassphrase(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        return try {
            val encoded = SecretKeyFactory.getInstance(KDF_ALGORITHM)
                .generateSecret(spec)
                .encoded
            try {
                SecretKeySpec(encoded, "AES")
            } finally {
                encoded.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }
}
