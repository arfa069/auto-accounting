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
import java.security.SecureRandom
import java.util.Base64

internal class VerificationCodeService(
    context: AccountServiceContext,
    private val sessionService: AccountSessionService
) : AccountServiceComponent(context) {
    @Suppress("CyclomaticComplexMethod", "LongParameterList", "ReturnCount")
    private fun issuePhoneCode(
        phone: String,
        deviceId: String,
        ipAddress: String,
        purpose: String,
        contextKey: String? = null,
        bearerToken: String? = null
    ): AccountResult<Unit> {
        if (!isValidPhone(phone) || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val now = clock.millis()
        val normalizedPurpose = when (purpose.ifBlank { SMS_PURPOSE_DEFAULT }) {
            SMS_PURPOSE_DEFAULT -> if (store.findAccountByIdentifier("PHONE", phone) == null) {
                PURPOSE_REGISTER
            } else {
                PURPOSE_RECOVERY
            }
            else -> purpose
        }
        val storedContextKey = when (normalizedPurpose) {
            PURPOSE_REGISTER, PURPOSE_RECOVERY, PURPOSE_IDENTIFIER_LINK -> {
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
            SMS_PURPOSE_WECHAT_UNLINK -> {
                if (!contextKey.isNullOrBlank()) return AccountResult.Failure(AccountError.INVALID_REQUEST)
                val current = sessionService.verifiedAccount(bearerToken.orEmpty())
                    ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
                if (current.phone != phone) return AccountResult.Failure(AccountError.INVALID_REQUEST)
                if (!current.wechatLinked) return AccountResult.Failure(AccountError.INVALID_REQUEST)
                if (current.deletionStatus != null) {
                    return AccountResult.Failure(AccountError.ACCOUNT_DELETION_PENDING)
                }
                current.accountId.toString()
            }
            else -> return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        if (isSmsRateLimited(phone, deviceId, ipAddress, now)) {
            return AccountResult.Failure(AccountError.SMS_TOO_FREQUENT)
        }

        val code = smsCodeGenerator()
        if (!isValidVerificationCode(code)) {
            return AccountResult.Failure(AccountError.SMS_SEND_FAILED)
        }
        when (val sendResult = smsProvider.sendCode(phone, code)) {
            SmsProviderResult.Sent -> Unit
            is SmsProviderResult.Failed -> return AccountResult.Failure(sendResult.error)
        }

        store.upsertVerificationCode(
            StoredVerificationCode(
                identifierType = "PHONE",
                normalizedIdentifier = phone,
                purpose = normalizedPurpose,
                codeHash = verificationCodeHasher.hash("PHONE", phone, normalizedPurpose, code),
                expiresAtMillis = now + SMS_CODE_TTL_MILLIS,
                deviceId = deviceId,
                ipAddress = ipAddress,
                contextKey = storedContextKey
            )
        )
        smsRateLimitScopes(phone, deviceId, ipAddress).forEach { (scopeType, scopeValue) ->
            store.recordVerificationSendLog("SMS", scopeType, scopeValue, now)
        }
        return AccountResult.Success(Unit)
    }

    @Suppress("LongParameterList")
    fun issueVerificationCode(
        identifier: String,
        deviceId: String,
        ipAddress: String,
        purpose: String = SMS_PURPOSE_DEFAULT,
        contextKey: String? = null,
        bearerToken: String? = null
    ): AccountResult<Unit> {
        val parseResult = try {
            com.autoaccounting.api.AccountIdentifierParser.parse(identifier)
        } catch (_: Exception) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        return when (parseResult.type) {
            com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME -> AccountResult.Failure(AccountError.INVALID_REQUEST)
            com.autoaccounting.api.AccountIdentifierTypeContract.PHONE -> issuePhoneCode(parseResult.normalizedValue, deviceId, ipAddress, purpose, contextKey, bearerToken)
            com.autoaccounting.api.AccountIdentifierTypeContract.EMAIL -> issueEmailCode(parseResult.normalizedValue, deviceId, ipAddress, purpose, contextKey, bearerToken)
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongParameterList", "ReturnCount")
    private fun issueEmailCode(
        email: String,
        deviceId: String,
        ipAddress: String,
        purpose: String,
        contextKey: String?,
        bearerToken: String?
    ): AccountResult<Unit> {
        if (!isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val now = clock.millis()
        val normalizedPurpose = when (purpose.ifBlank { PURPOSE_REGISTER }) {
            SMS_PURPOSE_DEFAULT -> PURPOSE_REGISTER
            else -> purpose
        }
        val storedContextKey = when (normalizedPurpose) {
            PURPOSE_REGISTER, PURPOSE_RECOVERY, PURPOSE_IDENTIFIER_LINK -> {
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
            SMS_PURPOSE_WECHAT_UNLINK -> {
                if (!contextKey.isNullOrBlank()) return AccountResult.Failure(AccountError.INVALID_REQUEST)
                val current = sessionService.verifiedAccount(bearerToken.orEmpty())
                    ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
                val emailIsBound = current.identifiers.any {
                    it.type == com.autoaccounting.api.AccountIdentifierTypeContract.EMAIL && it.value == email
                }
                if (!emailIsBound || !current.wechatLinked) {
                    return AccountResult.Failure(AccountError.INVALID_REQUEST)
                }
                if (current.deletionStatus != null) {
                    return AccountResult.Failure(AccountError.ACCOUNT_DELETION_PENDING)
                }
                current.accountId.toString()
            }
            else -> return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        if (isEmailRateLimited(email, deviceId, ipAddress, now)) {
            return AccountResult.Failure(AccountError.CODE_SEND_TOO_FREQUENT)
        }

        val code = emailCodeGenerator()
        if (!isValidVerificationCode(code)) {
            return AccountResult.Failure(AccountError.EMAIL_SEND_FAILED)
        }
        when (val sendResult = emailProvider.sendCode(email, code, normalizedPurpose)) {
            EmailProviderResult.Sent -> Unit
            is EmailProviderResult.Failed -> return AccountResult.Failure(sendResult.error)
        }

        store.upsertVerificationCode(
            StoredVerificationCode(
                identifierType = "EMAIL",
                normalizedIdentifier = email,
                purpose = normalizedPurpose,
                codeHash = verificationCodeHasher.hash("EMAIL", email, normalizedPurpose, code),
                expiresAtMillis = now + SMS_CODE_TTL_MILLIS,
                deviceId = deviceId,
                ipAddress = ipAddress,
                contextKey = storedContextKey
            )
        )
        emailRateLimitScopes(email, deviceId, ipAddress).forEach { (scopeType, scopeValue) ->
            store.recordVerificationSendLog("EMAIL", scopeType, scopeValue, now)
        }
        return AccountResult.Success(Unit)
    }

    private fun isEmailRateLimited(email: String, deviceId: String, ipAddress: String, now: Long): Boolean {
        return emailRateLimitScopes(email, deviceId, ipAddress).any { (scopeType, scopeValue) ->
            val windowMillis = if (scopeType == SMS_SCOPE_PHONE || scopeType == SMS_SCOPE_DEVICE) {
                SMS_RATE_LIMIT_MILLIS
            } else {
                SMS_HOUR_MILLIS
            }
            val maxAllowed = if (scopeType == SMS_SCOPE_PHONE || scopeType == SMS_SCOPE_DEVICE) 1 else 5
            val count = store.countVerificationSendLogs("EMAIL", scopeType, scopeValue, now - windowMillis)
            count >= maxAllowed
        }
    }

    private fun emailRateLimitScopes(email: String, deviceId: String, ipAddress: String): List<Pair<String, String>> {
        val scopes = mutableListOf<Pair<String, String>>(
            SMS_SCOPE_PHONE to email,
            SMS_SCOPE_DEVICE to deviceId
        )
        if (ipAddress.isNotBlank()) {
            scopes += SMS_SCOPE_IP to ipAddress
        }
        return scopes
    }


    fun verifyVerificationCode(
        identifierType: String,
        normalizedIdentifier: String,
        code: String,
        expectedPurpose: String = SMS_PURPOSE_DEFAULT,
        expectedContextKey: String? = null
    ): AccountResult<StoredVerificationCode> {
        val now = clock.millis()
        val normalizedPurpose = expectedPurpose.ifBlank { PURPOSE_REGISTER }
        val record = store.findVerificationCode(identifierType, normalizedIdentifier, normalizedPurpose)
            ?: return AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG)

        if (record.invalidated) {
            return AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG)
        }

        if (now > record.expiresAtMillis) {
            store.upsertVerificationCode(record.copy(invalidated = true))
            return AccountResult.Failure(AccountError.VERIFICATION_CODE_EXPIRED)
        }

        if (expectedContextKey != null && record.contextKey != expectedContextKey) {
            return AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG)
        }

        if (!verificationCodeHasher.matches(identifierType, normalizedIdentifier, normalizedPurpose, code, record.codeHash)) {
            val failedAttempts = record.failedAttempts + 1
            store.upsertVerificationCode(
                record.copy(
                    failedAttempts = failedAttempts,
                    invalidated = failedAttempts >= MAX_SMS_CODE_FAILURES
                )
            )
            return AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG)
        }

        return AccountResult.Success(record)
    }

    fun validateWechatAuthTicket(
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
            val lastIssuedAt = store.latestVerificationSendLogMillis("SMS", scopeType, scopeValue)
            val minuteLimited = scopeType == SMS_SCOPE_PHONE &&
                lastIssuedAt != null &&
                now - lastIssuedAt < SMS_RATE_LIMIT_MILLIS
            val hourLimited = store.countVerificationSendLogs("SMS", scopeType, scopeValue, now - SMS_HOUR_MILLIS) >= 5
            val dayLimited = store.countVerificationSendLogs("SMS", scopeType, scopeValue, now - SMS_DAY_MILLIS) >= 10
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

}


