@file:Suppress("TooManyFunctions", "LongParameterList", "LongMethod")


package com.autoaccounting.backend.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull


/**
 * Core account record keyed by internal account_id.
 * Holds deletion state and creation timestamp.
 */
data class StoredAccount(
    val accountId: Long,
    val deletionRequestedAtMillis: Long? = null,
    val createdAtMillis: Long
)

/**
 * Phone credential linked to an account.
 */
data class StoredPhoneCredential(
    val accountId: Long,
    val phone: String,
    val passwordSalt: String,
    val passwordHash: String,
    val failedLoginCount: Int = 0,
    val lockedUntilMillis: Long = 0
)

/**
 * Convenience composite for service-layer operations that need
 * both account-level and phone-credential-level fields.
 */
data class StoredUser(
    val accountId: Long,
    val phone: String,
    val passwordSalt: String,
    val passwordHash: String,
    val failedLoginCount: Int = 0,
    val lockedUntilMillis: Long = 0,
    val deletionRequestedAtMillis: Long? = null,
    val createdAtMillis: Long
)

data class StoredSmsCode(
    val phone: String,
    val codeHash: String,
    val expiresAtMillis: Long,
    val failedAttempts: Int = 0,
    val invalidated: Boolean = false,
    val deviceId: String = "",
    val ipAddress: String = "",
    val purpose: String = "DEFAULT",
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
    fun findUser(phone: String): StoredUser?
    fun findUserByAccountId(accountId: Long): StoredUser?
    fun findAccount(accountId: Long): StoredAccount?
    fun createUser(user: StoredUser): Boolean
    fun updateUser(user: StoredUser)
    fun accountsPendingDeletion(): List<StoredAccount>
    fun deleteAccount(accountId: Long)

    fun upsertSmsCode(record: StoredSmsCode)
    fun findSmsCode(phone: String): StoredSmsCode?
    fun updateSmsCode(record: StoredSmsCode)
    fun deleteSmsCode(phone: String)
    fun recordSmsIssue(scopeType: String, scopeValue: String, issuedAtMillis: Long)
    fun countSmsIssues(scopeType: String, scopeValue: String, sinceMillis: Long): Int
    fun latestSmsIssueMillis(scopeType: String, scopeValue: String): Long?

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
        smsCodePhoneToDelete: String?,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken>

    fun completePhoneLink(
        ticketHash: String,
        targetAccountId: Long,
        phone: String,
        passwordSalt: String,
        passwordHash: String,
        deviceId: String,
        ipAddress: String,
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
}


class InMemoryAccountStore : AccountStore {
    private var nextAccountId = 1L
    private val accounts = mutableMapOf<Long, StoredAccount>()
    private val phoneCredentials = mutableMapOf<Long, StoredPhoneCredential>()
    private val phoneIndex = mutableMapOf<String, Long>() // phone -> accountId
    private val smsCodes = mutableMapOf<String, StoredSmsCode>()
    private val smsIssues = mutableListOf<SmsIssue>()
    private val sessions = mutableMapOf<String, StoredSession>()
    private val devices = mutableMapOf<Pair<Long, String>, StoredRegisteredDevice>()
    private val wechatIdentities = mutableMapOf<Long, StoredWechatIdentity>()
    private val oneTimeTickets = mutableMapOf<String, StoredOneTimeTicket>()

    override fun findUser(phone: String): StoredUser? {

        val accountId = phoneIndex[phone] ?: return null
        return findUserByAccountId(accountId)
    }

    override fun findUserByAccountId(accountId: Long): StoredUser? {
        val account = accounts[accountId] ?: return null
        val credential = phoneCredentials[accountId]
        return StoredUser(
            accountId = account.accountId,
            phone = credential?.phone.orEmpty(),
            passwordSalt = credential?.passwordSalt.orEmpty(),
            passwordHash = credential?.passwordHash.orEmpty(),
            failedLoginCount = credential?.failedLoginCount ?: 0,
            lockedUntilMillis = credential?.lockedUntilMillis ?: 0,
            deletionRequestedAtMillis = account.deletionRequestedAtMillis,
            createdAtMillis = account.createdAtMillis
        )
    }

    override fun findAccount(accountId: Long): StoredAccount? = accounts[accountId]

    override fun createUser(user: StoredUser): Boolean {
        if (phoneIndex.containsKey(user.phone)) return false
        val accountId = nextAccountId++
        accounts[accountId] = StoredAccount(
            accountId = accountId,
            deletionRequestedAtMillis = user.deletionRequestedAtMillis,
            createdAtMillis = user.createdAtMillis
        )
        phoneCredentials[accountId] = StoredPhoneCredential(
            accountId = accountId,
            phone = user.phone,
            passwordSalt = user.passwordSalt,
            passwordHash = user.passwordHash,
            failedLoginCount = user.failedLoginCount,
            lockedUntilMillis = user.lockedUntilMillis
        )
        phoneIndex[user.phone] = accountId
        return true
    }

    override fun updateUser(user: StoredUser) {
        val accountId = user.accountId.takeIf { it > 0 } ?: phoneIndex[user.phone] ?: return
        accounts[accountId] = StoredAccount(
            accountId = accountId,
            deletionRequestedAtMillis = user.deletionRequestedAtMillis,
            createdAtMillis = user.createdAtMillis
        )
        if (user.phone.isNotBlank()) {
            phoneCredentials[accountId] = StoredPhoneCredential(
                accountId = accountId,
                phone = user.phone,
                passwordSalt = user.passwordSalt,
                passwordHash = user.passwordHash,
                failedLoginCount = user.failedLoginCount,
                lockedUntilMillis = user.lockedUntilMillis
            )
            phoneIndex[user.phone] = accountId
        }
    }

    override fun accountsPendingDeletion(): List<StoredAccount> {
        return accounts.values.filter { it.deletionRequestedAtMillis != null }
    }

    override fun deleteAccount(accountId: Long) {
        val credential = phoneCredentials.remove(accountId)
        if (credential != null) phoneIndex.remove(credential.phone)
        accounts.remove(accountId)
        sessions.values.removeAll { it.accountId == accountId }
        devices.keys.removeAll { it.first == accountId }
        wechatIdentities.remove(accountId)
    }


    override fun upsertSmsCode(record: StoredSmsCode) {
        smsCodes[record.phone] = record
    }

    override fun findSmsCode(phone: String): StoredSmsCode? = smsCodes[phone]

    override fun updateSmsCode(record: StoredSmsCode) {
        smsCodes[record.phone] = record
    }

    override fun deleteSmsCode(phone: String) {
        smsCodes.remove(phone)
    }

    override fun recordSmsIssue(scopeType: String, scopeValue: String, issuedAtMillis: Long) {
        smsIssues += SmsIssue(scopeType, scopeValue, issuedAtMillis)
    }

    override fun countSmsIssues(scopeType: String, scopeValue: String, sinceMillis: Long): Int {
        return smsIssues.count {
            it.scopeType == scopeType &&
                it.scopeValue == scopeValue &&
                it.issuedAtMillis >= sinceMillis
        }
    }

    override fun latestSmsIssueMillis(scopeType: String, scopeValue: String): Long? {
        return smsIssues
            .filter { it.scopeType == scopeType && it.scopeValue == scopeValue }
            .maxOfOrNull { it.issuedAtMillis }
    }

    override fun createSession(session: StoredSession) {
        sessions[session.tokenHash] = session
    }

    override fun findSession(tokenHash: String): StoredSession? = sessions[tokenHash]

    override fun deleteSession(tokenHash: String) {
        sessions.remove(tokenHash)
    }

    override fun deleteSessionsForAccount(accountId: Long) {
        sessions.values.removeAll { it.accountId == accountId }
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
        smsCodePhoneToDelete: String?,
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

        createSession(
            StoredSession(
                tokenHash = tokenHash,
                accountId = targetAccountId,
                deviceId = deviceId,
                issuedAtMillis = now
            )
        )
        smsCodePhoneToDelete?.let(smsCodes::remove)

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
    override fun completePhoneLink(
        ticketHash: String,
        targetAccountId: Long,
        phone: String,
        passwordSalt: String,
        passwordHash: String,
        deviceId: String,
        ipAddress: String,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken> {
        val ticket = oneTimeTickets[ticketHash]
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        if (ticket.ticketType != "PHONE_LINK" || ticket.expiresAtMillis < now || ticket.accountId != targetAccountId) {
            return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        }
        if (ticket.usedAtMillis != null) {
            return AccountResult.Failure(AccountError.TICKET_ALREADY_USED)
        }
        if (phoneCredentials.containsKey(targetAccountId)) {
            return AccountResult.Failure(AccountError.PHONE_ALREADY_LINKED)
        }
        if (phoneIndex.containsKey(phone)) {
            return AccountResult.Failure(AccountError.PHONE_ALREADY_REGISTERED)
        }

        val token = tokenGenerator()
        val tokenHash = hashStoredToken(token)

        oneTimeTickets[ticketHash] = ticket.copy(usedAtMillis = now)
        phoneCredentials[targetAccountId] = StoredPhoneCredential(
            accountId = targetAccountId,
            phone = phone,
            passwordSalt = passwordSalt,
            passwordHash = passwordHash,
            failedLoginCount = 0,
            lockedUntilMillis = 0
        )
        phoneIndex[phone] = targetAccountId

        deleteSessionsForAccount(targetAccountId)

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

        createSession(
            StoredSession(
                tokenHash = tokenHash,
                accountId = targetAccountId,
                deviceId = deviceId,
                issuedAtMillis = now
            )
        )

        val account = accounts[targetAccountId]
        val wechatIdentity = wechatIdentities[targetAccountId]
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
                wechatLinked = wechatIdentity != null,
                nickname = wechatIdentity?.nickname,
                avatarUrl = wechatIdentity?.avatarUrl
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

        val targetPhoneCred = phoneCredentials[targetAccountId]
        val sourcePhoneCred = phoneCredentials[sourceAccountId]
        if (targetPhoneCred != null && sourcePhoneCred != null) {
            return AccountResult.Failure(AccountError.MERGE_BLOCKED)
        }

        val targetWechat = wechatIdentities[targetAccountId]
        val sourceWechat = wechatIdentities[sourceAccountId]
        if (targetWechat != null && sourceWechat != null) {
            return AccountResult.Failure(AccountError.MERGE_BLOCKED)
        }

        // Transfer credentials
        if (sourcePhoneCred != null) {
            phoneCredentials[targetAccountId] = sourcePhoneCred.copy(accountId = targetAccountId)
            phoneCredentials.remove(sourceAccountId)
            phoneIndex[sourcePhoneCred.phone] = targetAccountId
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

        val finalPhoneCred = phoneCredentials[targetAccountId]
        val finalWechat = wechatIdentities[targetAccountId]

        return AccountResult.Success(
            AccountToken(
                accountId = targetAccountId,
                phone = finalPhoneCred?.phone,
                token = token,
                wechatLinked = finalWechat != null,
                nickname = finalWechat?.nickname,
                avatarUrl = finalWechat?.avatarUrl
            )
        )
    }




    private data class SmsIssue(
        val scopeType: String,
        val scopeValue: String,
        val issuedAtMillis: Long
    )

    private fun hashStoredToken(token: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(digest)
    }
}
