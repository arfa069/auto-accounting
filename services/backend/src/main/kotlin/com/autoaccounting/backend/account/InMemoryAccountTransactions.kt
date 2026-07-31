package com.autoaccounting.backend.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
internal class InMemoryAccountTransactions(
    state: InMemoryAccountState
) : InMemoryAccountStoreComponent(state), AccountWechatTransactionStore {

    override fun registerWechatAccount(
        ticketHash: String,
        appId: String,
        openid: String,
        unionid: String?,
        nickname: String?,
        avatarUrl: String?,
        deviceId: String,
        ipAddress: String,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken> = synchronized(state.lock) {
        val ticket = oneTimeTickets[ticketHash]
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        if (ticket.ticketType != "WECHAT_AUTH" || ticket.expiresAtMillis < now) {
            return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        }
        if (ticket.usedAtMillis != null) {
            return AccountResult.Failure(AccountError.TICKET_ALREADY_USED)
        }

        val existingIdentity = unionid?.let(::stateFindWechatIdentityByUnionid)
            ?: stateFindWechatIdentityByOpenid(appId, openid)
        if (existingIdentity != null) {
            return AccountResult.Failure(AccountError.WECHAT_ALREADY_LINKED)
        }

        val token = tokenGenerator()
        val tokenHash = hashStoredToken(token)

        oneTimeTickets[ticketHash] = ticket.copy(usedAtMillis = now)

        val accountId = nextAccountId++
        accounts[accountId] = StoredAccount(
            accountId = accountId,
            createdAtMillis = now
        )

        val identity = StoredWechatIdentity(
            accountId = accountId,
            appId = appId,
            openid = openid,
            unionid = unionid,
            nickname = nickname,
            avatarUrl = avatarUrl,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        wechatIdentities[accountId] = identity

        if (deviceId.isNotBlank()) {
            stateUpsertRegisteredDevice(
                StoredRegisteredDevice(
                    accountId = accountId,
                    deviceId = deviceId,
                    firstSeenAtMillis = now,
                    lastSeenAtMillis = now,
                    ipAddress = ipAddress
                )
            )
        }

        sessions.entries.removeAll { it.value.accountId == accountId }
        stateCreateSession(
            StoredSession(
                tokenHash = tokenHash,
                accountId = accountId,
                deviceId = deviceId,
                issuedAtMillis = now
            )
        )

        return AccountResult.Success(
            AccountToken(
                accountId = accountId,
                phone = null,
                token = token,
                wechatLinked = true,
                nickname = nickname,
                avatarUrl = avatarUrl
            )
        )
    }


    override fun linkWechatIdentity(
        ticketHash: String,
        targetAccountId: Long,
        phone: String?,
        appId: String,
        openid: String,
        unionid: String?,
        nickname: String?,
        avatarUrl: String?,
        deviceId: String,
        ipAddress: String,
        verificationCodeToDelete: StoredVerificationCode?,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken> = synchronized(state.lock) {
        val ticket = oneTimeTickets[ticketHash]
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        if (ticket.ticketType != "WECHAT_AUTH" || ticket.expiresAtMillis < now) {
            return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        }
        if (ticket.usedAtMillis != null) {
            return AccountResult.Failure(AccountError.TICKET_ALREADY_USED)
        }

        val existingTargetIdentity = wechatIdentities[targetAccountId]
        if (existingTargetIdentity != null) {
            return AccountResult.Failure(AccountError.WECHAT_ALREADY_LINKED)
        }

        val existingIdentity = unionid?.let(::stateFindWechatIdentityByUnionid)
            ?: stateFindWechatIdentityByOpenid(appId, openid)
        if (existingIdentity != null && existingIdentity.accountId != targetAccountId) {
            return AccountResult.Failure(AccountError.WECHAT_ALREADY_LINKED)
        }

        val token = tokenGenerator()
        val tokenHash = hashStoredToken(token)

        oneTimeTickets[ticketHash] = ticket.copy(usedAtMillis = now)

        val identity = StoredWechatIdentity(
            accountId = targetAccountId,
            appId = appId,
            openid = openid,
            unionid = unionid,
            nickname = nickname,
            avatarUrl = avatarUrl,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        wechatIdentities[targetAccountId] = identity

        if (deviceId.isNotBlank()) {
            stateUpsertRegisteredDevice(
                StoredRegisteredDevice(
                    accountId = targetAccountId,
                    deviceId = deviceId,
                    firstSeenAtMillis = now,
                    lastSeenAtMillis = now,
                    ipAddress = ipAddress
                )
            )
        }

        sessions.entries.removeAll { it.value.accountId == targetAccountId }
        stateCreateSession(
            StoredSession(
                tokenHash = tokenHash,
                accountId = targetAccountId,
                deviceId = deviceId,
                issuedAtMillis = now
            )
        )
        verificationCodeToDelete?.let { code ->
            stateDeleteVerificationCode(code)
        }

        val account = accounts[targetAccountId]
        val deletionStatus = account?.deletionRequestedAtMillis?.let { requestedAt ->
            AccountDeletionStatus(
                accountId = targetAccountId,
                phone = phone,
                requestedAtMillis = requestedAt,
                finalDeletionAtMillis = requestedAt + AccountService.ACCOUNT_DELETION_COOLING_OFF_MILLIS
            )
        }

        return AccountResult.Success(
            AccountToken(
                accountId = targetAccountId,
                phone = phone,
                token = token,
                deletionStatus = deletionStatus,
                wechatLinked = true,
                nickname = nickname,
                avatarUrl = avatarUrl
            )
        )
    }


    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod")
    override fun mergeAccounts(
        ticketHash: String,
        targetAccountId: Long,
        deviceId: String,
        ipAddress: String,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken> = synchronized(state.lock) {
        val ticket = oneTimeTickets[ticketHash]
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        if (ticket.ticketType != "ACCOUNT_MERGE" || ticket.expiresAtMillis < now) {
            return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        }
        if (ticket.usedAtMillis != null) {
            return AccountResult.Failure(AccountError.TICKET_ALREADY_USED)
        }

        val jsonObj = runCatching { Json.parseToJsonElement(ticket.payloadJson).jsonObject }.getOrNull()
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        val ticketTargetAccountId = jsonObj["targetAccountId"]?.jsonPrimitive?.longOrNull
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        val sourceAccountId = jsonObj["sourceAccountId"]?.jsonPrimitive?.longOrNull
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)

        if (ticketTargetAccountId != targetAccountId || sourceAccountId == targetAccountId) {
            return AccountResult.Failure(AccountError.MERGE_BLOCKED)
        }

        val targetAccount = accounts[targetAccountId]
            ?: return AccountResult.Failure(AccountError.INVALID_REQUEST)
        val sourceAccount = accounts[sourceAccountId]
            ?: return AccountResult.Failure(AccountError.INVALID_REQUEST)

        if (targetAccount.deletionRequestedAtMillis != null || sourceAccount.deletionRequestedAtMillis != null) {
            return AccountResult.Failure(AccountError.ACCOUNT_DELETION_PENDING)
        }

        val targetPassCred = passwordCredentials[targetAccountId]
        val sourcePassCred = passwordCredentials[sourceAccountId]
        if (targetPassCred != null && sourcePassCred != null) {
            return AccountResult.Failure(AccountError.MERGE_BLOCKED)
        }

        val targetWechat = wechatIdentities[targetAccountId]
        val sourceWechat = wechatIdentities[sourceAccountId]
        if (targetWechat != null && sourceWechat != null) {
            return AccountResult.Failure(AccountError.MERGE_BLOCKED)
        }

        val targetIdents = accountIdentifiers.values.filter { it.accountId == targetAccountId }
        val sourceIdents = accountIdentifiers.values.filter { it.accountId == sourceAccountId }
        if (targetIdents.any { t -> sourceIdents.any { s -> s.identifierType == t.identifierType } }) {
            return AccountResult.Failure(AccountError.MERGE_BLOCKED)
        }

        // Transfer credentials
        if (sourcePassCred != null) {
            passwordCredentials[targetAccountId] = sourcePassCred.copy(accountId = targetAccountId)
            passwordCredentials.remove(sourceAccountId)
        }
        for (sourceIdent in sourceIdents) {
            val key = Pair(sourceIdent.identifierType, sourceIdent.normalizedValue)
            accountIdentifiers[key] = sourceIdent.copy(accountId = targetAccountId)
        }
        if (targetAccount.primaryIdentifierType == null && sourceAccount.primaryIdentifierType != null) {
            accounts[targetAccountId] = targetAccount.copy(primaryIdentifierType = sourceAccount.primaryIdentifierType)
        }
        verificationCodes.entries.removeAll { entry ->
            sourceIdents.any { sourceIdentifier ->
                entry.key.first == sourceIdentifier.identifierType &&
                    entry.key.second == sourceIdentifier.normalizedValue
            }
        }

        if (sourceWechat != null) {
            wechatIdentities[targetAccountId] = sourceWechat.copy(accountId = targetAccountId)
            wechatIdentities.remove(sourceAccountId)
        }

        // Merge devices
        val targetDevs = devices.values.filter { it.accountId == targetAccountId }.associateBy { it.deviceId }
        val sourceDevs = devices.values.filter { it.accountId == sourceAccountId }
        for (sourceDev in sourceDevs) {
            val targetDev = targetDevs[sourceDev.deviceId]
            if (targetDev == null) {
                devices[targetAccountId to sourceDev.deviceId] = sourceDev.copy(accountId = targetAccountId)
            } else {
                val mergedFirstSeen = minOf(targetDev.firstSeenAtMillis, sourceDev.firstSeenAtMillis)
                val mergedLastSeen = maxOf(targetDev.lastSeenAtMillis, sourceDev.lastSeenAtMillis)
                val mergedIp = if (targetDev.lastSeenAtMillis >= sourceDev.lastSeenAtMillis) targetDev.ipAddress else sourceDev.ipAddress
                devices[targetAccountId to sourceDev.deviceId] = targetDev.copy(
                    firstSeenAtMillis = mergedFirstSeen,
                    lastSeenAtMillis = mergedLastSeen,
                    ipAddress = mergedIp
                )
            }
            devices.remove(sourceAccountId to sourceDev.deviceId)
        }

        if (deviceId.isNotBlank()) {
            stateUpsertRegisteredDevice(
                StoredRegisteredDevice(
                    accountId = targetAccountId,
                    deviceId = deviceId,
                    firstSeenAtMillis = now,
                    lastSeenAtMillis = now,
                    ipAddress = ipAddress
                )
            )
        }

        // Session rotation & cleanup
        stateDeleteSessionsForAccount(sourceAccountId)
        stateDeleteSessionsForAccount(targetAccountId)

        val token = tokenGenerator()
        val tokenHash = hashStoredToken(token)
        stateCreateSession(
            StoredSession(
                tokenHash = tokenHash,
                accountId = targetAccountId,
                deviceId = deviceId,
                issuedAtMillis = now
            )
        )

        // Mark ticket used & delete source account
        oneTimeTickets[ticketHash] = ticket.copy(usedAtMillis = now)
        oneTimeTickets.values.removeAll { it.accountId == sourceAccountId }
        accounts.remove(sourceAccountId)

        val finalPhone = accountIdentifiers.values.firstOrNull {
            it.accountId == targetAccountId && it.identifierType == "PHONE"
        }?.rawValue
        val finalWechat = wechatIdentities[targetAccountId]

        return AccountResult.Success(
            AccountToken(
                accountId = targetAccountId,
                phone = finalPhone,
                token = token,
                wechatLinked = finalWechat != null,
                nickname = finalWechat?.nickname,
                avatarUrl = finalWechat?.avatarUrl
            )
        )
    }


    override fun unlinkWechatIdentity(
        accountId: Long,
        phone: String?,
        deviceId: String,
        ipAddress: String,
        verificationCodeToDelete: StoredVerificationCode?,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken> = synchronized(state.lock) {
        val account = accounts[accountId]
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        passwordCredentials[accountId]
            ?: return AccountResult.Failure(AccountError.LAST_LOGIN_METHOD_CANNOT_UNLINK)
        val currentIdentifiers = accountIdentifiers.values.filter { it.accountId == accountId }
        if (currentIdentifiers.isEmpty()) return AccountResult.Failure(AccountError.LAST_LOGIN_METHOD_CANNOT_UNLINK)
        if (account.deletionRequestedAtMillis != null) {
            return AccountResult.Failure(AccountError.ACCOUNT_DELETION_PENDING)
        }
        if (wechatIdentities[accountId] == null) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }

        val token = tokenGenerator()
        val tokenHash = hashStoredToken(token)

        wechatIdentities.remove(accountId)
        stateDeleteSessionsForAccount(accountId)
        if (deviceId.isNotBlank()) {
            stateUpsertRegisteredDevice(
                StoredRegisteredDevice(
                    accountId = accountId,
                    deviceId = deviceId,
                    firstSeenAtMillis = now,
                    lastSeenAtMillis = now,
                    ipAddress = ipAddress
                )
            )
        }
        stateCreateSession(
            StoredSession(
                tokenHash = tokenHash,
                accountId = accountId,
                deviceId = deviceId,
                issuedAtMillis = now
            )
        )
        verificationCodeToDelete?.let { code ->
            stateDeleteVerificationCode(code)
        }

        return AccountResult.Success(
            AccountToken(
                accountId = accountId,
                phone = currentIdentifiers.firstOrNull { it.identifierType == "PHONE" }?.normalizedValue,
                token = token,
                wechatLinked = false
            )
        )
    }



}

