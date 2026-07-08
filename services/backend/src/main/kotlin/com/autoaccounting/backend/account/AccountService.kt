package com.autoaccounting.backend.account

import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class AccountError(
    val message: String
) {
    PHONE_ALREADY_REGISTERED("该手机号已注册，请直接登录"),
    PHONE_NOT_REGISTERED("该手机号尚未注册，请先创建账号"),
    VERIFICATION_CODE_WRONG("验证码不正确，请重新输入"),
    VERIFICATION_CODE_EXPIRED("验证码已过期，请重新获取"),
    SMS_TOO_FREQUENT("获取太频繁，请稍后再试"),
    LOGIN_FAILED("手机号或密码不正确"),
    ACCOUNT_LOCKED("尝试次数过多，请稍后再试，或使用短信找回密码"),
    ACCOUNT_DELETION_PENDING("账号注销冷静期内，云端写入已暂停"),
    ACCOUNT_DELETION_NOT_PENDING("账号当前没有注销申请")
}

sealed interface AccountResult<out T> {
    data class Success<T>(val value: T) : AccountResult<T>
    data class Failure(val error: AccountError) : AccountResult<Nothing>
}

val AccountResult<*>.error: AccountError?
    get() = (this as? AccountResult.Failure)?.error

data class AccountToken(
    val phone: String,
    val token: String
)

data class AccountDeletionStatus(
    val phone: String,
    val requestedAtMillis: Long,
    val finalDeletionAtMillis: Long
)

class AccountService(
    private val smsCodeGenerator: () -> String = { "%06d".format(SecureRandom().nextInt(1_000_000)) },
    private val tokenGenerator: () -> String = { secureToken() },
    private val clock: MutableClock = MutableClock()
) {
    private val users = mutableMapOf<String, UserRecord>()
    private val smsCodes = mutableMapOf<String, SmsCodeRecord>()
    private val smsIssueTimes = mutableMapOf<String, Long>()

    fun issueSmsCode(
        phone: String,
        deviceId: String,
        ipAddress: String
    ): AccountResult<Unit> {
        val now = clock.millis()
        val isRateLimited = listOf(
            "phone:$phone",
            "device:$deviceId",
            "ip:$ipAddress"
        ).any { key ->
            val lastIssuedAt = smsIssueTimes[key]
            lastIssuedAt != null && now - lastIssuedAt < SMS_RATE_LIMIT_MILLIS
        }
        if (isRateLimited) {
            return AccountResult.Failure(AccountError.SMS_TOO_FREQUENT)
        }

        smsCodes[phone] = SmsCodeRecord(
            code = smsCodeGenerator(),
            expiresAtMillis = now + SMS_CODE_TTL_MILLIS
        )
        smsIssueTimes["phone:$phone"] = now
        smsIssueTimes["device:$deviceId"] = now
        smsIssueTimes["ip:$ipAddress"] = now
        return AccountResult.Success(Unit)
    }

    fun register(
        phone: String,
        code: String,
        password: String
    ): AccountResult<AccountToken> {
        if (users.containsKey(phone)) {
            return AccountResult.Failure(AccountError.PHONE_ALREADY_REGISTERED)
        }
        val verification = verifySmsCode(phone, code)
        if (verification is AccountResult.Failure) return verification

        users[phone] = UserRecord(
            phone = phone,
            passwordHash = PasswordHash.create(password)
        )
        return AccountResult.Success(AccountToken(phone = phone, token = tokenGenerator()))
    }

    fun login(
        phone: String,
        password: String
    ): AccountResult<AccountToken> {
        val user = users[phone] ?: return AccountResult.Failure(AccountError.LOGIN_FAILED)
        val now = clock.millis()
        if (user.lockedUntilMillis > now) {
            return AccountResult.Failure(AccountError.ACCOUNT_LOCKED)
        }

        if (!user.passwordHash.matches(password)) {
            user.failedLoginCount += 1
            if (user.failedLoginCount >= MAX_LOGIN_FAILURES) {
                user.lockedUntilMillis = now + LOGIN_LOCK_MILLIS
                return AccountResult.Failure(AccountError.ACCOUNT_LOCKED)
            }
            return AccountResult.Failure(AccountError.LOGIN_FAILED)
        }

        user.failedLoginCount = 0
        return AccountResult.Success(AccountToken(phone = phone, token = tokenGenerator()))
    }

    fun recoverPassword(
        phone: String,
        code: String,
        newPassword: String
    ): AccountResult<AccountToken> {
        val user = users[phone] ?: return AccountResult.Failure(AccountError.PHONE_NOT_REGISTERED)
        val verification = verifySmsCode(phone, code)
        if (verification is AccountResult.Failure) return verification

        user.passwordHash = PasswordHash.create(newPassword)
        user.failedLoginCount = 0
        user.lockedUntilMillis = 0
        return AccountResult.Success(AccountToken(phone = phone, token = tokenGenerator()))
    }

    fun requestAccountDeletion(phone: String): AccountResult<AccountDeletionStatus> {
        val user = users[phone] ?: return AccountResult.Failure(AccountError.PHONE_NOT_REGISTERED)
        val requestedAt = user.deletionRequestedAtMillis ?: clock.millis()
        user.deletionRequestedAtMillis = requestedAt
        return AccountResult.Success(user.deletionStatus(requestedAt))
    }

    fun getAccountDeletionStatus(phone: String): AccountResult<AccountDeletionStatus> {
        val user = users[phone] ?: return AccountResult.Failure(AccountError.PHONE_NOT_REGISTERED)
        val requestedAt = user.deletionRequestedAtMillis
            ?: return AccountResult.Failure(AccountError.ACCOUNT_DELETION_NOT_PENDING)
        return AccountResult.Success(user.deletionStatus(requestedAt))
    }

    fun cancelAccountDeletion(phone: String): AccountResult<Unit> {
        val user = users[phone] ?: return AccountResult.Failure(AccountError.PHONE_NOT_REGISTERED)
        if (user.deletionRequestedAtMillis == null) {
            return AccountResult.Failure(AccountError.ACCOUNT_DELETION_NOT_PENDING)
        }
        user.deletionRequestedAtMillis = null
        return AccountResult.Success(Unit)
    }

    fun writeCloudConfiguration(phone: String): AccountResult<Unit> {
        val user = users[phone] ?: return AccountResult.Failure(AccountError.PHONE_NOT_REGISTERED)
        if (user.deletionRequestedAtMillis != null) {
            return AccountResult.Failure(AccountError.ACCOUNT_DELETION_PENDING)
        }
        return AccountResult.Success(Unit)
    }

    fun canWriteCloudData(phone: String): Boolean {
        val user = users[phone] ?: return false
        return user.deletionRequestedAtMillis == null
    }

    fun deleteDueAccounts(): List<String> {
        val now = clock.millis()
        val duePhones = users.values
            .filter { user ->
                val requestedAt = user.deletionRequestedAtMillis
                requestedAt != null && now >= requestedAt + ACCOUNT_DELETION_COOLING_OFF_MILLIS
            }
            .map { it.phone }
        duePhones.forEach(users::remove)
        return duePhones
    }

    fun advanceTimeBy(millis: Long) {
        clock.advanceBy(millis)
    }

    private fun verifySmsCode(
        phone: String,
        code: String
    ): AccountResult<Unit> {
        val record = smsCodes[phone] ?: return AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG)
        if (clock.millis() > record.expiresAtMillis) {
            return AccountResult.Failure(AccountError.VERIFICATION_CODE_EXPIRED)
        }
        if (record.code != code) {
            return AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG)
        }
        return AccountResult.Success(Unit)
    }

    private data class SmsCodeRecord(
        val code: String,
        val expiresAtMillis: Long
    )

    private data class UserRecord(
        val phone: String,
        var passwordHash: PasswordHash,
        var failedLoginCount: Int = 0,
        var lockedUntilMillis: Long = 0,
        var deletionRequestedAtMillis: Long? = null
    ) {
        fun deletionStatus(requestedAtMillis: Long): AccountDeletionStatus = AccountDeletionStatus(
            phone = phone,
            requestedAtMillis = requestedAtMillis,
            finalDeletionAtMillis = requestedAtMillis + ACCOUNT_DELETION_COOLING_OFF_MILLIS
        )
    }

    companion object {
        private const val SMS_RATE_LIMIT_MILLIS = 60_000L
        private const val SMS_CODE_TTL_MILLIS = 5 * 60_000L
        private const val MAX_LOGIN_FAILURES = 5
        private const val LOGIN_LOCK_MILLIS = 15 * 60_000L
        const val ACCOUNT_DELETION_COOLING_OFF_MILLIS = 7 * 24 * 60 * 60 * 1_000L
    }
}

private data class PasswordHash(
    val salt: String,
    val hash: String
) {
    fun matches(password: String): Boolean {
        return hash == hashPassword(password, salt)
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

private fun hashPassword(
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

private fun secureToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
