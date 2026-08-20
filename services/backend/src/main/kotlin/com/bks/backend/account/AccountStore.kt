@file:Suppress("LongParameterList")

package com.bks.backend.account

interface AccountLifecycleStore {
    fun findAccount(accountId: Long): StoredAccount?
    fun updateAccountDeletionRequestedAt(accountId: Long, requestedAtMillis: Long?)
    fun cancelAccountDeletion(accountId: Long): Boolean
    fun claimAccountDeletion(accountId: Long, cutoffMillis: Long, claimedAtMillis: Long): Boolean
    fun accountsPendingDeletion(): List<StoredAccount>
    fun deleteAccount(accountId: Long)
}

interface AccountIdentifierStore {
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
        tokenGenerator: () -> String,
        replaceExisting: Boolean = false
    ): AccountResult<AccountToken>
}

interface AccountProfileStore {
    fun findProfileByAccountId(accountId: Long): StoredAccountProfile?
    fun upsertProfile(profile: StoredAccountProfile)
}

interface AccountVerificationStore {
    fun upsertVerificationCode(code: StoredVerificationCode)
    fun findVerificationCode(identifierType: String, normalizedIdentifier: String, purpose: String): StoredVerificationCode?
    fun deleteVerificationCode(identifierType: String, normalizedIdentifier: String, purpose: String)
    fun recordVerificationSendLog(channelType: String, scopeType: String, scopeValue: String, issuedAtMillis: Long)
    fun countVerificationSendLogs(channelType: String, scopeType: String, scopeValue: String, sinceMillis: Long): Int
    fun latestVerificationSendLogMillis(channelType: String, scopeType: String, scopeValue: String): Long?
}

interface AccountSessionStore {
    fun createSession(session: StoredSession)
    fun findSession(tokenHash: String): StoredSession?
    fun deleteSession(tokenHash: String)
    fun deleteSessionsForAccount(accountId: Long)

    fun upsertRegisteredDevice(device: StoredRegisteredDevice)
    fun registeredDevices(accountId: Long): List<StoredRegisteredDevice>
}

interface AccountWechatStore {
    fun findWechatIdentityByOpenid(appId: String, openid: String): StoredWechatIdentity?
    fun findWechatIdentityByUnionid(unionid: String): StoredWechatIdentity?
    fun findWechatIdentityByAccountId(accountId: Long): StoredWechatIdentity?
    fun claimWechatIdentity(identity: StoredWechatIdentity): WechatIdentityClaimResult
    fun upsertWechatIdentity(identity: StoredWechatIdentity)
    fun deleteWechatIdentity(accountId: Long)

    fun createOneTimeTicket(ticket: StoredOneTimeTicket)
    fun findOneTimeTicket(ticketHash: String): StoredOneTimeTicket?
    fun markOneTimeTicketUsed(ticketHash: String, usedAtMillis: Long): Boolean
}

interface AccountWechatTransactionStore {
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

interface AccountStore :
    AccountLifecycleStore,
    AccountIdentifierStore,
    AccountProfileStore,
    AccountVerificationStore,
    AccountSessionStore,
    AccountWechatStore,
    AccountWechatTransactionStore
