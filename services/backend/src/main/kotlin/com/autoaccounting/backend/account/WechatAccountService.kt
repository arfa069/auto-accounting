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

internal class WechatAccountService(
    context: AccountServiceContext,
    private val verificationCodeService: VerificationCodeService,
    private val sessionService: AccountSessionService
) : AccountServiceComponent(context) {
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

        val existingIdentity = (unionid?.let { store.findWechatIdentityByUnionid(it) })
            ?: store.findWechatIdentityByOpenid(wechatOAuthClient.appId, tokenResp.openid)

        val currentSessionAccount = bearerToken?.takeIf { it.isNotBlank() }?.let { sessionService.verifiedAccount(it) }

        if (currentSessionAccount == null) {
            return exchangeWithoutVerifiedAccount(
                existingIdentity = existingIdentity,
                tokenResponse = tokenResp,
                userInfoResponse = userInfoResp,
                deviceId = deviceId,
                ipAddress = ipAddress
            )
        }
        val now = clock.millis()
        return exchangeForVerifiedAccount(
            candidateIdentity = StoredWechatIdentity(
                accountId = currentSessionAccount.accountId,
                appId = wechatOAuthClient.appId,
                openid = tokenResp.openid,
                unionid = unionid,
                nickname = nickname,
                avatarUrl = avatarUrl,
                createdAtMillis = now,
                updatedAtMillis = now
            ),
            existingIdentity = existingIdentity,
            bearerToken = bearerToken,
            deviceId = deviceId,
            ipAddress = ipAddress
        )
    }

    private fun exchangeForVerifiedAccount(
        candidateIdentity: StoredWechatIdentity,
        existingIdentity: StoredWechatIdentity?,
        bearerToken: String?,
        deviceId: String,
        ipAddress: String
    ): AccountResult<WechatExchangeResponseContract> {
        val currentAccountId = candidateIdentity.accountId
        val currentIdentity = store.findWechatIdentityByAccountId(currentAccountId)
        val matchesCurrentIdentity = currentIdentity?.matchesWechatIdentity(
            appId = wechatOAuthClient.appId,
            openid = candidateIdentity.openid,
            unionid = candidateIdentity.unionid
        ) ?: true
        if (!matchesCurrentIdentity) {
            return AccountResult.Failure(AccountError.WECHAT_ALREADY_LINKED)
        }

        val resolvedIdentity = existingIdentity ?: when (val claim = store.claimWechatIdentity(candidateIdentity)) {
            WechatIdentityClaimResult.Claimed -> candidateIdentity
            is WechatIdentityClaimResult.Conflict -> claim.existingIdentity
        }
        if (resolvedIdentity.accountId != currentAccountId) {
            return mergeRequiredResult(
                store = store,
                existingIdentity = resolvedIdentity,
                currentAccountId = currentAccountId,
                fallbackNickname = candidateIdentity.nickname,
                now = candidateIdentity.updatedAtMillis
            )
        }
        if (!resolvedIdentity.matchesWechatIdentity(wechatOAuthClient.appId, candidateIdentity.openid, candidateIdentity.unionid)) {
            return AccountResult.Failure(AccountError.WECHAT_ALREADY_LINKED)
        }

        val updatedNickname = candidateIdentity.nickname ?: resolvedIdentity.nickname
        val updatedAvatarUrl = candidateIdentity.avatarUrl ?: resolvedIdentity.avatarUrl
        store.upsertWechatIdentity(
            resolvedIdentity.copy(
                nickname = updatedNickname,
                avatarUrl = updatedAvatarUrl,
                updatedAtMillis = candidateIdentity.updatedAtMillis
            )
        )
        sessionService.registerDevice(currentAccountId, deviceId, ipAddress, candidateIdentity.updatedAtMillis)
        val phone = sessionService.phoneIdentifier(currentAccountId)
        val account = store.findAccount(currentAccountId)
        val deletionStatus = account?.deletionRequestedAtMillis?.let { account.deletionStatus(phone, it) }
        val sessionContract = AccountSessionResponseContract(
            accountId = currentAccountId,
            accountUuid = account?.publicId,
            primaryIdentifier = sessionService.primaryIdentifierForAccount(currentAccountId),
            identifiers = sessionService.identifierContracts(currentAccountId),
            token = bearerToken,
            wechatLinked = true,
            nickname = updatedNickname,
            avatarUrl = updatedAvatarUrl,
            deletionStatus = deletionStatus?.toSessionContract() ?: AccountDeletionStatusContract()
        )
        return signedInWechatResult(sessionContract)
    }

    private fun exchangeWithoutVerifiedAccount(
        existingIdentity: StoredWechatIdentity?,
        tokenResponse: WechatTokenResponse,
        userInfoResponse: WechatUserInfoResponse?,
        deviceId: String,
        ipAddress: String
    ): AccountResult<WechatExchangeResponseContract> {
        val now = clock.millis()
        if (existingIdentity != null) {
            val identity = existingIdentity.copy(
                nickname = userInfoResponse?.nickname ?: existingIdentity.nickname,
                avatarUrl = userInfoResponse?.avatarUrl ?: existingIdentity.avatarUrl,
                updatedAtMillis = now
            )
            store.upsertWechatIdentity(identity)
            sessionService.registerDevice(identity.accountId, deviceId, ipAddress, now)
            val phone = sessionService.phoneIdentifier(identity.accountId)
            val sessionResult = sessionService.issueSession(
                accountId = identity.accountId,
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
                nickname = identity.nickname,
                avatarUrl = identity.avatarUrl,
                deletionStatus = sessionToken.deletionStatus?.toSessionContract() ?: AccountDeletionStatusContract()
            )
            return signedInWechatResult(sessionContract)
        }

        val wechatTicketPlain = secureToken()
        val wechatTicketHash = hashToken(wechatTicketPlain)
        val ticketExpiresAt = now + TICKET_VALIDITY_MILLIS
        val unionid = tokenResponse.unionid ?: userInfoResponse?.unionid
        val nickname = userInfoResponse?.nickname
        val avatarUrl = userInfoResponse?.avatarUrl
        val payload = buildJsonObject {
            put("appId", wechatOAuthClient.appId)
            put("openid", tokenResponse.openid)
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

    fun registerWithWechat(
        wechatTicket: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        if (wechatTicket.isBlank() || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val now = clock.millis()
        val payload = when (val ticketResult = verificationCodeService.validateWechatAuthTicket(wechatTicket, now)) {
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
        ).mapAccountToken(sessionService::enrichAccountToken)
    }

    @Suppress("ReturnCount")
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
        } catch (_: Exception) {
            return AccountResult.Failure(AccountError.LOGIN_FAILED)
        }
        val now = clock.millis()
        val payload = when (val ticketResult = verificationCodeService.validateWechatAuthTicket(wechatTicket, now)) {
            is AccountResult.Success -> ticketResult.value
            is AccountResult.Failure -> return ticketResult
        }
        val account = store.findAccountByIdentifier(parseResult.type.name, parseResult.normalizedValue)
            ?: return AccountResult.Failure(AccountError.LOGIN_FAILED)
        val passCred = store.findPasswordCredentialByAccountId(account.accountId)
            ?: return AccountResult.Failure(AccountError.LOGIN_FAILED)
        when (val passwordResult = verifyPasswordWithLoginLockout(store, passCred, password, now)) {
            is AccountResult.Failure -> return passwordResult
            is AccountResult.Success -> Unit
        }

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
        ).mapAccountToken(sessionService::enrichAccountToken)
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
        } catch (_: Exception) {
            return AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG)
        }
        if (parseResult.type == com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val now = clock.millis()
        val payload = when (val ticketResult = verificationCodeService.validateWechatAuthTicket(wechatTicket, now)) {
            is AccountResult.Success -> ticketResult.value
            is AccountResult.Failure -> return ticketResult
        }

        val verifyRes = verificationCodeService.verifyVerificationCode(
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
        ).mapAccountToken(sessionService::enrichAccountToken)
    }

    @Suppress("ReturnCount")
    fun unlinkWechatWithPassword(
        bearerToken: String,
        password: String,
        deviceId: String = "",
        ipAddress: String = ""
    ): AccountResult<AccountToken> {
        if (password.isBlank() || !isValidDeviceId(deviceId)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val current = sessionService.verifiedAccount(bearerToken)
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
        when (val passwordResult = verifyPasswordWithLoginLockout(store, passCred, password, now)) {
            is AccountResult.Failure -> return passwordResult
            is AccountResult.Success -> Unit
        }

        val boundPhone = identifiers.find { it.identifierType == "PHONE" }?.normalizedValue

        return store.unlinkWechatIdentity(
            accountId = current.accountId,
            phone = boundPhone.orEmpty(),
            deviceId = deviceId,
            ipAddress = ipAddress,
            verificationCodeToDelete = null,
            now = now,
            tokenGenerator = tokenGenerator
        ).mapAccountToken(sessionService::enrichAccountToken)
    }

    @Suppress("ReturnCount")
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
        val current = sessionService.verifiedAccount(bearerToken)
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
        } catch (_: Exception) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        if (parsedIdentifier.type == com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val verifyIdent = identifiers.find {
            it.identifierType == parsedIdentifier.type.name &&
                it.normalizedValue == parsedIdentifier.normalizedValue
        } ?: return AccountResult.Failure(AccountError.INVALID_REQUEST)

        val verifyRes = verificationCodeService.verifyVerificationCode(
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
        ).mapAccountToken(sessionService::enrichAccountToken)
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun prepareMergeWithIdentifierPassword(
        bearerToken: String,
        identifier: String,
        password: String
    ): AccountResult<com.autoaccounting.api.MergePreviewResponseContract> {
        val currentAccount = sessionService.verifiedAccount(bearerToken)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val currentAccountId = currentAccount.accountId

        if (password.isBlank()) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        val parseResult = try {
            com.autoaccounting.api.AccountIdentifierParser.parse(identifier)
        } catch (_: Exception) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        val sourceAccount = store.findAccountByIdentifier(parseResult.type.name, parseResult.normalizedValue)
            ?: return AccountResult.Failure(AccountError.LOGIN_FAILED)

        val sourcePassCred = store.findPasswordCredentialByAccountId(sourceAccount.accountId)
            ?: return AccountResult.Failure(AccountError.LOGIN_FAILED)

        val now = clock.millis()
        when (val passwordResult = verifyPasswordWithLoginLockout(store, sourcePassCred, password, now)) {
            is AccountResult.Failure -> return passwordResult
            is AccountResult.Success -> Unit
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
        val currentAccount = sessionService.verifiedAccount(bearerToken)
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
        ).mapAccountToken(sessionService::enrichAccountToken)
    }

}

private fun mergeRequiredResult(
    store: AccountStore,
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

private fun signedInWechatResult(
    session: AccountSessionResponseContract
): AccountResult<WechatExchangeResponseContract> =
    AccountResult.Success(
        WechatExchangeResponseContract(
            result = WechatAuthResultContract.SignedIn(session)
        )
    )
