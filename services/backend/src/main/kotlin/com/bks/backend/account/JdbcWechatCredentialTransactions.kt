package com.bks.backend.account

import com.bks.api.LedgerSyncEntityTypeContract
import com.bks.api.LedgerSyncJsonContracts
import com.bks.api.LedgerSyncPayloadContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
@Suppress("NestedBlockDepth")
internal class JdbcWechatCredentialTransactions(
    context: JdbcAccountStoreContext
) : JdbcAccountStoreComponent(context) {
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
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
    ): AccountResult<AccountToken> = connection().use { connection ->
        connection.autoCommit = false
        try {
            val ticket = queryOneTimeTicket(connection, ticketHash)
                ?: run {
                    connection.rollback()
                    return AccountResult.Failure(AccountError.TICKET_EXPIRED)
                }
            if (ticket.ticketType != "WECHAT_AUTH" || ticket.expiresAtMillis < now) {
                connection.rollback()
                return AccountResult.Failure(AccountError.TICKET_EXPIRED)
            }
            if (ticket.usedAtMillis != null) {
                connection.rollback()
                return AccountResult.Failure(AccountError.TICKET_ALREADY_USED)
            }

            val token = tokenGenerator()

            val rowsUpdated = markOneTimeTicketUsed(connection, ticketHash, now)
            if (rowsUpdated == 0) {
                connection.rollback()
                return AccountResult.Failure(AccountError.TICKET_ALREADY_USED)
            }

            val existingIdentity = queryWechatIdentityByUnionid(connection, unionid)
                ?: queryWechatIdentityByOpenid(connection, appId, openid)
            if (existingIdentity != null) {
                connection.rollback()
                return AccountResult.Failure(AccountError.WECHAT_ALREADY_LINKED)
            }

            var accountId: Long = -1
            val publicId = UUID.randomUUID().toString()
            connection.prepareStatement(
                """
                INSERT INTO accounts (public_id, deletion_requested_at_millis, created_at_millis)
                VALUES (?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.setString(1, publicId)
                statement.setNullableLong(2, null)
                statement.setLong(3, now)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    if (keys.next()) accountId = keys.getLong(1)
                }
            }
            if (accountId == -1L) {
                connection.rollback()
                return AccountResult.Failure(AccountError.INVALID_REQUEST)
            }

            insertWechatIdentity(
                connection,
                StoredWechatIdentity(
                    accountId = accountId,
                    appId = appId,
                    openid = openid,
                    unionid = unionid,
                    nickname = nickname,
                    avatarUrl = avatarUrl,
                    createdAtMillis = now,
                    updatedAtMillis = now
                )
            )

            if (deviceId.isNotBlank()) {
                upsertRegisteredDevice(
                    connection,
                    StoredRegisteredDevice(
                        accountId = accountId,
                        deviceId = deviceId,
                        firstSeenAtMillis = now,
                        lastSeenAtMillis = now,
                        ipAddress = ipAddress
                    )
                )
            }

            connection.prepareStatement("DELETE FROM account_sessions WHERE account_id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeUpdate()
            }
            val tokenHash = hashTokenString(token)
            insertSession(
                connection,
                StoredSession(
                    tokenHash = tokenHash,
                    accountId = accountId,
                    deviceId = deviceId,
                    issuedAtMillis = now
                )
            )

            connection.commit()
            AccountResult.Success(
                AccountToken(
                    accountId = accountId,
                    phone = null,
                    token = token,
                    wechatLinked = true,
                    nickname = nickname,
                    avatarUrl = avatarUrl
                )
            )
        } catch (error: SQLException) {
            connection.rollback()
            if (error.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                AccountResult.Failure(AccountError.WECHAT_ALREADY_LINKED)
            } else {
                throw error
            }
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
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
    ): AccountResult<AccountToken> = connection().use { connection ->
        connection.autoCommit = false
        try {
            val ticket = queryOneTimeTicket(connection, ticketHash)
                ?: run {
                    connection.rollback()
                    return AccountResult.Failure(AccountError.TICKET_EXPIRED)
                }
            if (ticket.ticketType != "WECHAT_AUTH" || ticket.expiresAtMillis < now) {
                connection.rollback()
                return AccountResult.Failure(AccountError.TICKET_EXPIRED)
            }
            if (ticket.usedAtMillis != null) {
                connection.rollback()
                return AccountResult.Failure(AccountError.TICKET_ALREADY_USED)
            }

            val token = tokenGenerator()

            val rowsUpdated = markOneTimeTicketUsed(connection, ticketHash, now)
            if (rowsUpdated == 0) {
                connection.rollback()
                return AccountResult.Failure(AccountError.TICKET_ALREADY_USED)
            }

            val targetExisting = queryWechatIdentityByAccountId(connection, targetAccountId)
            if (targetExisting != null) {
                connection.rollback()
                return AccountResult.Failure(AccountError.WECHAT_ALREADY_LINKED)
            }

            val existingIdentity = queryWechatIdentityByUnionid(connection, unionid)
                ?: queryWechatIdentityByOpenid(connection, appId, openid)
            if (existingIdentity != null && existingIdentity.accountId != targetAccountId) {
                connection.rollback()
                return AccountResult.Failure(AccountError.WECHAT_ALREADY_LINKED)
            }

            insertWechatIdentity(
                connection,
                StoredWechatIdentity(
                    accountId = targetAccountId,
                    appId = appId,
                    openid = openid,
                    unionid = unionid,
                    nickname = nickname,
                    avatarUrl = avatarUrl,
                    createdAtMillis = now,
                    updatedAtMillis = now
                )
            )

            if (deviceId.isNotBlank()) {
                upsertRegisteredDevice(
                    connection,
                    StoredRegisteredDevice(
                        accountId = targetAccountId,
                        deviceId = deviceId,
                        firstSeenAtMillis = now,
                        lastSeenAtMillis = now,
                        ipAddress = ipAddress
                    )
                )
            }

            connection.prepareStatement("DELETE FROM account_sessions WHERE account_id = ?").use { statement ->
                statement.setLong(1, targetAccountId)
                statement.executeUpdate()
            }
            val tokenHash = hashTokenString(token)
            insertSession(
                connection,
                StoredSession(
                    tokenHash = tokenHash,
                    accountId = targetAccountId,
                    deviceId = deviceId,
                    issuedAtMillis = now
                )
            )
            verificationCodeToDelete?.let { code ->
                deleteVerificationCode(connection, code.identifierType, code.normalizedIdentifier, code.purpose)
            }

            val account = queryAccount(connection, targetAccountId)
            val deletionStatus = account?.deletionRequestedAtMillis?.let { requestedAt ->
                AccountDeletionStatus(
                    accountId = targetAccountId,
                    phone = phone,
                    requestedAtMillis = requestedAt,
                    finalDeletionAtMillis = requestedAt + AccountService.ACCOUNT_DELETION_COOLING_OFF_MILLIS
                )
            }

            connection.commit()
            AccountResult.Success(
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
        } catch (error: SQLException) {
            connection.rollback()
            if (error.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                AccountResult.Failure(AccountError.WECHAT_ALREADY_LINKED)
            } else {
                throw error
            }
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    @Suppress("LongParameterList")
    fun unlinkWechatIdentity(
        accountId: Long,
        deviceId: String,
        ipAddress: String,
        verificationCodeToDelete: StoredVerificationCode?,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken> = connection().use { connection ->
        connection.autoCommit = false
        try {
            val account = lockAccountsForUpdate(connection, accountId, accountId).singleOrNull()
                ?: run {
                    connection.rollback()
                    return AccountResult.Failure(AccountError.TOKEN_INVALID)
                }
            queryPasswordCredential(connection, accountId)
                ?: run {
                    connection.rollback()
                    return AccountResult.Failure(AccountError.LAST_LOGIN_METHOD_CANNOT_UNLINK)
                }
            val currentIdentifiers = queryIdentifiersByAccountId(connection, accountId)
            if (currentIdentifiers.isEmpty()) {
                connection.rollback()
                return AccountResult.Failure(AccountError.LAST_LOGIN_METHOD_CANNOT_UNLINK)
            }
            if (account.deletionRequestedAtMillis != null) {
                connection.rollback()
                return AccountResult.Failure(AccountError.ACCOUNT_DELETION_PENDING)
            }
            if (queryWechatIdentityByAccountId(connection, accountId) == null) {
                connection.rollback()
                return AccountResult.Failure(AccountError.INVALID_REQUEST)
            }

            val token = tokenGenerator()
            val tokenHash = hashTokenString(token)

            connection.prepareStatement(
                "DELETE FROM account_wechat_identities WHERE account_id = ?"
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "DELETE FROM account_sessions WHERE account_id = ?"
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.executeUpdate()
            }
            if (deviceId.isNotBlank()) {
                upsertRegisteredDevice(
                    connection,
                    StoredRegisteredDevice(
                        accountId = accountId,
                        deviceId = deviceId,
                        firstSeenAtMillis = now,
                        lastSeenAtMillis = now,
                        ipAddress = ipAddress
                    )
                )
            }
            insertSession(
                connection,
                StoredSession(
                    tokenHash = tokenHash,
                    accountId = accountId,
                    deviceId = deviceId,
                    issuedAtMillis = now
                )
            )
            verificationCodeToDelete?.let { code ->
                deleteVerificationCode(connection, code.identifierType, code.normalizedIdentifier, code.purpose)
            }

            connection.commit()
            AccountResult.Success(
                AccountToken(
                    accountId = accountId,
                    phone = currentIdentifiers.firstOrNull { it.identifierType == "PHONE" }?.normalizedValue,
                    token = token,
                    wechatLinked = false
                )
            )
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

}

