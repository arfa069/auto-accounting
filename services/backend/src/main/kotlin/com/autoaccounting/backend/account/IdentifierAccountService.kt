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

internal class IdentifierAccountService(
    context: AccountServiceContext,
    private val verificationCodeService: VerificationCodeService,
    private val sessionService: AccountSessionService
) : AccountServiceComponent(context) {
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun registerIdentifier(
        identifier: String,
        code: String?,
        password: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        val parseResult = try {
            com.autoaccounting.api.AccountIdentifierParser.parse(identifier)
        } catch (_: Exception) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        if (!isValidPassword(password) || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        val existingAccount = store.findAccountByIdentifier(parseResult.type.name, parseResult.normalizedValue)
        if (existingAccount != null) {
            val error = if (parseResult.type == com.autoaccounting.api.AccountIdentifierTypeContract.PHONE) {
                AccountError.PHONE_ALREADY_REGISTERED
            } else {
                AccountError.IDENTIFIER_ALREADY_REGISTERED
            }
            return AccountResult.Failure(error)
        }

        when (parseResult.type) {
            com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME -> {
                if (!code.isNullOrEmpty()) {
                    return AccountResult.Failure(AccountError.INVALID_REQUEST)
                }
            }
            com.autoaccounting.api.AccountIdentifierTypeContract.PHONE -> {
                if (code.isNullOrBlank() || !isValidVerificationCode(code)) {
                    return AccountResult.Failure(AccountError.INVALID_REQUEST)
                }
                val verifyRes = verificationCodeService.verifyVerificationCode("PHONE", parseResult.normalizedValue, code, PURPOSE_REGISTER)
                if (verifyRes is AccountResult.Failure) return verifyRes
            }
            com.autoaccounting.api.AccountIdentifierTypeContract.EMAIL -> {
                if (code.isNullOrBlank() || !isValidVerificationCode(code)) {
                    return AccountResult.Failure(AccountError.INVALID_REQUEST)
                }
                val verifyRes = verificationCodeService.verifyVerificationCode("EMAIL", parseResult.normalizedValue, code, PURPOSE_REGISTER)
                if (verifyRes is AccountResult.Failure) return verifyRes
            }
        }

        val now = clock.millis()
        val passwordHash = PasswordHash.create(password)
        val account = store.createAccountWithIdentifier(
            primaryIdentifierType = parseResult.type.name,
            rawValue = parseResult.displayValue,
            normalizedValue = parseResult.normalizedValue,
            passwordSalt = passwordHash.salt,
            passwordHash = passwordHash.hash,
            verified = true,
            now = now
        ) ?: return AccountResult.Failure(AccountError.INVALID_REQUEST)

        if (parseResult.type != com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME) {
            store.deleteVerificationCode(parseResult.type.name, parseResult.normalizedValue, PURPOSE_REGISTER)
        }

        sessionService.registerDevice(account.accountId, deviceId, ipAddress, now)

        val phone = if (parseResult.type == com.autoaccounting.api.AccountIdentifierTypeContract.PHONE) parseResult.normalizedValue else null
        return sessionService.issueSession(account.accountId, phone, deviceId, now)
    }

    fun loginIdentifier(
        identifier: String,
        password: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        val parseResult = try {
            com.autoaccounting.api.AccountIdentifierParser.parse(identifier)
        } catch (_: Exception) {
            return AccountResult.Failure(AccountError.LOGIN_FAILED)
        }

        if (password.isBlank() || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.LOGIN_FAILED)
        }

        val account = store.findAccountByIdentifier(parseResult.type.name, parseResult.normalizedValue)
        if (account == null) {
            PasswordHash.create("DummyPassword123!")
            return AccountResult.Failure(AccountError.LOGIN_FAILED)
        }

        val cred = store.findPasswordCredentialByAccountId(account.accountId)
            ?: return AccountResult.Failure(AccountError.LOGIN_FAILED)

        val now = clock.millis()
        when (val passwordResult = verifyPasswordWithLoginLockout(store, cred, password, now)) {
            is AccountResult.Failure -> return passwordResult
            is AccountResult.Success -> Unit
        }

        sessionService.registerDevice(account.accountId, deviceId, ipAddress, now)
        val identifiers = store.findIdentifiersByAccountId(account.accountId)
        val phone = identifiers.find { it.identifierType == "PHONE" }?.normalizedValue
        return sessionService.issueSession(account.accountId, phone, deviceId, now)
    }

    fun recoverPasswordByIdentifier(
        identifier: String,
        code: String,
        newPassword: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        val parseResult = try {
            com.autoaccounting.api.AccountIdentifierParser.parse(identifier)
        } catch (_: Exception) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        if (parseResult.type == com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        if (!isValidPassword(newPassword) || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        val verifyRes = verificationCodeService.verifyVerificationCode(parseResult.type.name, parseResult.normalizedValue, code, PURPOSE_RECOVERY)
        if (verifyRes is AccountResult.Failure) return verifyRes

        val account = store.findAccountByIdentifier(parseResult.type.name, parseResult.normalizedValue)
            ?: return AccountResult.Failure(AccountError.IDENTIFIER_NOT_REGISTERED)

        val cred = store.findPasswordCredentialByAccountId(account.accountId)
            ?: return AccountResult.Failure(AccountError.IDENTIFIER_NOT_REGISTERED)

        val now = clock.millis()
        val passwordHash = PasswordHash.create(newPassword)
        val rotated = store.resetPasswordAndRotateSession(
            credential = cred.copy(
                passwordSalt = passwordHash.salt,
                passwordHash = passwordHash.hash,
                failedLoginCount = 0,
                lockedUntilMillis = 0,
                updatedAtMillis = now
            ),
            verificationIdentifierType = parseResult.type.name,
            verificationNormalizedIdentifier = parseResult.normalizedValue,
            verificationPurpose = PURPOSE_RECOVERY,
            deviceId = deviceId,
            ipAddress = ipAddress,
            now = now,
            tokenGenerator = tokenGenerator
        )
        return rotated.mapAccountToken(sessionService::enrichAccountToken)
    }

    fun prepareIdentifierLink(
        bearerToken: String,
        identifier: String,
        deviceId: String = "",
        ipAddress: String = "",
        replaceExisting: Boolean = false
    ): AccountResult<com.autoaccounting.api.IdentifierLinkPrepareResponseContract> {
        val currentAccount = sessionService.verifiedAccount(bearerToken)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)

        val parseResult = try {
            com.autoaccounting.api.AccountIdentifierParser.parse(identifier)
        } catch (_: Exception) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        if (parseResult.type == com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        val existingIdentifierAccount = store.findAccountByIdentifier(parseResult.type.name, parseResult.normalizedValue)
        if (existingIdentifierAccount != null) {
            return if (existingIdentifierAccount.accountId == currentAccount.accountId) {
                AccountResult.Success(
                    com.autoaccounting.api.IdentifierLinkPrepareResponseContract.AlreadyLinked
                )
            } else {
                AccountResult.Failure(AccountError.IDENTIFIER_CONFLICT)
            }
        }

        val currentIdentifiers = store.findIdentifiersByAccountId(currentAccount.accountId)
        val currentIdentifierOfType = currentIdentifiers.find { it.identifierType == parseResult.type.name }
        if (currentIdentifierOfType != null && !replaceExisting) {
            return AccountResult.Failure(AccountError.IDENTIFIER_ALREADY_LINKED)
        }

        val now = clock.millis()
        val ticket = secureToken()
        val ticketHash = hashToken(ticket)
        val expiresAt = now + TICKET_VALIDITY_MILLIS

        val payloadObj = buildJsonObject {
            put("accountId", currentAccount.accountId)
            put("identifierType", parseResult.type.name)
            put("rawValue", parseResult.displayValue)
            put("normalizedValue", parseResult.normalizedValue)
        }

        val issueRes = verificationCodeService.issueVerificationCode(
            identifier = parseResult.displayValue,
            deviceId = deviceId,
            ipAddress = ipAddress,
            purpose = PURPOSE_IDENTIFIER_LINK
        )
        if (issueRes is AccountResult.Failure) return issueRes

        store.createOneTimeTicket(
            StoredOneTimeTicket(
                ticketHash = ticketHash,
                ticketType = if (currentIdentifierOfType == null) "IDENTIFIER_LINK" else "IDENTIFIER_REPLACE",
                accountId = currentAccount.accountId,
                payloadJson = payloadObj.toString(),
                expiresAtMillis = expiresAt
            )
        )

        return AccountResult.Success(
            com.autoaccounting.api.IdentifierLinkPrepareResponseContract.LinkTicketIssued(
                linkTicket = ticket,
                ticketExpiresAtMillis = expiresAt
            )
        )
    }

    @Suppress("LongParameterList", "ReturnCount")
    fun confirmIdentifierLink(
        bearerToken: String,
        linkTicket: String,
        code: String,
        deviceId: String = "",
        ipAddress: String = "",
        password: String? = null
    ): AccountResult<AccountToken> {
        val currentAccount = sessionService.verifiedAccount(bearerToken)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)

        val ticketHash = hashToken(linkTicket)
        val ticket = store.findOneTimeTicket(ticketHash)
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)

        val now = clock.millis()
        if (ticket.ticketType !in setOf("IDENTIFIER_LINK", "IDENTIFIER_REPLACE") || ticket.expiresAtMillis < now) {
            return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        }
        if (ticket.usedAtMillis != null) {
            return AccountResult.Failure(AccountError.TICKET_ALREADY_USED)
        }
        if (ticket.accountId != currentAccount.accountId) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        val jsonObj = runCatching { Json.parseToJsonElement(ticket.payloadJson).jsonObject }.getOrNull()
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)

        val identifierType = jsonObj["identifierType"]?.jsonPrimitive?.contentOrNull
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        val rawValue = jsonObj["rawValue"]?.jsonPrimitive?.contentOrNull
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        val normalizedValue = jsonObj["normalizedValue"]?.jsonPrimitive?.contentOrNull
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)

        val verifyRes = verificationCodeService.verifyVerificationCode(identifierType, normalizedValue, code, PURPOSE_IDENTIFIER_LINK)
        if (verifyRes is AccountResult.Failure) return verifyRes

        val passwordHash = if (store.findPasswordCredentialByAccountId(currentAccount.accountId) == null) {
            val newPassword = password.orEmpty()
            if (!isValidPassword(newPassword)) return AccountResult.Failure(AccountError.INVALID_REQUEST)
            PasswordHash.create(newPassword)
        } else {
            null
        }

        val linked = store.completeIdentifierLink(
            ticketHash = ticketHash,
            accountId = currentAccount.accountId,
            identifierType = identifierType,
            rawValue = rawValue,
            normalizedValue = normalizedValue,
            newPasswordSalt = passwordHash?.salt,
            newPasswordHash = passwordHash?.hash,
            deviceId = deviceId,
            ipAddress = ipAddress,
            now = now,
            tokenGenerator = tokenGenerator,
            replaceExisting = ticket.ticketType == "IDENTIFIER_REPLACE"
        )
        return linked.mapAccountToken(sessionService::enrichAccountToken)
    }

}


