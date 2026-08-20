package com.bks.backend.account

@Suppress("LongParameterList")
internal class JdbcAccountTransactionStore(
    private val credentialTransactions: JdbcWechatCredentialTransactions,
    private val mergeTransaction: JdbcAccountMergeTransaction
) : AccountWechatTransactionStore {
    override fun registerWechatAccount(
        ticketHash: String, appId: String, openid: String, unionid: String?, nickname: String?, avatarUrl: String?,
        deviceId: String, ipAddress: String, now: Long, tokenGenerator: () -> String
    ): AccountResult<AccountToken> = credentialTransactions.registerWechatAccount(
        ticketHash, appId, openid, unionid, nickname, avatarUrl, deviceId, ipAddress, now, tokenGenerator
    )

    override fun linkWechatIdentity(
        ticketHash: String, targetAccountId: Long, phone: String?, appId: String, openid: String, unionid: String?,
        nickname: String?, avatarUrl: String?, deviceId: String, ipAddress: String,
        verificationCodeToDelete: StoredVerificationCode?, now: Long, tokenGenerator: () -> String
    ): AccountResult<AccountToken> = credentialTransactions.linkWechatIdentity(
        ticketHash, targetAccountId, phone, appId, openid, unionid, nickname, avatarUrl,
        deviceId, ipAddress, verificationCodeToDelete, now, tokenGenerator
    )

    override fun mergeAccounts(
        ticketHash: String, targetAccountId: Long, deviceId: String, ipAddress: String, now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken> = mergeTransaction.mergeAccounts(
        ticketHash, targetAccountId, deviceId, ipAddress, now, tokenGenerator
    )

    override fun unlinkWechatIdentity(
        accountId: Long, phone: String?, deviceId: String, ipAddress: String,
        verificationCodeToDelete: StoredVerificationCode?, now: Long, tokenGenerator: () -> String
    ): AccountResult<AccountToken> = credentialTransactions.unlinkWechatIdentity(
        accountId, deviceId, ipAddress, verificationCodeToDelete, now, tokenGenerator
    )
}
