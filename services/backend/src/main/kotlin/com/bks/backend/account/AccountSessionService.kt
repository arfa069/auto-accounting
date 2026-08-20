package com.bks.backend.account


import com.bks.api.AccountDeletionStatusContract
import com.bks.api.AccountSessionResponseContract
import com.bks.api.TICKET_VALIDITY_MILLIS
import com.bks.api.WechatAuthResultContract
import com.bks.api.WechatExchangeResponseContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64

internal class AccountSessionService(
    context: AccountServiceContext
) : AccountServiceComponent(context) {
    fun phoneIdentifier(accountId: Long): String? {
        return store.findIdentifiersByAccountId(accountId)
            .firstOrNull { it.identifierType == "PHONE" }
            ?.rawValue
    }


    fun registerDevice(
        accountId: Long,
        deviceId: String,
        ipAddress: String,
        now: Long
    ) {
        if (deviceId.isBlank()) return
        if (store.findAccount(accountId)?.deletionRequestedAtMillis != null) return
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

    fun issueSession(
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


    fun verifiedAccount(token: String): AccountToken? {
        return (verifyToken(token) as? AccountResult.Success)?.value
    }

    fun verifyToken(token: String): AccountResult<AccountToken> {
        if (token.isBlank()) return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val session = store.findSession(hashToken(token))
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        if (session.expiresAtMillis <= clock.millis()) {
            store.deleteSession(session.tokenHash)
            return AccountResult.Failure(AccountError.TOKEN_INVALID)
        }
        val account = store.findAccount(session.accountId)
            ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val identifiers = identifierContracts(account.accountId)
        val phone = identifiers.firstOrNull {
            it.type == com.bks.api.AccountIdentifierTypeContract.PHONE
        }?.value
        val primaryIdentifier = account.primaryIdentifierType?.let { primaryType ->
            identifiers.firstOrNull { it.type.name == primaryType }
        }
        val wechatIdentity = store.findWechatIdentityByAccountId(account.accountId)
        val profile = store.findProfileByAccountId(account.accountId)
        return AccountResult.Success(
            AccountToken(
                accountId = account.accountId,
                accountUuid = account.publicId,
                primaryIdentifier = primaryIdentifier,
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

    fun identifierContracts(accountId: Long): List<com.bks.api.AccountIdentifierContract> {
        return store.findIdentifiersByAccountId(accountId).map { identifier ->
            com.bks.api.AccountIdentifierContract(
                type = com.bks.api.AccountIdentifierTypeContract.valueOf(identifier.identifierType),
                value = identifier.rawValue,
                verified = identifier.verified
            )
        }
    }

    fun enrichAccountToken(token: AccountToken): AccountToken {
        val account = store.findAccount(token.accountId)
        val identifiers = identifierContracts(token.accountId)
        val phone = identifiers.firstOrNull {
            it.type == com.bks.api.AccountIdentifierTypeContract.PHONE
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

    fun primaryIdentifierForAccount(accountId: Long): com.bks.api.AccountIdentifierContract? {
        val account = store.findAccount(accountId) ?: return null
        val primaryType = account.primaryIdentifierType ?: return null
        return identifierContracts(accountId).firstOrNull { it.type.name == primaryType }
    }

}

