@file:Suppress(
    "TooManyFunctions",
    "ComplexCondition",
    "LongParameterList",
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "ReturnCount",
    "LargeClass"
)


package com.autoaccounting.backend.account


import com.autoaccounting.api.AccountDeletionStatusContract
import com.autoaccounting.api.AccountSessionResponseContract
import com.autoaccounting.api.TICKET_VALIDITY_MILLIS
import com.autoaccounting.api.WechatAuthResultContract
import com.autoaccounting.api.WechatExchangeResponseContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class AccountError(
    val message: String
) {
    INVALID_REQUEST("请求信息不完整或格式不正确"),
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
    ACCOUNT_DELETION_NOT_PENDING("账号当前没有注销申请"),
    WECHAT_NOT_CONFIGURED("微信登录服务未配置"),
    WECHAT_AUTH_FAILED("微信授权失败，请重新尝试"),
    WECHAT_SERVICE_UNAVAILABLE("微信服务暂时不可用，请稍后再试"),
    TICKET_EXPIRED("操作超时，请重新发起微信授权"),
    TICKET_ALREADY_USED("此票据已被使用，请重新发起授权"),
    WECHAT_ALREADY_LINKED("此微信已被其他账号绑定"),
    PHONE_ALREADY_LINKED("此手机号已被其他账号绑定"),
    MERGE_BLOCKED("账号合并已被阻止"),
    LAST_LOGIN_METHOD_CANNOT_UNLINK("解绑失败：至少需要保留一种登录方式")
}


sealed interface AccountResult<out T> {
    data class Success<T>(val value: T) : AccountResult<T>
    data class Failure(val error: AccountError) : AccountResult<Nothing>
}

val AccountResult<*>.error: AccountError?
    get() = (this as? AccountResult.Failure)?.error

data class AccountToken(
    val accountId: Long = 0L,
    val phone: String? = null,
    val token: String,
    val deletionStatus: AccountDeletionStatus? = null,
    val wechatLinked: Boolean = false,
    val nickname: String? = null,
    val avatarUrl: String? = null
)

data class AccountDeletionStatus(
    val accountId: Long = 0L,
    val phone: String? = null,
    val requestedAtMillis: Long,
    val finalDeletionAtMillis: Long
)

private data class WechatAuthTicketPayload(
    val ticketHash: String,
    val appId: String,
    val openid: String,
    val unionid: String?,
    val nickname: String?,
    val avatarUrl: String?
)

class AccountService(
    private val store: AccountStore = InMemoryAccountStore(),
    private val smsProvider: SmsProvider = NoopSmsProvider,
    private val smsCodeGenerator: () -> String = { "%06d".format(SecureRandom().nextInt(1_000_000)) },
    private val tokenGenerator: () -> String = { secureToken() },
    private val verificationCodeHasher: VerificationCodeHasher = VerificationCodeHasher.random(),
    private val clock: MutableClock = MutableClock(),
    private val wechatOAuthClient: WechatOAuthClient = DefaultWechatOAuthClient()
) {

    fun exchangeWechatCode(
        code: String,
        bearerToken: String? = null,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<WechatExchangeResponseContract> {
        if (!wechatOAuthClient.isConfigured()) {
            return AccountResult.Failure(AccountError.WECHAT_NOT_CONFIGURED)
        }
        if (code.isBlank() || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        val tokenResult = wechatOAuthClient.exchangeCode(code)
        val tokenResp = when (tokenResult) {
            is WechatOAuthResult.Success -> tokenResult.value
            is WechatOAuthResult.Failure.AuthFailed -> return AccountResult.Failure(AccountError.WECHAT_AUTH_FAILED)
            is WechatOAuthResult.Failure.ServiceUnavailable -> return AccountResult.Failure(AccountError.WECHAT_SERVICE_UNAVAILABLE)
        }

        val userInfoResp = (wechatOAuthClient.fetchUserInfo(tokenResp.accessToken, tokenResp.openid) as? WechatOAuthResult.Success)?.value

        val unionid = tokenResp.unionid ?: userInfoResp?.unionid
        val nickname = userInfoResp?.nickname
        val avatarUrl = userInfoResp?.avatarUrl
        val now = clock.millis()

        val existingIdentity = (unionid?.let { store.findWechatIdentityByUnionid(it) })
            ?: store.findWechatIdentityByOpenid(wechatOAuthClient.appId, tokenResp.openid)

        val currentSessionAccount = bearerToken?.takeIf { it.isNotBlank() }?.let { verifiedAccount(it) }

        if (currentSessionAccount != null) {
            val currentAccountId = currentSessionAccount.accountId
            val currentIdentity = store.findWechatIdentityByAccountId(currentAccountId)
            val matchesCurrentIdentity = currentIdentity?.matchesWechatIdentity(
                appId = wechatOAuthClient.appId,
                openid = tokenResp.openid,
                unionid = unionid
            ) ?: true
            if (!matchesCurrentIdentity) {
                return AccountResult.Failure(AccountError.WECHAT_ALREADY_LINKED)
            }

            val candidateIdentity = StoredWechatIdentity(
                accountId = currentAccountId,
                appId = wechatOAuthClient.appId,
                openid = tokenResp.openid,
                unionid = unionid,
                nickname = nickname,
                avatarUrl = avatarUrl,
                createdAtMillis = now,
                updatedAtMillis = now
            )
            val resolvedIdentity = existingIdentity ?: when (val claim = store.claimWechatIdentity(candidateIdentity)) {
                WechatIdentityClaimResult.Claimed -> candidateIdentity
                is WechatIdentityClaimResult.Conflict -> claim.existingIdentity
            }
            if (resolvedIdentity.accountId != currentAccountId) {
                return mergeRequiredResult(
                    existingIdentity = resolvedIdentity,
                    currentAccountId = currentAccountId,
                    fallbackNickname = nickname,
                    now = now
                )
            }
            if (!resolvedIdentity.matchesWechatIdentity(wechatOAuthClient.appId, tokenResp.openid, unionid)) {
                return AccountResult.Failure(AccountError.WECHAT_ALREADY_LINKED)
            }

            val updatedNickname = nickname ?: resolvedIdentity.nickname
            val updatedAvatarUrl = avatarUrl ?: resolvedIdentity.avatarUrl
            store.upsertWechatIdentity(
                resolvedIdentity.copy(
                    nickname = updatedNickname,
                    avatarUrl = updatedAvatarUrl,
                    updatedAtMillis = now
                )
            )
            registerDevice(currentAccountId, deviceId, ipAddress, now)
            val user = phoneUserByAccountId(currentAccountId)
            val account = store.findAccount(currentAccountId)
            val deletionStatus = account?.deletionRequestedAtMillis?.let { account.deletionStatus(user?.phone, it) }
            val sessionContract = AccountSessionResponseContract(
                phone = user?.phone?.takeIf { it.isNotBlank() },
                token = bearerToken,
                wechatLinked = true,
                nickname = updatedNickname,
                avatarUrl = updatedAvatarUrl,
                deletionStatus = deletionStatus?.toContract() ?: AccountDeletionStatusContract()
            )
            return AccountResult.Success(
                WechatExchangeResponseContract(
                    result = WechatAuthResultContract.SignedIn(sessionContract)
                )
            )
        } else {
            if (existingIdentity != null) {
                val updatedNickname = nickname ?: existingIdentity.nickname
                val updatedAvatarUrl = avatarUrl ?: existingIdentity.avatarUrl
                store.upsertWechatIdentity(
                    existingIdentity.copy(
                        nickname = updatedNickname,
                        avatarUrl = updatedAvatarUrl,
                        updatedAtMillis = now
                    )
                )
                registerDevice(existingIdentity.accountId, deviceId, ipAddress, now)
                val user = phoneUserByAccountId(existingIdentity.accountId)
                val sessionResult = issueSession(
                    accountId = existingIdentity.accountId,
                    phone = user?.phone?.takeIf { it.isNotBlank() },
                    deviceId = deviceId,
                    now = now
                )
                val sessionToken = when (sessionResult) {
                    is AccountResult.Success -> sessionResult.value
                    is AccountResult.Failure -> return sessionResult
                }

                val sessionContract = AccountSessionResponseContract(
                    phone = sessionToken.phone,
                    token = sessionToken.token,
                    wechatLinked = true,
                    nickname = updatedNickname,
                    avatarUrl = updatedAvatarUrl,
                    deletionStatus = sessionToken.deletionStatus?.toContract() ?: AccountDeletionStatusContract()
                )
                return AccountResult.Success(
                    WechatExchangeResponseContract(
                        result = WechatAuthResultContract.SignedIn(sessionContract)
                    )
                )
            } else {
                val wechatTicketPlain = secureToken()
                val wechatTicketHash = hashToken(wechatTicketPlain)
                val ticketExpiresAt = now + TICKET_VALIDITY_MILLIS

                val payload = buildJsonObject {
                    put("appId", wechatOAuthClient.appId)
                    put("openid", tokenResp.openid)
                    if (unionid != null) put("unionid", unionid) else put("unionid", JsonNull)
                    if (nickname != null) put("nickname", nickname) else put("nickname", JsonNull)
                    if (avatarUrl != null) put("avatarUrl", avatarUrl) else put("avatarUrl", JsonNull)
                }.toString()

                store.createOneTimeTicket(
                    StoredOneTimeTicket(
                        ticketHash = wechatTicketHash,
                        ticketType = "WECHAT_AUTH",
                        payloadJson = payload,
                        expiresAtMillis = ticketExpiresAt
                    )
                )

                return AccountResult.Success(
                    WechatExchangeResponseContract(
                        result = WechatAuthResultContract.RegistrationRequired(
                            wechatTicket = wechatTicketPlain,
                            nickname = nickname,
                            avatarUrl = avatarUrl,
                            ticketExpiresAtMillis = ticketExpiresAt
                        )
                    )
                )
            }
        }
    }

    fun registerWithWechat(
        wechatTicket: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        if (wechatTicket.isBlank() || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val now = clock.millis()
        val payload = when (val ticketResult = validateWechatAuthTicket(wechatTicket, now)) {
            is AccountResult.Success -> ticketResult.value
            is AccountResult.Failure -> return ticketResult
        }

        return store.registerWechatAccount(
            ticketHash = payload.ticketHash,
            appId = payload.appId,
            openid = payload.openid,
            unionid = payload.unionid,
            nickname = payload.nickname,
            avatarUrl = payload.avatarUrl,
            deviceId = deviceId,
            ipAddress = ipAddress,
            now = now,
            tokenGenerator = tokenGenerator
        )
    }

    fun linkWechatWithPassword(
        wechatTicket: String,
        phone: String,
        password: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        if (wechatTicket.isBlank() || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        if (!isValidPhone(phone) || password.isBlank()) {
            return AccountResult.Failure(AccountError.LOGIN_FAILED)
        }
        val now = clock.millis()
        val payload = when (val ticketResult = validateWechatAuthTicket(wechatTicket, now)) {
            is AccountResult.Success -> ticketResult.value
            is AccountResult.Failure -> return ticketResult
        }
        val user = store.findUser(phone)
            ?: return AccountResult.Failure(AccountError.LOGIN_FAILED)
        if (user.lockedUntilMillis > now) {
            return AccountResult.Failure(AccountError.ACCOUNT_LOCKED)
        }

        if (!user.passwordHash().matches(password)) {
            val failedCount = user.failedLoginCount + 1
            val lockedUntil = if (failedCount >= MAX_LOGIN_FAILURES) now + LOGIN_LOCK_MILLIS else user.lockedUntilMillis
            store.updateUser(user.copy(failedLoginCount = failedCount, lockedUntilMillis = lockedUntil))
            return if (failedCount >= MAX_LOGIN_FAILURES) {
                AccountResult.Failure(AccountError.ACCOUNT_LOCKED)
            } else {
                AccountResult.Failure(AccountError.LOGIN_FAILED)
            }
        }

        store.updateUser(user.copy(failedLoginCount = 0, lockedUntilMillis = 0))

        val targetExistingIdentity = store.findWechatIdentityByAccountId(user.accountId)
        if (targetExistingIdentity != null) {
            return AccountResult.Failure(AccountError.WECHAT_ALREADY_LINKED)
        }

        return store.linkWechatIdentity(
            ticketHash = payload.ticketHash,
            targetAccountId = user.accountId,
            phone = user.phone,
            appId = payload.appId,
            openid = payload.openid,
            unionid = payload.unionid,
            nickname = payload.nickname,
            avatarUrl = payload.avatarUrl,
            deviceId = deviceId,
            ipAddress = ipAddress,
            smsCodePhoneToDelete = null,
            now = now,
            tokenGenerator = tokenGenerator
        )
    }

    fun linkWechatWithSms(
        wechatTicket: String,
        phone: String,
        code: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        if (wechatTicket.isBlank() || !isValidPhone(phone) || !isValidVerificationCode(code) || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val now = clock.millis()
        val payload = when (val ticketResult = validateWechatAuthTicket(wechatTicket, now)) {
            is AccountResult.Success -> ticketResult.value
            is AccountResult.Failure -> return ticketResult
        }
        when (
            val verification = verifySmsCode(
                phone = phone,
                code = code,
                expectedPurpose = SMS_PURPOSE_WECHAT_LINK,
                expectedContextKey = payload.ticketHash
            )
        ) {
            is AccountResult.Failure -> return verification
            is AccountResult.Success -> Unit
        }
        val user = store.findUser(phone)
            ?: return AccountResult.Failure(AccountError.PHONE_NOT_REGISTERED)

        val targetExistingIdentity = store.findWechatIdentityByAccountId(user.accountId)
        if (targetExistingIdentity != null) {
            return AccountResult.Failure(AccountError.WECHAT_ALREADY_LINKED)
        }

        return store.linkWechatIdentity(
            ticketHash = payload.ticketHash,
            targetAccountId = user.accountId,
            phone = user.phone,
            appId = payload.appId,
            openid = payload.openid,
            unionid = payload.unionid,
            nickname = payload.nickname,
            avatarUrl = payload.avatarUrl,
            deviceId = deviceId,
            ipAddress = ipAddress,
            smsCodePhoneToDelete = phone,
            now = now,
            tokenGenerator = tokenGenerator
        )
    }

    fun preparePhoneLink(
        bearerToken: String,
        phone: String,
        code: String
    ): AccountResult<com.autoaccounting.api.PhoneLinkPrepareResponseContract> {
        val verified = verifiedAccount(bearerToken)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val currentAccountId = verified.accountId

        if (phoneUserByAccountId(currentAccountId) != null) {
            return AccountResult.Failure(AccountError.PHONE_ALREADY_LINKED)
        }

        if (!isValidPhone(phone) || !isValidVerificationCode(code)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        when (val verification = verifySmsCode(phone = phone, code = code, expectedPurpose = SMS_PURPOSE_PHONE_LINK)) {
            is AccountResult.Failure -> return verification
            is AccountResult.Success -> Unit
        }

        store.deleteSmsCode(phone)

        val now = clock.millis()
        val ticketExpiresAt = now + TICKET_VALIDITY_MILLIS
        val existingUser = store.findUser(phone)

        return if (existingUser == null) {
            val phoneTicketPlain = secureToken()
            val phoneTicketHash = hashToken(phoneTicketPlain)
            val payload = buildJsonObject { put("phone", phone) }.toString()

            store.createOneTimeTicket(
                StoredOneTimeTicket(
                    ticketHash = phoneTicketHash,
                    ticketType = "PHONE_LINK",
                    accountId = currentAccountId,
                    payloadJson = payload,
                    expiresAtMillis = ticketExpiresAt
                )
            )

            AccountResult.Success(
                com.autoaccounting.api.PhoneLinkPrepareResponseContract.PhoneTicketIssued(
                    phoneTicket = phoneTicketPlain,
                    ticketExpiresAtMillis = ticketExpiresAt
                )
            )
        } else {
            val mergeTicketPlain = secureToken()
            val mergeTicketHash = hashToken(mergeTicketPlain)
            val sourceAccountId = existingUser.accountId
            val payload = buildJsonObject {
                put("targetAccountId", currentAccountId)
                put("sourceAccountId", sourceAccountId)
            }.toString()

            store.createOneTimeTicket(
                StoredOneTimeTicket(
                    ticketHash = mergeTicketHash,
                    ticketType = "ACCOUNT_MERGE",
                    accountId = currentAccountId,
                    payloadJson = payload,
                    expiresAtMillis = ticketExpiresAt
                )
            )

            val sourceWechatLinked = store.findWechatIdentityByAccountId(sourceAccountId) != null
            AccountResult.Success(
                com.autoaccounting.api.PhoneLinkPrepareResponseContract.MergeRequired(
                    mergeTicket = mergeTicketPlain,
                    sourcePhone = phone,
                    sourceWechatLinked = sourceWechatLinked,
                    ticketExpiresAtMillis = ticketExpiresAt
                )
            )
        }
    }

    fun completePhoneLink(
        bearerToken: String,
        phoneTicket: String,
        password: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        val verified = verifiedAccount(bearerToken)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val currentAccountId = verified.accountId

        if (phoneTicket.isBlank() || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        if (!isValidPassword(password)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        val now = clock.millis()
        val ticketHash = hashToken(phoneTicket)
        val ticket = store.findOneTimeTicket(ticketHash)
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)

        if (ticket.ticketType != "PHONE_LINK" || ticket.expiresAtMillis < now || ticket.accountId != currentAccountId) {
            return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        }
        if (ticket.usedAtMillis != null) {
            return AccountResult.Failure(AccountError.TICKET_ALREADY_USED)
        }

        if (phoneUserByAccountId(currentAccountId) != null) {
            return AccountResult.Failure(AccountError.PHONE_ALREADY_LINKED)
        }

        val jsonObj = runCatching { Json.parseToJsonElement(ticket.payloadJson).jsonObject }.getOrNull()
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        val phone = jsonObj["phone"]?.jsonPrimitive?.contentOrNull
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)

        val passwordHash = PasswordHash.create(password)

        return store.completePhoneLink(
            ticketHash = ticketHash,
            targetAccountId = currentAccountId,
            phone = phone,
            passwordSalt = passwordHash.salt,
            passwordHash = passwordHash.hash,
            deviceId = deviceId,
            ipAddress = ipAddress,
            now = now,
            tokenGenerator = tokenGenerator
        )
    }



    private fun mergeRequiredResult(
        existingIdentity: StoredWechatIdentity,
        currentAccountId: Long,
        fallbackNickname: String?,
        now: Long
    ): AccountResult<WechatExchangeResponseContract> {
        val sourceAccountId = existingIdentity.accountId
        val sourceUser = phoneUserByAccountId(sourceAccountId)
        val mergeTicketPlain = secureToken()
        val ticketExpiresAt = now + TICKET_VALIDITY_MILLIS
        val payload = buildJsonObject {
            put("targetAccountId", currentAccountId)
            put("sourceAccountId", sourceAccountId)
        }.toString()

        store.createOneTimeTicket(
            StoredOneTimeTicket(
                ticketHash = hashToken(mergeTicketPlain),
                ticketType = "ACCOUNT_MERGE",
                accountId = currentAccountId,
                payloadJson = payload,
                expiresAtMillis = ticketExpiresAt
            )
        )
        return AccountResult.Success(
            WechatExchangeResponseContract(
                result = WechatAuthResultContract.MergeRequired(
                    mergeTicket = mergeTicketPlain,
                    sourceNickname = existingIdentity.nickname ?: fallbackNickname,
                    sourcePhone = sourceUser?.phone?.takeIf { it.isNotBlank() },
                    ticketExpiresAtMillis = ticketExpiresAt
                )
            )
        )
    }

    fun issueSmsCode(

        phone: String,
        deviceId: String,
        ipAddress: String,
        purpose: String = SMS_PURPOSE_DEFAULT,
        contextKey: String? = null
    ): AccountResult<Unit> {
        if (!isValidPhone(phone) || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val now = clock.millis()
        val normalizedPurpose = purpose.ifBlank { SMS_PURPOSE_DEFAULT }
        val storedContextKey = when (normalizedPurpose) {
            SMS_PURPOSE_DEFAULT, SMS_PURPOSE_PHONE_LINK -> {
                if (!contextKey.isNullOrBlank()) return AccountResult.Failure(AccountError.INVALID_REQUEST)
                null
            }
            SMS_PURPOSE_WECHAT_LINK -> {
                val rawContext = contextKey?.takeIf { it.isNotBlank() }
                    ?: return AccountResult.Failure(AccountError.INVALID_REQUEST)
                when (val ticketResult = validateWechatAuthTicket(rawContext, now)) {
                    is AccountResult.Success -> ticketResult.value.ticketHash
                    is AccountResult.Failure -> return ticketResult
                }
            }
            else -> return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
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
                codeHash = verificationCodeHasher.hash(phone, code),
                expiresAtMillis = now + SMS_CODE_TTL_MILLIS,
                deviceId = deviceId,
                ipAddress = ipAddress,
                purpose = normalizedPurpose,
                contextKey = storedContextKey
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
        if (
            !isValidPhone(phone) ||
            !isValidVerificationCode(code) ||
            !isValidPassword(password) ||
            !isValidDeviceId(deviceId)
        ) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
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
                accountId = 0L,
                phone = phone,
                passwordSalt = passwordHash.salt,
                passwordHash = passwordHash.hash,
                createdAtMillis = now
            )
        )
        if (!created) {
            return AccountResult.Failure(AccountError.PHONE_ALREADY_REGISTERED)
        }
        val user = store.findUser(phone) ?: return AccountResult.Failure(AccountError.PHONE_ALREADY_REGISTERED)
        store.deleteSmsCode(phone)
        registerDeviceFromSms(user.accountId, smsCode, deviceId, ipAddress, now)
        return issueSession(user.accountId, user.phone, deviceId.ifBlank { smsCode.deviceId }, now)
    }

    fun login(
        phone: String,
        password: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        if (!isValidPhone(phone) || password.isBlank() || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.LOGIN_FAILED)
        }
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
        registerDevice(user.accountId, deviceId, ipAddress, now)
        return issueSession(user.accountId, user.phone, deviceId, now)
    }

    fun recoverPassword(
        phone: String,
        code: String,
        newPassword: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        if (
            !isValidPhone(phone) ||
            !isValidVerificationCode(code) ||
            !isValidPassword(newPassword) ||
            !isValidDeviceId(deviceId)
        ) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val user = store.findUser(phone)
            ?: return AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG)
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
        store.deleteSessionsForAccount(user.accountId)
        val now = clock.millis()
        registerDeviceFromSms(user.accountId, smsCode, deviceId, ipAddress, now)
        return issueSession(user.accountId, user.phone, deviceId.ifBlank { smsCode.deviceId }, now)
    }

    fun verifyToken(token: String): AccountResult<AccountToken> {
        if (token.isBlank()) return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val session = store.findSession(hashToken(token))
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val account = store.findAccount(session.accountId)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val user = phoneUserByAccountId(account.accountId)
        val phone = user?.phone?.takeIf { it.isNotBlank() }
        val wechatIdentity = store.findWechatIdentityByAccountId(account.accountId)
        return AccountResult.Success(
            AccountToken(
                accountId = account.accountId,
                phone = phone,
                token = token,
                deletionStatus = account.deletionRequestedAtMillis?.let { requestedAt ->
                    account.deletionStatus(phone, requestedAt)
                },
                wechatLinked = wechatIdentity != null,
                nickname = wechatIdentity?.nickname,
                avatarUrl = wechatIdentity?.avatarUrl
            )
        )
    }

    fun signOut(token: String): AccountResult<Unit> {
        val verified = verifyToken(token)
        if (verified is AccountResult.Failure) return verified
        store.deleteSession(hashToken(token))
        return AccountResult.Success(Unit)
    }

    fun registeredDevices(accountId: Long): List<StoredRegisteredDevice> {
        return store.registeredDevices(accountId)
    }

    fun registeredDevices(phone: String): List<StoredRegisteredDevice> {
        val user = store.findUser(phone) ?: return emptyList()
        return store.registeredDevices(user.accountId)
    }

    fun requestAccountDeletion(token: String): AccountResult<AccountDeletionStatus> {
        val verified = verifiedAccount(token)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val account = store.findAccount(verified.accountId)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val requestedAt = account.deletionRequestedAtMillis ?: clock.millis()
        val user = phoneUserByAccountId(account.accountId)
        if (user != null) {
            store.updateUser(user.copy(deletionRequestedAtMillis = requestedAt))
        }
        val phone = user?.phone?.takeIf { it.isNotBlank() }
        return AccountResult.Success(account.deletionStatus(phone, requestedAt))
    }

    fun getAccountDeletionStatus(token: String): AccountResult<AccountDeletionStatus?> {
        val verified = verifiedAccount(token)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val account = store.findAccount(verified.accountId)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val user = phoneUserByAccountId(account.accountId)
        val phone = user?.phone?.takeIf { it.isNotBlank() }
        return AccountResult.Success(
            account.deletionRequestedAtMillis?.let { requestedAt ->
                account.deletionStatus(phone, requestedAt)
            }
        )
    }

    fun cancelAccountDeletion(token: String): AccountResult<Unit> {
        val verified = verifiedAccount(token)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val account = store.findAccount(verified.accountId)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        if (account.deletionRequestedAtMillis == null) {
            return AccountResult.Failure(AccountError.ACCOUNT_DELETION_NOT_PENDING)
        }
        val user = phoneUserByAccountId(account.accountId)
        if (user != null) {
            store.updateUser(user.copy(deletionRequestedAtMillis = null))
        }
        return AccountResult.Success(Unit)
    }

    fun writeCloudConfiguration(accountId: Long): AccountResult<Unit> {
        val account = store.findAccount(accountId)
            ?: return AccountResult.Failure(AccountError.PHONE_NOT_REGISTERED)
        if (account.deletionRequestedAtMillis != null) {
            return AccountResult.Failure(AccountError.ACCOUNT_DELETION_PENDING)
        }
        return AccountResult.Success(Unit)
    }

    fun writeCloudConfiguration(phone: String): AccountResult<Unit> {
        val user = store.findUser(phone) ?: return AccountResult.Failure(AccountError.PHONE_NOT_REGISTERED)
        return writeCloudConfiguration(user.accountId)
    }

    fun canWriteCloudData(accountId: Long): Boolean {
        val account = store.findAccount(accountId) ?: return false
        return account.deletionRequestedAtMillis == null
    }

    fun canWriteCloudData(phone: String): Boolean {
        val user = store.findUser(phone) ?: return false
        return canWriteCloudData(user.accountId)
    }

    fun accountsDueForDeletion(): List<Long> {
        val now = clock.millis()
        return store.accountsPendingDeletion()
            .filter { account ->
                val requestedAt = account.deletionRequestedAtMillis
                requestedAt != null && now >= requestedAt + ACCOUNT_DELETION_COOLING_OFF_MILLIS
            }
            .map { it.accountId }
    }

    fun finalizeAccountDeletion(accountId: Long): Boolean {
        val account = store.findAccount(accountId) ?: return false
        val requestedAt = account.deletionRequestedAtMillis ?: return false
        if (clock.millis() < requestedAt + ACCOUNT_DELETION_COOLING_OFF_MILLIS) return false
        store.deleteAccount(accountId)
        return true
    }

    fun advanceTimeBy(millis: Long) {
        clock.advanceBy(millis)
    }

    private fun phoneUserByAccountId(accountId: Long): StoredUser? {
        return store.findUserByAccountId(accountId)?.takeIf { it.phone.isNotBlank() }
    }

    private fun verifySmsCode(
        phone: String,
        code: String,
        expectedPurpose: String = SMS_PURPOSE_DEFAULT,
        expectedContextKey: String? = null
    ): AccountResult<StoredSmsCode> {
        val record = store.findSmsCode(phone)
            ?: return AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG)
        if (record.purpose != expectedPurpose || record.contextKey != expectedContextKey) {
            return AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG)
        }
        if (record.invalidated) {
            return AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG)
        }
        if (clock.millis() > record.expiresAtMillis) {
            store.updateSmsCode(record.copy(invalidated = true))
            return AccountResult.Failure(AccountError.VERIFICATION_CODE_EXPIRED)
        }
        if (!verificationCodeHasher.matches(phone, code, record.codeHash)) {
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

    private fun validateWechatAuthTicket(
        wechatTicket: String,
        now: Long
    ): AccountResult<WechatAuthTicketPayload> {
        val ticketHash = hashToken(wechatTicket)
        val ticket = store.findOneTimeTicket(ticketHash)
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        if (ticket.ticketType != "WECHAT_AUTH" || ticket.expiresAtMillis < now) {
            return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        }
        if (ticket.usedAtMillis != null) {
            return AccountResult.Failure(AccountError.TICKET_ALREADY_USED)
        }
        val jsonObj = runCatching { Json.parseToJsonElement(ticket.payloadJson).jsonObject }.getOrNull()
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        val appId = jsonObj["appId"]?.jsonPrimitive?.contentOrNull
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        val openid = jsonObj["openid"]?.jsonPrimitive?.contentOrNull
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        return AccountResult.Success(
            WechatAuthTicketPayload(
                ticketHash = ticketHash,
                appId = appId,
                openid = openid,
                unionid = jsonObj["unionid"]?.jsonPrimitive?.contentOrNull,
                nickname = jsonObj["nickname"]?.jsonPrimitive?.contentOrNull,
                avatarUrl = jsonObj["avatarUrl"]?.jsonPrimitive?.contentOrNull
            )
        )
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
        accountId: Long,
        smsCode: StoredSmsCode,
        requestedDeviceId: String,
        requestedIpAddress: String,
        now: Long
    ) {
        registerDevice(
            accountId = accountId,
            deviceId = requestedDeviceId.ifBlank { smsCode.deviceId },
            ipAddress = requestedIpAddress.ifBlank { smsCode.ipAddress },
            now = now
        )
    }

    private fun registerDevice(
        accountId: Long,
        deviceId: String,
        ipAddress: String,
        now: Long
    ) {
        if (deviceId.isBlank()) return
        if (!canWriteCloudData(accountId)) return
        store.upsertRegisteredDevice(
            StoredRegisteredDevice(
                accountId = accountId,
                deviceId = deviceId,
                firstSeenAtMillis = now,
                lastSeenAtMillis = now,
                ipAddress = ipAddress
            )
        )
    }

    private fun issueSession(
        accountId: Long,
        phone: String?,
        deviceId: String,
        now: Long
    ): AccountResult<AccountToken> {
        val token = tokenGenerator()
        store.createSession(
            StoredSession(
                tokenHash = hashToken(token),
                accountId = accountId,
                deviceId = deviceId,
                issuedAtMillis = now
            )
        )
        val account = store.findAccount(accountId)
        val deletionStatus = account
            ?.deletionRequestedAtMillis
            ?.let { requestedAt -> account.deletionStatus(phone, requestedAt) }
        val wechatIdentity = store.findWechatIdentityByAccountId(accountId)
        return AccountResult.Success(
            AccountToken(
                accountId = accountId,
                phone = phone,
                token = token,
                deletionStatus = deletionStatus,
                wechatLinked = wechatIdentity != null,
                nickname = wechatIdentity?.nickname,
                avatarUrl = wechatIdentity?.avatarUrl
            )
        )
    }


    private fun verifiedAccount(token: String): AccountToken? {
        return (verifyToken(token) as? AccountResult.Success)?.value
    }

    private fun StoredAccount.deletionStatus(phone: String?, requestedAtMillis: Long): AccountDeletionStatus {
        return AccountDeletionStatus(
            accountId = accountId,
            phone = phone,
            requestedAtMillis = requestedAtMillis,
            finalDeletionAtMillis = requestedAtMillis + ACCOUNT_DELETION_COOLING_OFF_MILLIS
        )
    }

    private fun AccountDeletionStatus.toContract(): AccountDeletionStatusContract {
        return AccountDeletionStatusContract(
            pending = true,
            requestedAtMillis = requestedAtMillis,
            finalDeletionAtMillis = finalDeletionAtMillis
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
        private const val SMS_PURPOSE_DEFAULT = "DEFAULT"
        private const val SMS_PURPOSE_WECHAT_LINK = "WECHAT_LINK"
        private const val SMS_PURPOSE_PHONE_LINK = "PHONE_LINK"
        private const val LOGIN_LOCK_MILLIS = 15 * 60_000L
        const val ACCOUNT_DELETION_COOLING_OFF_MILLIS = 7 * 24 * 60 * 60 * 1_000L

        fun fromEnvironment(env: Map<String, String> = System.getenv()): AccountService {
            val jdbcConfig = JdbcAccountStore.configFromEnvironment(env)
                ?: error("AUTO_ACCOUNTING_DATABASE_URL is required for backend account persistence.")
            val authPepper = env["AUTO_ACCOUNTING_AUTH_PEPPER"].orEmpty()
            require(authPepper.length >= 32) {
                "AUTO_ACCOUNTING_AUTH_PEPPER must contain at least 32 characters."
            }
            return AccountService(
                store = JdbcAccountStore(
                    jdbcUrl = jdbcConfig.jdbcUrl,
                    username = jdbcConfig.username,
                    password = jdbcConfig.password
                ),
                smsProvider = WebhookSmsProvider.fromEnvironment(env),
                verificationCodeHasher = VerificationCodeHasher.fromSecret(authPepper),
                wechatOAuthClient = WechatOAuthClient.fromEnvironment(env)
            )

        }
    }
}

private data class PasswordHash(
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

private fun hashToken(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
    return Base64.getEncoder().encodeToString(digest)
}

private fun isValidPhone(phone: String): Boolean = Regex("^\\d{11}$").matches(phone)

private fun isValidVerificationCode(code: String): Boolean = Regex("^\\d{6}$").matches(code)

private fun isValidPassword(password: String): Boolean {
    return password.length in 8..32 &&
        password.any(Char::isUpperCase) &&
        password.any(Char::isLowerCase) &&
        password.any(Char::isDigit) &&
        password.any { !it.isLetterOrDigit() }
}

private fun isValidDeviceId(deviceId: String): Boolean {
    return deviceId.length <= 128 && deviceId.none(Char::isWhitespace)
}

private fun StoredWechatIdentity.matchesWechatIdentity(
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

    fun hash(phone: String, code: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
        return Base64.getEncoder().encodeToString(
            mac.doFinal("$phone:$code".toByteArray(Charsets.UTF_8))
        )
    }

    fun matches(phone: String, code: String, expectedHash: String): Boolean {
        return runCatching {
            MessageDigest.isEqual(
                Base64.getDecoder().decode(expectedHash),
                Base64.getDecoder().decode(hash(phone, code))
            )
        }.getOrDefault(false)
    }

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
