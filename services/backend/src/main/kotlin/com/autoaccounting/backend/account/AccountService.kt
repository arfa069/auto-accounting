package com.autoaccounting.backend.account

import java.security.SecureRandom
import java.time.Clock
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
    SMS_PROVIDER_UNCONFIGURED("短信服务未配置"),
    SMS_SEND_FAILED("验证码发送失败，请稍后重试"),
    LOGIN_FAILED("手机号或密码不正确"),
    TOKEN_INVALID("登录状态已失效，请重新登录"),
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
    private val store: AccountStore = InMemoryAccountStore(),
    private val smsProvider: SmsProvider = NoopSmsProvider,
    private val smsCodeGenerator: () -> String = { "%06d".format(SecureRandom().nextInt(1_000_000)) },
    private val tokenGenerator: () -> String = { secureToken() },
    private val clock: MutableClock = MutableClock()
) {
    fun issueSmsCode(
        phone: String,
        deviceId: String,
        ipAddress: String
    ): AccountResult<Unit> {
        val now = clock.millis()
        if (isSmsRateLimited(phone, deviceId, ipAddress, now)) {
            return AccountResult.Failure(AccountError.SMS_TOO_FREQUENT)
        }

        val code = smsCodeGenerator()
        when (val sendResult = smsProvider.sendCode(phone, code)) {
            SmsProviderResult.Sent -> Unit
            is SmsProviderResult.Failed -> return AccountResult.Failure(sendResult.error)
        }

        store.upsertSmsCode(
            StoredSmsCode(
                phone = phone,
                code = code,
                expiresAtMillis = now + SMS_CODE_TTL_MILLIS,
                deviceId = deviceId,
                ipAddress = ipAddress
            )
        )
        smsRateLimitScopes(phone, deviceId, ipAddress).forEach { (scopeType, scopeValue) ->
            store.recordSmsIssue(scopeType, scopeValue, now)
        }
        return AccountResult.Success(Unit)
    }

    fun register(
        phone: String,
        code: String,
        password: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        if (store.findUser(phone) != null) {
            return AccountResult.Failure(AccountError.PHONE_ALREADY_REGISTERED)
        }
        val smsCode = when (val verification = verifySmsCode(phone, code)) {
            is AccountResult.Failure -> return verification
            is AccountResult.Success -> verification.value
        }

        val now = clock.millis()
        val passwordHash = PasswordHash.create(password)
        val created = store.createUser(
            StoredUser(
                phone = phone,
                passwordSalt = passwordHash.salt,
                passwordHash = passwordHash.hash,
                createdAtMillis = now
            )
        )
        if (!created) {
            return AccountResult.Failure(AccountError.PHONE_ALREADY_REGISTERED)
        }
        store.deleteSmsCode(phone)
        registerDeviceFromSms(phone, smsCode, deviceId, ipAddress, now)
        return issueSession(phone, deviceId.ifBlank { smsCode.deviceId }, now)
    }

    fun login(
        phone: String,
        password: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        val user = store.findUser(phone) ?: return AccountResult.Failure(AccountError.LOGIN_FAILED)
        val now = clock.millis()
        if (user.lockedUntilMillis > now) {
            return AccountResult.Failure(AccountError.ACCOUNT_LOCKED)
        }

        if (!user.passwordHash().matches(password)) {
            val failedLoginCount = user.failedLoginCount + 1
            val lockedUntilMillis = if (failedLoginCount >= MAX_LOGIN_FAILURES) {
                now + LOGIN_LOCK_MILLIS
            } else {
                user.lockedUntilMillis
            }
            store.updateUser(
                user.copy(
                    failedLoginCount = failedLoginCount,
                    lockedUntilMillis = lockedUntilMillis
                )
            )
            return if (failedLoginCount >= MAX_LOGIN_FAILURES) {
                AccountResult.Failure(AccountError.ACCOUNT_LOCKED)
            } else {
                AccountResult.Failure(AccountError.LOGIN_FAILED)
            }
        }

        store.updateUser(user.copy(failedLoginCount = 0, lockedUntilMillis = 0))
        registerDevice(phone, deviceId, ipAddress, now)
        return issueSession(phone, deviceId, now)
    }

    fun recoverPassword(
        phone: String,
        code: String,
        newPassword: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        val user = store.findUser(phone) ?: return AccountResult.Failure(AccountError.PHONE_NOT_REGISTERED)
        val smsCode = when (val verification = verifySmsCode(phone, code)) {
            is AccountResult.Failure -> return verification
            is AccountResult.Success -> verification.value
        }

        val passwordHash = PasswordHash.create(newPassword)
        store.updateUser(
            user.copy(
                passwordSalt = passwordHash.salt,
                passwordHash = passwordHash.hash,
                failedLoginCount = 0,
                lockedUntilMillis = 0
            )
        )
        store.deleteSmsCode(phone)
        val now = clock.millis()
        registerDeviceFromSms(phone, smsCode, deviceId, ipAddress, now)
        return issueSession(phone, deviceId.ifBlank { smsCode.deviceId }, now)
    }

    fun verifyToken(token: String): AccountResult<AccountToken> {
        val session = store.findSession(token) ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val user = store.findUser(session.phone) ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        return AccountResult.Success(AccountToken(phone = user.phone, token = token))
    }

    fun registeredDevices(phone: String): List<StoredRegisteredDevice> {
        return store.registeredDevices(phone)
    }

    fun requestAccountDeletion(phone: String): AccountResult<AccountDeletionStatus> {
        val user = store.findUser(phone) ?: return AccountResult.Failure(AccountError.PHONE_NOT_REGISTERED)
        val requestedAt = user.deletionRequestedAtMillis ?: clock.millis()
        val updated = user.copy(deletionRequestedAtMillis = requestedAt)
        store.updateUser(updated)
        return AccountResult.Success(updated.deletionStatus(requestedAt))
    }

    fun getAccountDeletionStatus(phone: String): AccountResult<AccountDeletionStatus> {
        val user = store.findUser(phone) ?: return AccountResult.Failure(AccountError.PHONE_NOT_REGISTERED)
        val requestedAt = user.deletionRequestedAtMillis
            ?: return AccountResult.Failure(AccountError.ACCOUNT_DELETION_NOT_PENDING)
        return AccountResult.Success(user.deletionStatus(requestedAt))
    }

    fun cancelAccountDeletion(phone: String): AccountResult<Unit> {
        val user = store.findUser(phone) ?: return AccountResult.Failure(AccountError.PHONE_NOT_REGISTERED)
        if (user.deletionRequestedAtMillis == null) {
            return AccountResult.Failure(AccountError.ACCOUNT_DELETION_NOT_PENDING)
        }
        store.updateUser(user.copy(deletionRequestedAtMillis = null))
        return AccountResult.Success(Unit)
    }

    fun writeCloudConfiguration(phone: String): AccountResult<Unit> {
        val user = store.findUser(phone) ?: return AccountResult.Failure(AccountError.PHONE_NOT_REGISTERED)
        if (user.deletionRequestedAtMillis != null) {
            return AccountResult.Failure(AccountError.ACCOUNT_DELETION_PENDING)
        }
        return AccountResult.Success(Unit)
    }

    fun canWriteCloudData(phone: String): Boolean {
        val user = store.findUser(phone) ?: return false
        return user.deletionRequestedAtMillis == null
    }

    fun deleteDueAccounts(): List<String> {
        val now = clock.millis()
        val duePhones = store.usersPendingDeletion()
            .filter { user ->
                val requestedAt = user.deletionRequestedAtMillis
                requestedAt != null && now >= requestedAt + ACCOUNT_DELETION_COOLING_OFF_MILLIS
            }
            .map { it.phone }
        duePhones.forEach { phone ->
            store.deleteSessionsForPhone(phone)
            store.deleteUser(phone)
        }
        return duePhones
    }

    fun advanceTimeBy(millis: Long) {
        clock.advanceBy(millis)
    }

    private fun verifySmsCode(
        phone: String,
        code: String
    ): AccountResult<StoredSmsCode> {
        val record = store.findSmsCode(phone)
            ?: return AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG)
        if (record.invalidated) {
            return AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG)
        }
        if (clock.millis() > record.expiresAtMillis) {
            store.updateSmsCode(record.copy(invalidated = true))
            return AccountResult.Failure(AccountError.VERIFICATION_CODE_EXPIRED)
        }
        if (record.code != code) {
            val failedAttempts = record.failedAttempts + 1
            store.updateSmsCode(
                record.copy(
                    failedAttempts = failedAttempts,
                    invalidated = failedAttempts >= MAX_SMS_CODE_FAILURES
                )
            )
            return AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG)
        }
        return AccountResult.Success(record)
    }

    private fun isSmsRateLimited(
        phone: String,
        deviceId: String,
        ipAddress: String,
        now: Long
    ): Boolean {
        return smsRateLimitScopes(phone, deviceId, ipAddress).any { (scopeType, scopeValue) ->
            val lastIssuedAt = store.latestSmsIssueMillis(scopeType, scopeValue)
            val minuteLimited = scopeType == SMS_SCOPE_PHONE &&
                lastIssuedAt != null &&
                now - lastIssuedAt < SMS_RATE_LIMIT_MILLIS
            val hourLimited = store.countSmsIssues(scopeType, scopeValue, now - SMS_HOUR_MILLIS) >= 5
            val dayLimited = store.countSmsIssues(scopeType, scopeValue, now - SMS_DAY_MILLIS) >= 10
            minuteLimited || hourLimited || dayLimited
        }
    }

    private fun smsRateLimitScopes(
        phone: String,
        deviceId: String,
        ipAddress: String
    ): List<Pair<String, String>> {
        return buildList {
            add(SMS_SCOPE_PHONE to phone)
            if (deviceId.isNotBlank()) add(SMS_SCOPE_DEVICE to deviceId)
            if (ipAddress.isNotBlank()) add(SMS_SCOPE_IP to ipAddress)
        }
    }

    private fun registerDeviceFromSms(
        phone: String,
        smsCode: StoredSmsCode,
        requestedDeviceId: String,
        requestedIpAddress: String,
        now: Long
    ) {
        registerDevice(
            phone = phone,
            deviceId = requestedDeviceId.ifBlank { smsCode.deviceId },
            ipAddress = requestedIpAddress.ifBlank { smsCode.ipAddress },
            now = now
        )
    }

    private fun registerDevice(
        phone: String,
        deviceId: String,
        ipAddress: String,
        now: Long
    ) {
        if (deviceId.isBlank()) return
        if (!canWriteCloudData(phone)) return
        store.upsertRegisteredDevice(
            StoredRegisteredDevice(
                phone = phone,
                deviceId = deviceId,
                firstSeenAtMillis = now,
                lastSeenAtMillis = now,
                ipAddress = ipAddress
            )
        )
    }

    private fun issueSession(
        phone: String,
        deviceId: String,
        now: Long
    ): AccountResult<AccountToken> {
        val token = tokenGenerator()
        store.createSession(
            StoredSession(
                token = token,
                phone = phone,
                deviceId = deviceId,
                issuedAtMillis = now
            )
        )
        return AccountResult.Success(AccountToken(phone = phone, token = token))
    }

    private fun StoredUser.deletionStatus(requestedAtMillis: Long): AccountDeletionStatus {
        return AccountDeletionStatus(
            phone = phone,
            requestedAtMillis = requestedAtMillis,
            finalDeletionAtMillis = requestedAtMillis + ACCOUNT_DELETION_COOLING_OFF_MILLIS
        )
    }

    private fun StoredUser.passwordHash(): PasswordHash {
        return PasswordHash(passwordSalt, passwordHash)
    }

    companion object {
        private const val SMS_SCOPE_PHONE = "phone"
        private const val SMS_SCOPE_DEVICE = "device"
        private const val SMS_SCOPE_IP = "ip"
        private const val SMS_RATE_LIMIT_MILLIS = 60_000L
        private const val SMS_HOUR_MILLIS = 60 * 60_000L
        private const val SMS_DAY_MILLIS = 24 * 60 * 60_000L
        private const val SMS_CODE_TTL_MILLIS = 5 * 60_000L
        private const val MAX_SMS_CODE_FAILURES = 3
        private const val MAX_LOGIN_FAILURES = 5
        private const val LOGIN_LOCK_MILLIS = 15 * 60_000L
        const val ACCOUNT_DELETION_COOLING_OFF_MILLIS = 7 * 24 * 60 * 60 * 1_000L

        fun fromEnvironment(env: Map<String, String> = System.getenv()): AccountService {
            val jdbcConfig = JdbcAccountStore.configFromEnvironment(env)
                ?: error("AUTO_ACCOUNTING_DATABASE_URL is required for backend account persistence.")
            return AccountService(
                store = JdbcAccountStore(
                    jdbcUrl = jdbcConfig.jdbcUrl,
                    username = jdbcConfig.username,
                    password = jdbcConfig.password
                ),
                smsProvider = WebhookSmsProvider.fromEnvironment(env)
            )
        }
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
