@file:Suppress("TooManyFunctions", "LongParameterList", "LongMethod")


package com.autoaccounting.backend.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull


/**
 * Core account record keyed by internal account_id.
 * Holds primary identifier type, deletion state and creation timestamp.
 */
data class StoredAccount(
    val accountId: Long,
    val primaryIdentifierType: String? = null,
    val deletionRequestedAtMillis: Long? = null,
    val createdAtMillis: Long
)

/**
 * Account-level password credential (shared across all identifiers).
 */
data class StoredPasswordCredential(
    val accountId: Long,
    val passwordSalt: String,
    val passwordHash: String,
    val failedLoginCount: Int = 0,
    val lockedUntilMillis: Long = 0,
    val updatedAtMillis: Long
)

/**
 * Account identifier record.
 */
data class StoredAccountIdentifier(
    val id: Long = 0,
    val accountId: Long,
    val identifierType: String,
    val rawValue: String,
    val normalizedValue: String,
    val verified: Boolean = true,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

/**
 * Verification code record for SMS and Email.
 */
data class StoredVerificationCode(
    val identifierType: String,
    val normalizedIdentifier: String,
    val purpose: String,
    val codeHash: String,
    val expiresAtMillis: Long,
    val failedAttempts: Int = 0,
    val invalidated: Boolean = false,
    val deviceId: String = "",
    val ipAddress: String = "",
    val contextKey: String? = null
)

data class StoredSession(
    val tokenHash: String,
    val accountId: Long,
    val deviceId: String = "",
    val issuedAtMillis: Long
)

data class StoredRegisteredDevice(
    val accountId: Long,
    val deviceId: String,
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long,
    val ipAddress: String = ""
)

data class StoredWechatIdentity(
    val accountId: Long,
    val appId: String,
    val openid: String,
    val unionid: String? = null,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

sealed interface WechatIdentityClaimResult {
    data object Claimed : WechatIdentityClaimResult
    data class Conflict(val existingIdentity: StoredWechatIdentity) : WechatIdentityClaimResult
}

data class StoredOneTimeTicket(
    val ticketHash: String,
    val ticketType: String,
    val accountId: Long? = null,
    val payloadJson: String,
    val expiresAtMillis: Long,
    val usedAtMillis: Long? = null
)

interface AccountStore {
    fun findAccount(accountId: Long): StoredAccount?
    fun updateAccountDeletionRequestedAt(accountId: Long, requestedAtMillis: Long?)
    fun accountsPendingDeletion(): List<StoredAccount>
    fun deleteAccount(accountId: Long)

    fun findAccountByIdentifier(identifierType: String, normalizedValue: String): StoredAccount?
    fun findPasswordCredentialByAccountId(accountId: Long): StoredPasswordCredential?
    fun findIdentifiersByAccountId(accountId: Long): List<StoredAccountIdentifier>
    fun findIdentifierByValue(identifierType: String, normalizedValue: String): StoredAccountIdentifier?
    fun updatePasswordCredential(credential: StoredPasswordCredential)
    fun resetPasswordAndRotateSession(
        credential: StoredPasswordCredential,
        verificationIdentifierType: String,
        verificationNormalizedIdentifier: String,
        verificationPurpose: String,
        deviceId: String,
        ipAddress: String,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken>
    fun createAccountWithIdentifier(
        primaryIdentifierType: String,
        rawValue: String,
        normalizedValue: String,
        passwordSalt: String?,
        passwordHash: String?,
        verified: Boolean,
        now: Long
    ): StoredAccount?
    fun addIdentifierToAccount(
        accountId: Long,
        identifierType: String,
        rawValue: String,
        normalizedValue: String,
        verified: Boolean,
        now: Long
    ): Boolean
    fun completeIdentifierLink(
        ticketHash: String,
        accountId: Long,
        identifierType: String,
        rawValue: String,
        normalizedValue: String,
        newPasswordSalt: String?,
        newPasswordHash: String?,
        deviceId: String,
        ipAddress: String,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken>

    fun upsertVerificationCode(code: StoredVerificationCode)
    fun findVerificationCode(identifierType: String, normalizedIdentifier: String, purpose: String): StoredVerificationCode?
    fun deleteVerificationCode(identifierType: String, normalizedIdentifier: String, purpose: String)
    fun recordVerificationSendLog(channelType: String, scopeType: String, scopeValue: String, issuedAtMillis: Long)
    fun countVerificationSendLogs(channelType: String, scopeType: String, scopeValue: String, sinceMillis: Long): Int
    fun latestVerificationSendLogMillis(channelType: String, scopeType: String, scopeValue: String): Long?

    fun createSession(session: StoredSession)
    fun findSession(tokenHash: String): StoredSession?
    fun deleteSession(tokenHash: String)
    fun deleteSessionsForAccount(accountId: Long)

    fun upsertRegisteredDevice(device: StoredRegisteredDevice)
    fun registeredDevices(accountId: Long): List<StoredRegisteredDevice>

    fun findWechatIdentityByOpenid(appId: String, openid: String): StoredWechatIdentity?
    fun findWechatIdentityByUnionid(unionid: String): StoredWechatIdentity?
    fun findWechatIdentityByAccountId(accountId: Long): StoredWechatIdentity?
    fun claimWechatIdentity(identity: StoredWechatIdentity): WechatIdentityClaimResult
    fun upsertWechatIdentity(identity: StoredWechatIdentity)
    fun deleteWechatIdentity(accountId: Long)

    fun createOneTimeTicket(ticket: StoredOneTimeTicket)
    fun findOneTimeTicket(ticketHash: String): StoredOneTimeTicket?
    fun markOneTimeTicketUsed(ticketHash: String, usedAtMillis: Long): Boolean

    fun registerWechatAccount(
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
    ): AccountResult<AccountToken>

    fun linkWechatIdentity(
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
    ): AccountResult<AccountToken>

    fun mergeAccounts(
        ticketHash: String,
        targetAccountId: Long,
        deviceId: String,
        ipAddress: String,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken>

    fun unlinkWechatIdentity(
        accountId: Long,
        phone: String?,
        deviceId: String,
        ipAddress: String,
        verificationCodeToDelete: StoredVerificationCode?,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken>
}


class InMemoryAccountStore : AccountStore {
    private var nextAccountId = 1L
    private val accounts = mutableMapOf<Long, StoredAccount>()
    private val passwordCredentials = mutableMapOf<Long, StoredPasswordCredential>()
    private val accountIdentifiers = mutableMapOf<Pair<String, String>, StoredAccountIdentifier>()
    private val verificationCodes = mutableMapOf<Triple<String, String, String>, StoredVerificationCode>()
    private val verificationSendLogs = mutableListOf<VerificationSendLog>()
    private val sessions = mutableMapOf<String, StoredSession>()
    private val devices = mutableMapOf<Pair<Long, String>, StoredRegisteredDevice>()
    private val wechatIdentities = mutableMapOf<Long, StoredWechatIdentity>()
    private val oneTimeTickets = mutableMapOf<String, StoredOneTimeTicket>()

    private data class VerificationSendLog(
        val channelType: String,
        val scopeType: String,
        val scopeValue: String,
        val issuedAtMillis: Long
    )

    override fun findAccountByIdentifier(identifierType: String, normalizedValue: String): StoredAccount? {
        val id = accountIdentifiers[identifierType to normalizedValue] ?: return null
        return accounts[id.accountId]
    }

    override fun findPasswordCredentialByAccountId(accountId: Long): StoredPasswordCredential? {
        return passwordCredentials[accountId]
    }

    override fun findIdentifiersByAccountId(accountId: Long): List<StoredAccountIdentifier> {
        return accountIdentifiers.values.filter { it.accountId == accountId }
    }

    override fun findIdentifierByValue(identifierType: String, normalizedValue: String): StoredAccountIdentifier? {
        return accountIdentifiers[identifierType to normalizedValue]
    }

    override fun updatePasswordCredential(credential: StoredPasswordCredential) {
        passwordCredentials[credential.accountId] = credential
    }

    @Synchronized
    override fun resetPasswordAndRotateSession(
        credential: StoredPasswordCredential,
        verificationIdentifierType: String,
        verificationNormalizedIdentifier: String,
        verificationPurpose: String,
        deviceId: String,
        ipAddress: String,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken> {
        if (accounts[credential.accountId] == null) return AccountResult.Failure(AccountError.LOGIN_FAILED)
        updatePasswordCredential(credential)
        sessions.entries.removeAll { it.value.accountId == credential.accountId }
        verificationCodes.remove(
            Triple(verificationIdentifierType, verificationNormalizedIdentifier, verificationPurpose)
        )
        if (deviceId.isNotBlank()) {
            val key = credential.accountId to deviceId
            val existing = devices[key]
            devices[key] = StoredRegisteredDevice(
                accountId = credential.accountId,
                deviceId = deviceId,
                firstSeenAtMillis = existing?.firstSeenAtMillis ?: now,
                lastSeenAtMillis = now,
                ipAddress = ipAddress
            )
        }
        val token = tokenGenerator()
        val tokenHash = hashStoredToken(token)
        sessions[tokenHash] = StoredSession(tokenHash, credential.accountId, deviceId, now)
        return AccountResult.Success(AccountToken(accountId = credential.accountId, token = token))
    }

    @Synchronized
    override fun createAccountWithIdentifier(
        primaryIdentifierType: String,
        rawValue: String,
        normalizedValue: String,
        passwordSalt: String?,
        passwordHash: String?,
        verified: Boolean,
        now: Long
    ): StoredAccount? {
        val key = primaryIdentifierType to normalizedValue
        if (accountIdentifiers.containsKey(key)) return null

        val accountId = nextAccountId++
        val account = StoredAccount(
            accountId = accountId,
            primaryIdentifierType = primaryIdentifierType,
            createdAtMillis = now
        )
        accounts[accountId] = account

        val identifier = StoredAccountIdentifier(
            id = accountId * 10,
            accountId = accountId,
            identifierType = primaryIdentifierType,
            rawValue = rawValue,
            normalizedValue = normalizedValue,
            verified = verified,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        accountIdentifiers[key] = identifier

        if (passwordSalt != null && passwordHash != null) {
            val pwd = StoredPasswordCredential(
                accountId = accountId,
                passwordSalt = passwordSalt,
                passwordHash = passwordHash,
                failedLoginCount = 0,
                lockedUntilMillis = 0,
                updatedAtMillis = now
            )
            passwordCredentials[accountId] = pwd
        }
        return account
    }

    @Synchronized
    override fun addIdentifierToAccount(
        accountId: Long,
        identifierType: String,
        rawValue: String,
        normalizedValue: String,
        verified: Boolean,
        now: Long
    ): Boolean {
        if (!accounts.containsKey(accountId)) return false
        val key = identifierType to normalizedValue
        if (accountIdentifiers.containsKey(key)) return false
        if (accountIdentifiers.values.any { it.accountId == accountId && it.identifierType == identifierType }) return false

        accountIdentifiers[key] = StoredAccountIdentifier(
            id = accountId * 10 + accountIdentifiers.size,
            accountId = accountId,
            identifierType = identifierType,
            rawValue = rawValue,
            normalizedValue = normalizedValue,
            verified = verified,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        return true
    }

    @Synchronized
    override fun completeIdentifierLink(
        ticketHash: String,
        accountId: Long,
        identifierType: String,
        rawValue: String,
        normalizedValue: String,
        newPasswordSalt: String?,
        newPasswordHash: String?,
        deviceId: String,
        ipAddress: String,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken> {
        val ticket = oneTimeTickets[ticketHash]
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        if (ticket.ticketType != "IDENTIFIER_LINK" || ticket.accountId != accountId ||
            ticket.usedAtMillis != null || ticket.expiresAtMillis < now
        ) return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        if (accountIdentifiers.containsKey(identifierType to normalizedValue) ||
            accountIdentifiers.values.any { it.accountId == accountId && it.identifierType == identifierType }
        ) return AccountResult.Failure(AccountError.IDENTIFIER_CONFLICT)

        val account = accounts[accountId] ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val existingPassword = passwordCredentials[accountId]
        if (existingPassword == null && (newPasswordSalt == null || newPasswordHash == null)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val token = tokenGenerator()
        if (existingPassword == null) {
            passwordCredentials[accountId] = StoredPasswordCredential(
                accountId = accountId,
                passwordSalt = requireNotNull(newPasswordSalt),
                passwordHash = requireNotNull(newPasswordHash),
                updatedAtMillis = now
            )
        }
        val added = addIdentifierToAccount(accountId, identifierType, rawValue, normalizedValue, true, now)
        check(added) { "Identifier link preconditions changed while holding the store lock" }
        if (account.primaryIdentifierType == null) {
            accounts[accountId] = account.copy(primaryIdentifierType = identifierType)
        }
        oneTimeTickets[ticketHash] = ticket.copy(usedAtMillis = now)
        verificationCodes.remove(Triple(identifierType, normalizedValue, "IDENTIFIER_LINK"))
        sessions.entries.removeAll { it.value.accountId == accountId }
        if (deviceId.isNotBlank()) {
            devices[accountId to deviceId] = StoredRegisteredDevice(accountId, deviceId, now, now, ipAddress)
        }
        val tokenHash = hashStoredToken(token)
        sessions[tokenHash] = StoredSession(tokenHash, accountId, deviceId, now)
        return AccountResult.Success(AccountToken(accountId = accountId, token = token))
    }

    override fun upsertVerificationCode(code: StoredVerificationCode) {
        verificationCodes[Triple(code.identifierType, code.normalizedIdentifier, code.purpose)] = code
    }

    override fun findVerificationCode(identifierType: String, normalizedIdentifier: String, purpose: String): StoredVerificationCode? {
        return verificationCodes[Triple(identifierType, normalizedIdentifier, purpose)]
    }

    override fun deleteVerificationCode(identifierType: String, normalizedIdentifier: String, purpose: String) {
        verificationCodes.remove(Triple(identifierType, normalizedIdentifier, purpose))
    }

    override fun recordVerificationSendLog(channelType: String, scopeType: String, scopeValue: String, issuedAtMillis: Long) {
        verificationSendLogs += VerificationSendLog(channelType, scopeType, scopeValue, issuedAtMillis)
    }

    override fun countVerificationSendLogs(channelType: String, scopeType: String, scopeValue: String, sinceMillis: Long): Int {
        return verificationSendLogs.count {
            it.channelType == channelType && it.scopeType == scopeType && it.scopeValue == scopeValue && it.issuedAtMillis >= sinceMillis
        }
    }

    override fun latestVerificationSendLogMillis(channelType: String, scopeType: String, scopeValue: String): Long? {
        return verificationSendLogs
            .filter { it.channelType == channelType && it.scopeType == scopeType && it.scopeValue == scopeValue }
            .maxOfOrNull { it.issuedAtMillis }
    }

    override fun findAccount(accountId: Long): StoredAccount? = accounts[accountId]

    @Synchronized
    override fun updateAccountDeletionRequestedAt(accountId: Long, requestedAtMillis: Long?) {
        val account = accounts[accountId] ?: return
        accounts[accountId] = account.copy(deletionRequestedAtMillis = requestedAtMillis)
    }

    override fun accountsPendingDeletion(): List<StoredAccount> {
        return accounts.values.filter { it.deletionRequestedAtMillis != null }
    }

    override fun deleteAccount(accountId: Long) {
        passwordCredentials.remove(accountId)
        accountIdentifiers.entries.removeIf { it.value.accountId == accountId }
        accounts.remove(accountId)
        sessions.values.removeAll { it.accountId == accountId }
        devices.keys.removeAll { it.first == accountId }
        wechatIdentities.remove(accountId)
    }


    override fun createSession(session: StoredSession) {
        sessions[session.tokenHash] = session
    }

    override fun findSession(tokenHash: String): StoredSession? = sessions[tokenHash]

    override fun deleteSession(tokenHash: String) {
        sessions.remove(tokenHash)
    }

    override fun deleteSessionsForAccount(accountId: Long) {
        sessions.entries.removeIf { it.value.accountId == accountId }
    }

    override fun upsertRegisteredDevice(device: StoredRegisteredDevice) {
        val key = device.accountId to device.deviceId
        val existing = devices[key]
        devices[key] = if (existing == null) {
            device
        } else {
            device.copy(firstSeenAtMillis = existing.firstSeenAtMillis)
        }
    }

    override fun registeredDevices(accountId: Long): List<StoredRegisteredDevice> {
        return devices.values.filter { it.accountId == accountId }.sortedBy { it.deviceId }
    }

    override fun findWechatIdentityByOpenid(appId: String, openid: String): StoredWechatIdentity? {
        return wechatIdentities.values.find { it.appId == appId && it.openid == openid }
    }

    override fun findWechatIdentityByUnionid(unionid: String): StoredWechatIdentity? {
        if (unionid.isBlank()) return null
        return wechatIdentities.values.find { it.unionid == unionid }
    }

    override fun findWechatIdentityByAccountId(accountId: Long): StoredWechatIdentity? {
        return wechatIdentities[accountId]
    }

    @Synchronized
    override fun claimWechatIdentity(identity: StoredWechatIdentity): WechatIdentityClaimResult {
        val existingIdentity = wechatIdentities[identity.accountId]
            ?: findWechatIdentityByOpenid(identity.appId, identity.openid)
            ?: identity.unionid?.let(::findWechatIdentityByUnionid)
        if (existingIdentity != null) {
            return WechatIdentityClaimResult.Conflict(existingIdentity)
        }
        wechatIdentities[identity.accountId] = identity
        return WechatIdentityClaimResult.Claimed
    }

    override fun upsertWechatIdentity(identity: StoredWechatIdentity) {
        wechatIdentities[identity.accountId] = identity
    }

    override fun deleteWechatIdentity(accountId: Long) {
        wechatIdentities.remove(accountId)
    }

    override fun createOneTimeTicket(ticket: StoredOneTimeTicket) {
        oneTimeTickets[ticket.ticketHash] = ticket
    }

    override fun findOneTimeTicket(ticketHash: String): StoredOneTimeTicket? {
        return oneTimeTickets[ticketHash]
    }

    override fun markOneTimeTicketUsed(ticketHash: String, usedAtMillis: Long): Boolean {
        val ticket = oneTimeTickets[ticketHash] ?: return false
        if (ticket.usedAtMillis != null || ticket.expiresAtMillis < usedAtMillis) return false
        oneTimeTickets[ticketHash] = ticket.copy(usedAtMillis = usedAtMillis)
        return true
    }

    @Synchronized
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
    ): AccountResult<AccountToken> {
        val ticket = oneTimeTickets[ticketHash]
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        if (ticket.ticketType != "WECHAT_AUTH" || ticket.expiresAtMillis < now) {
            return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        }
        if (ticket.usedAtMillis != null) {
            return AccountResult.Failure(AccountError.TICKET_ALREADY_USED)
        }

        val existingIdentity = (unionid?.let(::findWechatIdentityByUnionid))
            ?: findWechatIdentityByOpenid(appId, openid)
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
            upsertRegisteredDevice(
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
        createSession(
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

    @Synchronized
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
    ): AccountResult<AccountToken> {
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

        val existingIdentity = (unionid?.let(::findWechatIdentityByUnionid))
            ?: findWechatIdentityByOpenid(appId, openid)
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
            upsertRegisteredDevice(
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
        createSession(
            StoredSession(
                tokenHash = tokenHash,
                accountId = targetAccountId,
                deviceId = deviceId,
                issuedAtMillis = now
            )
        )
        verificationCodeToDelete?.let { code ->
            deleteVerificationCode(code.identifierType, code.normalizedIdentifier, code.purpose)
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

    @Synchronized
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod")
    override fun mergeAccounts(
        ticketHash: String,
        targetAccountId: Long,
        deviceId: String,
        ipAddress: String,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken> {
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
            upsertRegisteredDevice(
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
        deleteSessionsForAccount(sourceAccountId)
        deleteSessionsForAccount(targetAccountId)

        val token = tokenGenerator()
        val tokenHash = hashStoredToken(token)
        createSession(
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

    @Synchronized
    override fun unlinkWechatIdentity(
        accountId: Long,
        phone: String?,
        deviceId: String,
        ipAddress: String,
        verificationCodeToDelete: StoredVerificationCode?,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken> {
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
        deleteSessionsForAccount(accountId)
        if (deviceId.isNotBlank()) {
            upsertRegisteredDevice(
                StoredRegisteredDevice(
                    accountId = accountId,
                    deviceId = deviceId,
                    firstSeenAtMillis = now,
                    lastSeenAtMillis = now,
                    ipAddress = ipAddress
                )
            )
        }
        createSession(
            StoredSession(
                tokenHash = tokenHash,
                accountId = accountId,
                deviceId = deviceId,
                issuedAtMillis = now
            )
        )
        verificationCodeToDelete?.let { code ->
            deleteVerificationCode(code.identifierType, code.normalizedIdentifier, code.purpose)
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




    private fun hashStoredToken(token: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(digest)
    }
}
