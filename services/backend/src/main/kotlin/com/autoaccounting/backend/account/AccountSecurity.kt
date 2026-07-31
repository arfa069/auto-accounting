package com.autoaccounting.backend.account

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal data class PasswordHash(
    val salt: String,
    val hash: String
) {
    fun matches(password: String): Boolean {
        return MessageDigest.isEqual(
            Base64.getDecoder().decode(hash),
            Base64.getDecoder().decode(hashPassword(password, salt))
        )
    }

    companion object {
        fun create(password: String): PasswordHash {
            val saltBytes = ByteArray(16)
            SecureRandom().nextBytes(saltBytes)
            val salt = Base64.getEncoder().encodeToString(saltBytes)
            return PasswordHash(
                salt = salt,
                hash = hashPassword(password, salt)
            )
        }
    }
}

class MutableClock(
    private var nowMillis: Long = Clock.systemUTC().millis()
) {
    fun millis(): Long = nowMillis

    fun advanceBy(millis: Long) {
        nowMillis += millis
    }
}

internal fun hashPassword(
    password: String,
    salt: String
): String {
    val spec = PBEKeySpec(
        password.toCharArray(),
        salt.toByteArray(),
        120_000,
        256
    )
    val bytes = SecretKeyFactory
        .getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(spec)
        .encoded
    return Base64.getEncoder().encodeToString(bytes)
}

internal fun secureToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun hashToken(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
    return Base64.getEncoder().encodeToString(digest)
}

internal fun isValidPhone(phone: String): Boolean = Regex("^\\d{11}$").matches(phone)

internal fun isValidVerificationCode(code: String): Boolean = Regex("^\\d{6}$").matches(code)

internal fun isValidPassword(password: String): Boolean {
    return password.length in 8..32 &&
        password.any(Char::isUpperCase) &&
        password.any(Char::isLowerCase) &&
        password.any(Char::isDigit) &&
        password.any { !it.isLetterOrDigit() }
}

internal fun isValidDeviceId(deviceId: String): Boolean {
    return deviceId.length <= 128 && deviceId.none(Char::isWhitespace)
}

internal fun AccountResult<AccountToken>.mapAccountToken(
    transform: (AccountToken) -> AccountToken
): AccountResult<AccountToken> = when (this) {
    is AccountResult.Success -> AccountResult.Success(transform(value))
    is AccountResult.Failure -> this
}

internal fun StoredWechatIdentity.matchesWechatIdentity(
    appId: String,
    openid: String,
    unionid: String?
): Boolean {
    return (this.appId == appId && this.openid == openid) ||
        (unionid != null && this.unionid == unionid)
}

class VerificationCodeHasher private constructor(
    secret: ByteArray
) {
    private val secretBytes = secret.copyOf()

    fun hash(identifierType: String, normalizedIdentifier: String, purpose: String, code: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
        return Base64.getEncoder().encodeToString(
            mac.doFinal("$identifierType:$normalizedIdentifier:$purpose:$code".toByteArray(Charsets.UTF_8))
        )
    }

    fun hash(normalizedIdentifier: String, code: String): String =
        hash("PHONE", normalizedIdentifier, "REGISTER", code)

    fun matches(
        identifierType: String,
        normalizedIdentifier: String,
        purpose: String,
        code: String,
        expectedHash: String
    ): Boolean {
        return runCatching {
            MessageDigest.isEqual(
                Base64.getDecoder().decode(expectedHash),
                Base64.getDecoder().decode(hash(identifierType, normalizedIdentifier, purpose, code))
            )
        }.getOrDefault(false)
    }

    fun matches(normalizedIdentifier: String, code: String, expectedHash: String): Boolean =
        matches("PHONE", normalizedIdentifier, "REGISTER", code, expectedHash)

    companion object {
        internal fun fromSecret(secret: String): VerificationCodeHasher {
            return VerificationCodeHasher(secret.toByteArray(Charsets.UTF_8))
        }

        fun random(): VerificationCodeHasher {
            return VerificationCodeHasher(
                ByteArray(32).also(SecureRandom()::nextBytes)
            )
        }

        fun forTests(
            secret: String = "account-test-verification-secret-32"
        ): VerificationCodeHasher = fromSecret(secret)
    }
}
