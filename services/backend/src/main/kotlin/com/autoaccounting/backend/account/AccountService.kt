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
import java.security.SecureRandom
import java.util.Base64

class AccountService(
    private val store: AccountStore = InMemoryAccountStore(),
    private val smsProvider: SmsProvider = NoopSmsProvider,
    private val smsCodeGenerator: () -> String = { "%06d".format(SecureRandom().nextInt(1_000_000)) },
    private val emailProvider: EmailProvider = NoopEmailProvider,
    private val emailCodeGenerator: () -> String = { "%06d".format(SecureRandom().nextInt(1_000_000)) },
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
            val phone = phoneIdentifier(currentAccountId)
            val account = store.findAccount(currentAccountId)
            val deletionStatus = account?.deletionRequestedAtMillis?.let { account.deletionStatus(phone, it) }
            val sessionContract = AccountSessionResponseContract(
                accountId = currentAccountId,
                accountUuid = account?.publicId,
                primaryIdentifier = primaryIdentifierForAccount(currentAccountId),
                identifiers = identifierContracts(currentAccountId),
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
                val phone = phoneIdentifier(existingIdentity.accountId)
                val sessionResult = issueSession(
                    accountId = existingIdentity.accountId,
                    phone = phone,
                    deviceId = deviceId,
                    now = now
                )
                val sessionToken = when (sessionResult) {
                    is AccountResult.Success -> sessionResult.value
                    is AccountResult.Failure -> return sessionResult
                }

                val sessionContract = AccountSessionResponseContract(
                    accountId = sessionToken.accountId,
                    accountUuid = sessionToken.accountUuid,
                    primaryIdentifier = sessionToken.primaryIdentifier,
                    identifiers = sessionToken.identifiers,
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
        ).mapAccountToken(::enrichAccountToken)
    }

    fun linkWechatWithPassword(
        wechatTicket: String,
        identifier: String,
        password: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        if (wechatTicket.isBlank() || !isValidDeviceId(deviceId) || password.isBlank()) {
            return AccountResult.Failure(AccountError.LOGIN_FAILED)
        }
        val parseResult = try {
            com.autoaccounting.api.AccountIdentifierParser.parse(identifier)
        } catch (e: Exception) {
            return AccountResult.Failure(AccountError.LOGIN_FAILED)
        }
        val now = clock.millis()
        val payload = when (val ticketResult = validateWechatAuthTicket(wechatTicket, now)) {
            is AccountResult.Success -> ticketResult.value
            is AccountResult.Failure -> return ticketResult
        }
        val account = store.findAccountByIdentifier(parseResult.type.name, parseResult.normalizedValue)
            ?: return AccountResult.Failure(AccountError.LOGIN_FAILED)
        val passCred = store.findPasswordCredentialByAccountId(account.accountId)
            ?: return AccountResult.Failure(AccountError.LOGIN_FAILED)
        if (passCred.lockedUntilMillis > now) {
            return AccountResult.Failure(AccountError.ACCOUNT_LOCKED)
        }

        if (!PasswordHash(passCred.passwordSalt, passCred.passwordHash).matches(password)) {
            val failedCount = passCred.failedLoginCount + 1
            val lockedUntil = if (failedCount >= MAX_LOGIN_FAILURES) now + LOGIN_LOCK_MILLIS else passCred.lockedUntilMillis
            store.updatePasswordCredential(passCred.copy(failedLoginCount = failedCount, lockedUntilMillis = lockedUntil))
            return if (failedCount >= MAX_LOGIN_FAILURES) {
                AccountResult.Failure(AccountError.ACCOUNT_LOCKED)
            } else {
                AccountResult.Failure(AccountError.LOGIN_FAILED)
            }
        }

        store.updatePasswordCredential(passCred.copy(failedLoginCount = 0, lockedUntilMillis = 0))

        val targetExistingIdentity = store.findWechatIdentityByAccountId(account.accountId)
        if (targetExistingIdentity != null) {
            return AccountResult.Failure(AccountError.WECHAT_ALREADY_LINKED)
        }

        val identifiers = store.findIdentifiersByAccountId(account.accountId)
        val boundPhone = identifiers.find { it.identifierType == "PHONE" }?.normalizedValue

        return store.linkWechatIdentity(
            ticketHash = payload.ticketHash,
            targetAccountId = account.accountId,
                phone = boundPhone.orEmpty(),
            appId = payload.appId,
            openid = payload.openid,
            unionid = payload.unionid,
            nickname = payload.nickname,
            avatarUrl = payload.avatarUrl,
            deviceId = deviceId,
            ipAddress = ipAddress,
            verificationCodeToDelete = null,
            now = now,
            tokenGenerator = tokenGenerator
        ).mapAccountToken(::enrichAccountToken)
    }

    fun linkWechatWithCode(
        wechatTicket: String,
        identifier: String,
        code: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        if (wechatTicket.isBlank() || !isValidVerificationCode(code) || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val parseResult = try {
            com.autoaccounting.api.AccountIdentifierParser.parse(identifier)
        } catch (e: Exception) {
            return AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG)
        }
        if (parseResult.type == com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val now = clock.millis()
        val payload = when (val ticketResult = validateWechatAuthTicket(wechatTicket, now)) {
            is AccountResult.Success -> ticketResult.value
            is AccountResult.Failure -> return ticketResult
        }

        val verifyRes = verifyVerificationCode(
            identifierType = parseResult.type.name,
            normalizedIdentifier = parseResult.normalizedValue,
            code = code,
            expectedPurpose = SMS_PURPOSE_WECHAT_LINK,
            expectedContextKey = payload.ticketHash
        )
        if (verifyRes is AccountResult.Failure) return verifyRes
        val verifiedCode = (verifyRes as AccountResult.Success).value

        val account = store.findAccountByIdentifier(parseResult.type.name, parseResult.normalizedValue)
            ?: return AccountResult.Failure(AccountError.PHONE_NOT_REGISTERED)

        val targetExistingIdentity = store.findWechatIdentityByAccountId(account.accountId)
        if (targetExistingIdentity != null) {
            return AccountResult.Failure(AccountError.WECHAT_ALREADY_LINKED)
        }

        val identifiers = store.findIdentifiersByAccountId(account.accountId)
        val boundPhone = identifiers.find { it.identifierType == "PHONE" }?.normalizedValue

        return store.linkWechatIdentity(
            ticketHash = payload.ticketHash,
            targetAccountId = account.accountId,
                phone = boundPhone.orEmpty(),
            appId = payload.appId,
            openid = payload.openid,
            unionid = payload.unionid,
            nickname = payload.nickname,
            avatarUrl = payload.avatarUrl,
            deviceId = deviceId,
            ipAddress = ipAddress,
            verificationCodeToDelete = verifiedCode,
            now = now,
            tokenGenerator = tokenGenerator
        ).mapAccountToken(::enrichAccountToken)
    }

    fun unlinkWechatWithPassword(
        bearerToken: String,
        password: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        if (password.isBlank() || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val current = verifiedAccount(bearerToken)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val identifiers = store.findIdentifiersByAccountId(current.accountId)
        if (identifiers.isEmpty()) {
            return AccountResult.Failure(AccountError.LAST_LOGIN_METHOD_CANNOT_UNLINK)
        }
        if (!current.wechatLinked) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        if (current.deletionStatus != null) {
            return AccountResult.Failure(AccountError.ACCOUNT_DELETION_PENDING)
        }

        val now = clock.millis()
        val passCred = store.findPasswordCredentialByAccountId(current.accountId)
            ?: return AccountResult.Failure(AccountError.LAST_LOGIN_METHOD_CANNOT_UNLINK)
        if (passCred.lockedUntilMillis > now) {
            return AccountResult.Failure(AccountError.ACCOUNT_LOCKED)
        }
        if (!PasswordHash(passCred.passwordSalt, passCred.passwordHash).matches(password)) {
            val failedCount = passCred.failedLoginCount + 1
            val lockedUntil = if (failedCount >= MAX_LOGIN_FAILURES) now + LOGIN_LOCK_MILLIS else passCred.lockedUntilMillis
            store.updatePasswordCredential(passCred.copy(failedLoginCount = failedCount, lockedUntilMillis = lockedUntil))
            return if (failedCount >= MAX_LOGIN_FAILURES) {
                AccountResult.Failure(AccountError.ACCOUNT_LOCKED)
            } else {
                AccountResult.Failure(AccountError.LOGIN_FAILED)
            }
        }
        store.updatePasswordCredential(passCred.copy(failedLoginCount = 0, lockedUntilMillis = 0))

        val boundPhone = identifiers.find { it.identifierType == "PHONE" }?.normalizedValue

        return store.unlinkWechatIdentity(
            accountId = current.accountId,
            phone = boundPhone.orEmpty(),
            deviceId = deviceId,
            ipAddress = ipAddress,
            verificationCodeToDelete = null,
            now = now,
            tokenGenerator = tokenGenerator
        ).mapAccountToken(::enrichAccountToken)
    }

    fun unlinkWechatWithCode(
        bearerToken: String,
        identifier: String,
        code: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        if (!isValidVerificationCode(code) || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val current = verifiedAccount(bearerToken)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val identifiers = store.findIdentifiersByAccountId(current.accountId)
        if (identifiers.isEmpty()) {
            return AccountResult.Failure(AccountError.LAST_LOGIN_METHOD_CANNOT_UNLINK)
        }
        if (!current.wechatLinked) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        if (current.deletionStatus != null) {
            return AccountResult.Failure(AccountError.ACCOUNT_DELETION_PENDING)
        }

        val parsedIdentifier = try {
            com.autoaccounting.api.AccountIdentifierParser.parse(identifier)
        } catch (e: Exception) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        if (parsedIdentifier.type == com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val verifyIdent = identifiers.find {
            it.identifierType == parsedIdentifier.type.name &&
                it.normalizedValue == parsedIdentifier.normalizedValue
        } ?: return AccountResult.Failure(AccountError.INVALID_REQUEST)

        val verifyRes = verifyVerificationCode(
            identifierType = verifyIdent.identifierType,
            normalizedIdentifier = verifyIdent.normalizedValue,
            code = code,
            expectedPurpose = SMS_PURPOSE_WECHAT_UNLINK,
            expectedContextKey = current.accountId.toString()
        )
        if (verifyRes is AccountResult.Failure) return verifyRes
        val verifiedCode = (verifyRes as AccountResult.Success).value

        val boundPhone = identifiers.find { it.identifierType == "PHONE" }?.normalizedValue

        return store.unlinkWechatIdentity(
            accountId = current.accountId,
            phone = boundPhone.orEmpty(),
            deviceId = deviceId,
            ipAddress = ipAddress,
            verificationCodeToDelete = verifiedCode,
            now = clock.millis(),
            tokenGenerator = tokenGenerator
        ).mapAccountToken(::enrichAccountToken)
    }

    fun prepareMergeWithIdentifierPassword(
        bearerToken: String,
        identifier: String,
        password: String
    ): AccountResult<com.autoaccounting.api.MergePreviewResponseContract> {
        val currentAccount = verifiedAccount(bearerToken)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val currentAccountId = currentAccount.accountId

        if (password.isBlank()) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        val parseResult = try {
            com.autoaccounting.api.AccountIdentifierParser.parse(identifier)
        } catch (e: Exception) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        val sourceAccount = store.findAccountByIdentifier(parseResult.type.name, parseResult.normalizedValue)
            ?: return AccountResult.Failure(AccountError.LOGIN_FAILED)

        val sourcePassCred = store.findPasswordCredentialByAccountId(sourceAccount.accountId)
            ?: return AccountResult.Failure(AccountError.LOGIN_FAILED)

        val now = clock.millis()
        if (sourcePassCred.lockedUntilMillis > now) {
            return AccountResult.Failure(AccountError.ACCOUNT_LOCKED)
        }

        if (!PasswordHash(sourcePassCred.passwordSalt, sourcePassCred.passwordHash).matches(password)) {
            val updatedCount = sourcePassCred.failedLoginCount + 1
            val lockUntil = if (updatedCount >= MAX_LOGIN_FAILURES) {
                now + LOGIN_LOCK_MILLIS
            } else {
                0L
            }
            store.updatePasswordCredential(sourcePassCred.copy(failedLoginCount = updatedCount, lockedUntilMillis = lockUntil))
            return if (lockUntil > now) {
                AccountResult.Failure(AccountError.ACCOUNT_LOCKED)
            } else {
                AccountResult.Failure(AccountError.LOGIN_FAILED)
            }
        }

        if (sourcePassCred.failedLoginCount > 0 || sourcePassCred.lockedUntilMillis > 0) {
            store.updatePasswordCredential(sourcePassCred.copy(failedLoginCount = 0, lockedUntilMillis = 0))
        }

        val sourceAccountId = sourceAccount.accountId
        if (sourceAccountId == currentAccountId) {
            return AccountResult.Failure(AccountError.MERGE_BLOCKED)
        }

        val targetAccountObj = store.findAccount(currentAccountId)
        val sourceAccountObj = store.findAccount(sourceAccountId)
        if (targetAccountObj?.deletionRequestedAtMillis != null || sourceAccountObj?.deletionRequestedAtMillis != null) {
            return AccountResult.Failure(AccountError.ACCOUNT_DELETION_PENDING)
        }

        val currentPassCred = store.findPasswordCredentialByAccountId(currentAccountId)
        val currentWechat = store.findWechatIdentityByAccountId(currentAccountId)
        val sourceWechat = store.findWechatIdentityByAccountId(sourceAccountId)

        if (currentPassCred != null) {
            return AccountResult.Failure(AccountError.MERGE_BLOCKED)
        }
        if (currentWechat != null && sourceWechat != null) {
            return AccountResult.Failure(AccountError.MERGE_BLOCKED)
        }

        val currentIdentifiers = store.findIdentifiersByAccountId(currentAccountId).map {
            com.autoaccounting.api.AccountIdentifierContract(
                type = com.autoaccounting.api.AccountIdentifierTypeContract.valueOf(it.identifierType),
                value = it.rawValue
            )
        }
        val sourceIdentifiers = store.findIdentifiersByAccountId(sourceAccountId).map {
            com.autoaccounting.api.AccountIdentifierContract(
                type = com.autoaccounting.api.AccountIdentifierTypeContract.valueOf(it.identifierType),
                value = it.rawValue
            )
        }

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
            com.autoaccounting.api.MergePreviewResponseContract(
                mergeTicket = mergeTicketPlain,
                ticketExpiresAtMillis = ticketExpiresAt,
                currentIdentifiers = currentIdentifiers,
                currentWechatLinked = currentWechat != null,
                currentNickname = currentWechat?.nickname,
                sourceIdentifiers = sourceIdentifiers,
                sourceWechatLinked = sourceWechat != null,
                sourceNickname = sourceWechat?.nickname
            )
        )
    }

    fun confirmMerge(
        bearerToken: String,
        mergeTicket: String,
        confirmText: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        val currentAccount = verifiedAccount(bearerToken)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val currentAccountId = currentAccount.accountId

        val validTexts = setOf("MERGE_ACCOUNT", "合并账号")
        if (mergeTicket.isBlank() || confirmText.trim() !in validTexts) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        val now = clock.millis()
        val ticketHash = hashToken(mergeTicket)

        return store.mergeAccounts(
            ticketHash = ticketHash,
            targetAccountId = currentAccountId,
            deviceId = deviceId,
            ipAddress = ipAddress,
            now = now,
            tokenGenerator = tokenGenerator
        ).mapAccountToken(::enrichAccountToken)
    }

    private fun mergeRequiredResult(
        existingIdentity: StoredWechatIdentity,
        currentAccountId: Long,
        fallbackNickname: String?,
        now: Long
    ): AccountResult<WechatExchangeResponseContract> {
        val sourceAccountId = existingIdentity.accountId
        val sourceIdentifiers = store.findIdentifiersByAccountId(sourceAccountId).map {
            com.autoaccounting.api.AccountIdentifierContract(
                type = com.autoaccounting.api.AccountIdentifierTypeContract.valueOf(it.identifierType),
                value = it.rawValue
            )
        }
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
                    sourceIdentifiers = sourceIdentifiers,
                    ticketExpiresAtMillis = ticketExpiresAt
                )
            )
        )
    }

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
                val current = verifiedAccount(bearerToken.orEmpty())
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
        } catch (e: Exception) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        return when (parseResult.type) {
            com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME -> AccountResult.Failure(AccountError.INVALID_REQUEST)
            com.autoaccounting.api.AccountIdentifierTypeContract.PHONE -> issuePhoneCode(parseResult.normalizedValue, deviceId, ipAddress, purpose, contextKey, bearerToken)
            com.autoaccounting.api.AccountIdentifierTypeContract.EMAIL -> issueEmailCode(parseResult.normalizedValue, deviceId, ipAddress, purpose, contextKey, bearerToken)
        }
    }

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
                val current = verifiedAccount(bearerToken.orEmpty())
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

    fun registerIdentifier(
        identifier: String,
        code: String?,
        password: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        val parseResult = try {
            com.autoaccounting.api.AccountIdentifierParser.parse(identifier)
        } catch (e: Exception) {
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
                val verifyRes = verifyVerificationCode("PHONE", parseResult.normalizedValue, code, PURPOSE_REGISTER)
                if (verifyRes is AccountResult.Failure) return verifyRes
            }
            com.autoaccounting.api.AccountIdentifierTypeContract.EMAIL -> {
                if (code.isNullOrBlank() || !isValidVerificationCode(code)) {
                    return AccountResult.Failure(AccountError.INVALID_REQUEST)
                }
                val verifyRes = verifyVerificationCode("EMAIL", parseResult.normalizedValue, code, PURPOSE_REGISTER)
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

        registerDevice(account.accountId, deviceId, ipAddress, now)

        val phone = if (parseResult.type == com.autoaccounting.api.AccountIdentifierTypeContract.PHONE) parseResult.normalizedValue else null
        return issueSession(account.accountId, phone, deviceId, now)
    }

    fun loginIdentifier(
        identifier: String,
        password: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        val parseResult = try {
            com.autoaccounting.api.AccountIdentifierParser.parse(identifier)
        } catch (e: Exception) {
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
        if (cred.lockedUntilMillis > now) {
            return AccountResult.Failure(AccountError.ACCOUNT_LOCKED)
        }

        if (!PasswordHash(cred.passwordSalt, cred.passwordHash).matches(password)) {
            val failedLoginCount = cred.failedLoginCount + 1
            val lockedUntil = if (failedLoginCount >= MAX_LOGIN_FAILURES) now + LOGIN_LOCK_MILLIS else cred.lockedUntilMillis
            store.updatePasswordCredential(
                cred.copy(
                    failedLoginCount = failedLoginCount,
                    lockedUntilMillis = lockedUntil,
                    updatedAtMillis = now
                )
            )
            return if (lockedUntil > now) {
                AccountResult.Failure(AccountError.ACCOUNT_LOCKED)
            } else {
                AccountResult.Failure(AccountError.LOGIN_FAILED)
            }
        }

        if (cred.failedLoginCount > 0 || cred.lockedUntilMillis > 0) {
            store.updatePasswordCredential(
                cred.copy(
                    failedLoginCount = 0,
                    lockedUntilMillis = 0,
                    updatedAtMillis = now
                )
            )
        }

        registerDevice(account.accountId, deviceId, ipAddress, now)
        val identifiers = store.findIdentifiersByAccountId(account.accountId)
        val phone = identifiers.find { it.identifierType == "PHONE" }?.normalizedValue
        return issueSession(account.accountId, phone, deviceId, now)
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
        } catch (e: Exception) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        if (parseResult.type == com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        if (!isValidPassword(newPassword) || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        val verifyRes = verifyVerificationCode(parseResult.type.name, parseResult.normalizedValue, code, PURPOSE_RECOVERY)
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
        return rotated.mapAccountToken(::enrichAccountToken)
    }

    fun prepareIdentifierLink(
        bearerToken: String,
        identifier: String,
        deviceId: String = "",
        ipAddress: String = "",
        replaceExisting: Boolean = false
    ): AccountResult<com.autoaccounting.api.IdentifierLinkPrepareResponseContract> {
        val currentAccount = verifiedAccount(bearerToken)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)

        val parseResult = try {
            com.autoaccounting.api.AccountIdentifierParser.parse(identifier)
        } catch (e: Exception) {
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

        val issueRes = issueVerificationCode(
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

    fun confirmIdentifierLink(
        bearerToken: String,
        linkTicket: String,
        code: String,
        deviceId: String = "",
        ipAddress: String = "",
        password: String? = null
    ): AccountResult<AccountToken> {
        val currentAccount = verifiedAccount(bearerToken)
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

        val verifyRes = verifyVerificationCode(identifierType, normalizedValue, code, PURPOSE_IDENTIFIER_LINK)
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
        return linked.mapAccountToken(::enrichAccountToken)
    }

    fun verifyToken(token: String): AccountResult<AccountToken> {
        if (token.isBlank()) return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val session = store.findSession(hashToken(token))
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val account = store.findAccount(session.accountId)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val phone = phoneIdentifier(account.accountId)
        val wechatIdentity = store.findWechatIdentityByAccountId(account.accountId)
        val profile = store.findProfileByAccountId(account.accountId)
        val identifiers = identifierContracts(account.accountId)
        return AccountResult.Success(
            AccountToken(
                accountId = account.accountId,
                accountUuid = account.publicId,
                primaryIdentifier = primaryIdentifierForAccount(account.accountId),
                identifiers = identifiers,
                phone = phone,
                token = token,
                deletionStatus = account.deletionRequestedAtMillis?.let { requestedAt ->
                    account.deletionStatus(phone, requestedAt)
                },
                wechatLinked = wechatIdentity != null,
                nickname = profile?.nickname ?: wechatIdentity?.nickname,
                avatarUrl = profile?.avatarUrl ?: wechatIdentity?.avatarUrl
            )
        )
    }

    fun updateNickname(token: String, nickname: String): AccountResult<AccountToken> {
        val normalizedNickname = nickname.trim()
        if (normalizedNickname.isBlank() || normalizedNickname.length > MAX_NICKNAME_LENGTH) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val verified = verifyToken(token)
        if (verified is AccountResult.Failure) return verified
        val current = (verified as AccountResult.Success).value
        store.upsertProfile(
            StoredAccountProfile(
                accountId = current.accountId,
                nickname = normalizedNickname,
                avatarUrl = current.avatarUrl,
                updatedAtMillis = clock.millis()
            )
        )
        return verifyToken(token)
    }

    fun updateAvatar(token: String, avatarDataUrl: String): AccountResult<AccountToken> {
        if (!isValidAvatarDataUrl(avatarDataUrl)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val verified = verifyToken(token)
        if (verified is AccountResult.Failure) return verified
        val current = (verified as AccountResult.Success).value
        store.upsertProfile(
            StoredAccountProfile(
                accountId = current.accountId,
                nickname = current.nickname,
                avatarUrl = avatarDataUrl,
                updatedAtMillis = clock.millis()
            )
        )
        return verifyToken(token)
    }

    private fun isValidAvatarDataUrl(value: String): Boolean {
        val prefix = AVATAR_DATA_PREFIXES.firstOrNull(value::startsWith) ?: return false
        val encoded = value.substring(prefix.length)
        if (encoded.isBlank() || encoded.length > MAX_AVATAR_BASE64_LENGTH) return false
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return false
        if (bytes.isEmpty() || bytes.size > MAX_AVATAR_BYTES) return false
        return when (prefix) {
            "data:image/jpeg;base64," -> bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            "data:image/png;base64," -> bytes.size >= PNG_SIGNATURE.size &&
                PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
            else -> false
        }
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

    fun requestAccountDeletion(token: String): AccountResult<AccountDeletionStatus> {
        val verified = verifiedAccount(token)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val account = store.findAccount(verified.accountId)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val requestedAt = account.deletionRequestedAtMillis ?: clock.millis()
        store.updateAccountDeletionRequestedAt(account.accountId, requestedAt)
        val phone = verified.phone
        return AccountResult.Success(account.deletionStatus(phone, requestedAt))
    }

    fun getAccountDeletionStatus(token: String): AccountResult<AccountDeletionStatus?> {
        val verified = verifiedAccount(token)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val account = store.findAccount(verified.accountId)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val phone = phoneIdentifier(account.accountId)
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
        store.updateAccountDeletionRequestedAt(account.accountId, null)
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

    fun canWriteCloudData(accountId: Long): Boolean {
        val account = store.findAccount(accountId) ?: return false
        return account.deletionRequestedAtMillis == null
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

    private fun phoneIdentifier(accountId: Long): String? {
        return store.findIdentifiersByAccountId(accountId)
            .firstOrNull { it.identifierType == "PHONE" }
            ?.rawValue
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
        val identifiers = identifierContracts(accountId)
        return AccountResult.Success(
            AccountToken(
                accountId = accountId,
                accountUuid = account?.publicId,
                primaryIdentifier = primaryIdentifierForAccount(accountId),
                identifiers = identifiers,
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

    private fun identifierContracts(accountId: Long): List<com.autoaccounting.api.AccountIdentifierContract> {
        return store.findIdentifiersByAccountId(accountId).map { identifier ->
            com.autoaccounting.api.AccountIdentifierContract(
                type = com.autoaccounting.api.AccountIdentifierTypeContract.valueOf(identifier.identifierType),
                value = identifier.rawValue,
                verified = identifier.verified
            )
        }
    }

    private fun enrichAccountToken(token: AccountToken): AccountToken {
        val account = store.findAccount(token.accountId)
        val identifiers = identifierContracts(token.accountId)
        val phone = identifiers.firstOrNull {
            it.type == com.autoaccounting.api.AccountIdentifierTypeContract.PHONE
        }?.value
        val wechat = store.findWechatIdentityByAccountId(token.accountId)
        return token.copy(
            accountUuid = account?.publicId,
            primaryIdentifier = primaryIdentifierForAccount(token.accountId),
            identifiers = identifiers,
            phone = phone,
            deletionStatus = account?.deletionRequestedAtMillis?.let { requestedAt ->
                account.deletionStatus(phone, requestedAt)
            },
            wechatLinked = wechat != null,
            nickname = wechat?.nickname,
            avatarUrl = wechat?.avatarUrl
        )
    }

    private fun primaryIdentifierForAccount(accountId: Long): com.autoaccounting.api.AccountIdentifierContract? {
        val account = store.findAccount(accountId) ?: return null
        val primaryType = account.primaryIdentifierType ?: return null
        return identifierContracts(accountId).firstOrNull { it.type.name == primaryType }
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
        private const val MAX_NICKNAME_LENGTH = 20
        private const val MAX_AVATAR_BYTES = 256 * 1024
        private const val MAX_AVATAR_BASE64_LENGTH = 350_000
        private val AVATAR_DATA_PREFIXES = listOf(
            "data:image/jpeg;base64,",
            "data:image/png;base64,"
        )
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        private const val SMS_PURPOSE_DEFAULT = "DEFAULT"
        private const val SMS_PURPOSE_WECHAT_LINK = "WECHAT_LINK"
        private const val SMS_PURPOSE_WECHAT_UNLINK = "WECHAT_UNLINK"
        private const val PURPOSE_REGISTER = "REGISTER"
        private const val PURPOSE_RECOVERY = "RECOVERY"
        private const val PURPOSE_IDENTIFIER_LINK = "IDENTIFIER_LINK"
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
                smsProvider = SmsProvider.fromEnvironment(env),
                emailProvider = SmtpEmailProvider.fromEnvironment(env),
                verificationCodeHasher = VerificationCodeHasher.fromSecret(authPepper),
                wechatOAuthClient = WechatOAuthClient.fromEnvironment(env)
            )

        }
    }
}
