@file:Suppress("NestedBlockDepth", "TooManyFunctions", "LargeClass", "LongMethod", "CyclomaticComplexMethod", "LongParameterList")


package com.autoaccounting.backend.account

import com.autoaccounting.backend.jdbcConnection
import com.autoaccounting.backend.runBackendMigrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.Base64

class JdbcAccountStore(
    private val jdbcUrl: String,
    private val username: String = "",
    private val password: String = ""
) : AccountStore {
    init {
        runBackendMigrations(jdbcUrl, username, password)
    }

    override fun findUser(phone: String): StoredUser? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT a.account_id, p.phone, p.password_salt, p.password_hash, p.failed_login_count,
                   p.locked_until_millis, a.deletion_requested_at_millis, a.created_at_millis
            FROM account_phone_credentials p
            JOIN accounts a ON a.account_id = p.account_id
            WHERE p.phone = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, phone)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredUser() else null
            }
        }
    }

    override fun findUserByAccountId(accountId: Long): StoredUser? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT a.account_id, p.phone, p.password_salt, p.password_hash, p.failed_login_count,
                   p.locked_until_millis, a.deletion_requested_at_millis, a.created_at_millis
            FROM accounts a
            LEFT JOIN account_phone_credentials p ON p.account_id = a.account_id
            WHERE a.account_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredUser() else null
            }
        }
    }

    override fun findAccount(accountId: Long): StoredAccount? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT account_id, deletion_requested_at_millis, created_at_millis
            FROM accounts
            WHERE account_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredAccount(
                        accountId = rs.getLong("account_id"),
                        deletionRequestedAtMillis = rs.getNullableLong("deletion_requested_at_millis"),
                        createdAtMillis = rs.getLong("created_at_millis")
                    )
                } else {
                    null
                }
            }
        }
    }

    override fun createUser(user: StoredUser): Boolean = connection().use { connection ->
        connection.autoCommit = false
        try {
            var accountId: Long = -1
            connection.prepareStatement(
                """
                INSERT INTO accounts (deletion_requested_at_millis, created_at_millis)
                VALUES (?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.setNullableLong(1, user.deletionRequestedAtMillis)
                statement.setLong(2, user.createdAtMillis)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    if (keys.next()) accountId = keys.getLong(1)
                }
            }
            if (accountId == -1L) {
                connection.rollback()
                return false
            }
            connection.prepareStatement(
                """
                INSERT INTO account_phone_credentials (
                    account_id, phone, password_salt, password_hash,
                    failed_login_count, locked_until_millis, updated_at_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.setString(2, user.phone)
                statement.setString(3, user.passwordSalt)
                statement.setString(4, user.passwordHash)
                statement.setInt(5, user.failedLoginCount)
                statement.setLong(6, user.lockedUntilMillis)
                statement.setLong(7, user.createdAtMillis)
                statement.executeUpdate()
            }
            connection.commit()
            true
        } catch (error: java.sql.SQLException) {
            connection.rollback()
            if (error.sqlState == UNIQUE_VIOLATION_SQL_STATE) false else throw error
        } finally {
            connection.autoCommit = true
        }
    }

    override fun updateUser(user: StoredUser) {
        connection().use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    UPDATE accounts
                    SET deletion_requested_at_millis = ?
                    WHERE account_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setNullableLong(1, user.deletionRequestedAtMillis)
                    statement.setLong(2, user.accountId)
                    statement.executeUpdate()
                }
                if (user.phone.isNotBlank()) {
                    connection.prepareStatement(
                        """
                        UPDATE account_phone_credentials
                        SET failed_login_count = ?,
                            locked_until_millis = ?,
                            password_salt = ?,
                            password_hash = ?,
                            updated_at_millis = ?
                        WHERE account_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setInt(1, user.failedLoginCount)
                        statement.setLong(2, user.lockedUntilMillis)
                        statement.setString(3, user.passwordSalt)
                        statement.setString(4, user.passwordHash)
                        statement.setLong(5, System.currentTimeMillis())
                        statement.setLong(6, user.accountId)
                        statement.executeUpdate()
                    }
                }
                connection.commit()
            } catch (error: java.sql.SQLException) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override fun accountsPendingDeletion(): List<StoredAccount> = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT account_id, deletion_requested_at_millis, created_at_millis
            FROM accounts
            WHERE deletion_requested_at_millis IS NOT NULL
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StoredAccount(
                                accountId = rs.getLong("account_id"),
                                deletionRequestedAtMillis = rs.getNullableLong("deletion_requested_at_millis"),
                                createdAtMillis = rs.getLong("created_at_millis")
                            )
                        )
                    }
                }
            }
        }
    }

    override fun deleteAccount(accountId: Long) {
        connection().use { connection ->
            connection.prepareStatement(
                "DELETE FROM accounts WHERE account_id = ?"
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.executeUpdate()
            }
        }
    }

    override fun upsertSmsCode(record: StoredSmsCode) {
        connection().use { connection ->
            val updated = connection.prepareStatement(
                """
                UPDATE account_sms_codes
                SET code_hash = ?, expires_at_millis = ?, failed_attempts = ?,
                    invalidated = ?, device_id = ?, ip_address = ?, purpose = ?, context_key = ?
                WHERE phone = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, record.codeHash)
                statement.setLong(2, record.expiresAtMillis)
                statement.setInt(3, record.failedAttempts)
                statement.setBoolean(4, record.invalidated)
                statement.setString(5, record.deviceId)
                statement.setString(6, record.ipAddress)
                statement.setString(7, record.purpose)
                statement.setString(8, record.contextKey)
                statement.setString(9, record.phone)
                statement.executeUpdate()
            }
            if (updated == 0) {
                connection.prepareStatement(
                    """
                    INSERT INTO account_sms_codes (
                        phone, code_hash, expires_at_millis, failed_attempts,
                        invalidated, device_id, ip_address, purpose, context_key
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, record.phone)
                    statement.setString(2, record.codeHash)
                    statement.setLong(3, record.expiresAtMillis)
                    statement.setInt(4, record.failedAttempts)
                    statement.setBoolean(5, record.invalidated)
                    statement.setString(6, record.deviceId)
                    statement.setString(7, record.ipAddress)
                    statement.setString(8, record.purpose)
                    statement.setString(9, record.contextKey)
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun findSmsCode(phone: String): StoredSmsCode? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT phone, code_hash, expires_at_millis, failed_attempts,
                   invalidated, device_id, ip_address, purpose, context_key
            FROM account_sms_codes
            WHERE phone = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, phone)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredSmsCode(
                        phone = rs.getString("phone"),
                        codeHash = rs.getString("code_hash"),
                        expiresAtMillis = rs.getLong("expires_at_millis"),
                        failedAttempts = rs.getInt("failed_attempts"),
                        invalidated = rs.getBoolean("invalidated"),
                        deviceId = rs.getString("device_id").orEmpty(),
                        ipAddress = rs.getString("ip_address").orEmpty(),
                        purpose = rs.getString("purpose").orEmpty(),
                        contextKey = rs.getString("context_key")
                    )
                } else {
                    null
                }
            }
        }
    }

    override fun updateSmsCode(record: StoredSmsCode) {
        upsertSmsCode(record)
    }

    override fun deleteSmsCode(phone: String) {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM account_sms_codes WHERE phone = ?").use { statement ->
                statement.setString(1, phone)
                statement.executeUpdate()
            }
        }
    }

    override fun recordSmsIssue(scopeType: String, scopeValue: String, issuedAtMillis: Long) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO account_sms_issues (scope_type, scope_value, issued_at_millis)
                VALUES (?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, scopeType)
                statement.setString(2, scopeValue)
                statement.setLong(3, issuedAtMillis)
                statement.executeUpdate()
            }
        }
    }

    override fun countSmsIssues(scopeType: String, scopeValue: String, sinceMillis: Long): Int {
        return connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*) AS issue_count
                FROM account_sms_issues
                WHERE scope_type = ? AND scope_value = ? AND issued_at_millis >= ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, scopeType)
                statement.setString(2, scopeValue)
                statement.setLong(3, sinceMillis)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt("issue_count")
                }
            }
        }
    }

    override fun latestSmsIssueMillis(scopeType: String, scopeValue: String): Long? {
        return connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT MAX(issued_at_millis) AS latest_issue
                FROM account_sms_issues
                WHERE scope_type = ? AND scope_value = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, scopeType)
                statement.setString(2, scopeValue)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getNullableLong("latest_issue")
                }
            }
        }
    }

    override fun createSession(session: StoredSession) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO account_sessions (token_hash, account_id, device_id, issued_at_millis)
                VALUES (?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, session.tokenHash)
                statement.setLong(2, session.accountId)
                statement.setString(3, session.deviceId)
                statement.setLong(4, session.issuedAtMillis)
                statement.executeUpdate()
            }
        }
    }

    override fun findSession(tokenHash: String): StoredSession? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT token_hash, account_id, device_id, issued_at_millis
            FROM account_sessions
            WHERE token_hash = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, tokenHash)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredSession(
                        tokenHash = rs.getString("token_hash"),
                        accountId = rs.getLong("account_id"),
                        deviceId = rs.getString("device_id").orEmpty(),
                        issuedAtMillis = rs.getLong("issued_at_millis")
                    )
                } else {
                    null
                }
            }
        }
    }

    override fun deleteSession(tokenHash: String) {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM account_sessions WHERE token_hash = ?").use { statement ->
                statement.setString(1, tokenHash)
                statement.executeUpdate()
            }
        }
    }

    override fun deleteSessionsForAccount(accountId: Long) {
        connection().use { connection ->
            connection.prepareStatement(
                "DELETE FROM account_sessions WHERE account_id = ?"
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.executeUpdate()
            }
        }
    }

    override fun upsertRegisteredDevice(device: StoredRegisteredDevice) {
        connection().use { connection ->
            val updated = connection.prepareStatement(
                """
                UPDATE registered_devices
                SET last_seen_at_millis = ?, ip_address = ?
                WHERE account_id = ? AND device_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, device.lastSeenAtMillis)
                statement.setString(2, device.ipAddress)
                statement.setLong(3, device.accountId)
                statement.setString(4, device.deviceId)
                statement.executeUpdate()
            }
            if (updated == 0) {
                connection.prepareStatement(
                    """
                    INSERT INTO registered_devices (
                        account_id, device_id, first_seen_at_millis,
                        last_seen_at_millis, ip_address
                    ) VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, device.accountId)
                    statement.setString(2, device.deviceId)
                    statement.setLong(3, device.firstSeenAtMillis)
                    statement.setLong(4, device.lastSeenAtMillis)
                    statement.setString(5, device.ipAddress)
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun registeredDevices(accountId: Long): List<StoredRegisteredDevice> = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT account_id, device_id, first_seen_at_millis, last_seen_at_millis, ip_address
            FROM registered_devices
            WHERE account_id = ?
            ORDER BY device_id
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StoredRegisteredDevice(
                                accountId = rs.getLong("account_id"),
                                deviceId = rs.getString("device_id"),
                                firstSeenAtMillis = rs.getLong("first_seen_at_millis"),
                                lastSeenAtMillis = rs.getLong("last_seen_at_millis"),
                                ipAddress = rs.getString("ip_address").orEmpty()
                            )
                        )
                    }
                }
            }
        }
    }

    override fun findWechatIdentityByOpenid(appId: String, openid: String): StoredWechatIdentity? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT account_id, app_id, openid, unionid, nickname, avatar_url, created_at_millis, updated_at_millis
            FROM account_wechat_identities
            WHERE app_id = ? AND openid = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, appId)
            statement.setString(2, openid)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredWechatIdentity() else null
            }
        }
    }

    override fun findWechatIdentityByUnionid(unionid: String): StoredWechatIdentity? {
        if (unionid.isBlank()) return null
        return connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT account_id, app_id, openid, unionid, nickname, avatar_url, created_at_millis, updated_at_millis
                FROM account_wechat_identities
                WHERE unionid = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, unionid)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.toStoredWechatIdentity() else null
                }
            }
        }
    }

    override fun findWechatIdentityByAccountId(accountId: Long): StoredWechatIdentity? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT account_id, app_id, openid, unionid, nickname, avatar_url, created_at_millis, updated_at_millis
            FROM account_wechat_identities
            WHERE account_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredWechatIdentity() else null
            }
        }
    }

    override fun claimWechatIdentity(identity: StoredWechatIdentity): WechatIdentityClaimResult {
        try {
            connection().use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO account_wechat_identities (
                        account_id, app_id, openid, unionid, nickname, avatar_url, created_at_millis, updated_at_millis
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, identity.accountId)
                    statement.setString(2, identity.appId)
                    statement.setString(3, identity.openid)
                    statement.setString(4, identity.unionid)
                    statement.setString(5, identity.nickname)
                    statement.setString(6, identity.avatarUrl)
                    statement.setLong(7, identity.createdAtMillis)
                    statement.setLong(8, identity.updatedAtMillis)
                    statement.executeUpdate()
                }
            }
            return WechatIdentityClaimResult.Claimed
        } catch (error: SQLException) {
            if (error.sqlState != UNIQUE_VIOLATION_SQL_STATE) throw error
            val existingIdentity = findWechatIdentityByAccountId(identity.accountId)
                ?: findWechatIdentityByOpenid(identity.appId, identity.openid)
                ?: identity.unionid?.let(::findWechatIdentityByUnionid)
                ?: throw error
            return WechatIdentityClaimResult.Conflict(existingIdentity)
        }
    }

    override fun upsertWechatIdentity(identity: StoredWechatIdentity) {
        connection().use { connection ->
            val updated = connection.prepareStatement(
                """
                UPDATE account_wechat_identities
                SET app_id = ?, openid = ?, unionid = ?, nickname = ?, avatar_url = ?, updated_at_millis = ?
                WHERE account_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, identity.appId)
                statement.setString(2, identity.openid)
                statement.setString(3, identity.unionid)
                statement.setString(4, identity.nickname)
                statement.setString(5, identity.avatarUrl)
                statement.setLong(6, identity.updatedAtMillis)
                statement.setLong(7, identity.accountId)
                statement.executeUpdate()
            }
            if (updated == 0) {
                connection.prepareStatement(
                    """
                    INSERT INTO account_wechat_identities (
                        account_id, app_id, openid, unionid, nickname, avatar_url, created_at_millis, updated_at_millis
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, identity.accountId)
                    statement.setString(2, identity.appId)
                    statement.setString(3, identity.openid)
                    statement.setString(4, identity.unionid)
                    statement.setString(5, identity.nickname)
                    statement.setString(6, identity.avatarUrl)
                    statement.setLong(7, identity.createdAtMillis)
                    statement.setLong(8, identity.updatedAtMillis)
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun deleteWechatIdentity(accountId: Long) {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM account_wechat_identities WHERE account_id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeUpdate()
            }
        }
    }

    override fun createOneTimeTicket(ticket: StoredOneTimeTicket) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO account_one_time_tickets (
                    ticket_hash, ticket_type, account_id, payload_json, expires_at_millis, used_at_millis
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, ticket.ticketHash)
                statement.setString(2, ticket.ticketType)
                statement.setNullableLong(3, ticket.accountId)
                statement.setString(4, ticket.payloadJson)
                statement.setLong(5, ticket.expiresAtMillis)
                statement.setNullableLong(6, ticket.usedAtMillis)
                statement.executeUpdate()
            }
        }
    }

    override fun findOneTimeTicket(ticketHash: String): StoredOneTimeTicket? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT ticket_hash, ticket_type, account_id, payload_json, expires_at_millis, used_at_millis
            FROM account_one_time_tickets
            WHERE ticket_hash = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ticketHash)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredOneTimeTicket() else null
            }
        }
    }

    override fun markOneTimeTicketUsed(ticketHash: String, usedAtMillis: Long): Boolean = connection().use { connection ->
        markOneTimeTicketUsed(connection, ticketHash, usedAtMillis) > 0
    }

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
            connection.prepareStatement(
                """
                INSERT INTO accounts (deletion_requested_at_millis, created_at_millis)
                VALUES (?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.setNullableLong(1, null)
                statement.setLong(2, now)
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

            val digest = MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
            val tokenHash = Base64.getEncoder().encodeToString(digest)
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

            val digest = MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
            val tokenHash = Base64.getEncoder().encodeToString(digest)
            insertSession(
                connection,
                StoredSession(
                    tokenHash = tokenHash,
                    accountId = targetAccountId,
                    deviceId = deviceId,
                    issuedAtMillis = now
                )
            )
            smsCodePhoneToDelete?.let { deleteSmsCode(connection, it) }

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
    ): AccountResult<AccountToken> = connection().use { connection ->
        connection.autoCommit = false
        try {
            val ticket = queryOneTimeTicket(connection, ticketHash)
                ?: run {
                    connection.rollback()
                    return AccountResult.Failure(AccountError.TICKET_EXPIRED)
                }
            if (ticket.ticketType != "PHONE_LINK" || ticket.expiresAtMillis < now || ticket.accountId != targetAccountId) {
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

            val existingCredential = queryPhoneCredentialByAccountId(connection, targetAccountId)
            if (existingCredential != null) {
                connection.rollback()
                return AccountResult.Failure(AccountError.PHONE_ALREADY_LINKED)
            }

            val phoneUser = queryUserByPhone(connection, phone)
            if (phoneUser != null) {
                connection.rollback()
                return AccountResult.Failure(AccountError.PHONE_ALREADY_REGISTERED)
            }

            insertPhoneCredential(
                connection,
                targetAccountId,
                phone,
                passwordSalt,
                passwordHash,
                now
            )

            connection.prepareStatement("DELETE FROM account_sessions WHERE account_id = ?").use { statement ->
                statement.setLong(1, targetAccountId)
                statement.executeUpdate()
            }

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

            val digest = MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
            val tokenHash = Base64.getEncoder().encodeToString(digest)
            insertSession(
                connection,
                StoredSession(
                    tokenHash = tokenHash,
                    accountId = targetAccountId,
                    deviceId = deviceId,
                    issuedAtMillis = now
                )
            )

            val account = queryAccount(connection, targetAccountId)
            val wechatIdentity = queryWechatIdentityByAccountId(connection, targetAccountId)
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
                    wechatLinked = wechatIdentity != null,
                    nickname = wechatIdentity?.nickname,
                    avatarUrl = wechatIdentity?.avatarUrl
                )
            )
        } catch (error: SQLException) {
            connection.rollback()
            if (error.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                AccountResult.Failure(AccountError.PHONE_ALREADY_REGISTERED)
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

    override fun unlinkWechatIdentity(
        accountId: Long,
        phone: String,
        deviceId: String,
        ipAddress: String,
        smsCodePhoneToDelete: String?,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken> = connection().use { connection ->
        connection.autoCommit = false
        try {
            val account = lockAccountsForUpdateInternal(connection, accountId, accountId).singleOrNull()
                ?: run {
                    connection.rollback()
                    return AccountResult.Failure(AccountError.TOKEN_INVALID)
                }
            val phoneCredential = queryPhoneCredentialByAccountId(connection, accountId)
                ?: run {
                    connection.rollback()
                    return AccountResult.Failure(AccountError.LAST_LOGIN_METHOD_CANNOT_UNLINK)
                }
            if (phoneCredential.phone != phone) {
                connection.rollback()
                return AccountResult.Failure(AccountError.TOKEN_INVALID)
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
            smsCodePhoneToDelete?.let { deleteSmsCode(connection, it) }

            connection.commit()
            AccountResult.Success(
                AccountToken(
                    accountId = accountId,
                    phone = phone,
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

    private fun queryPhoneCredentialByAccountId(connection: Connection, accountId: Long): StoredPhoneCredential? {
        return connection.prepareStatement(
            """
            SELECT account_id, phone, password_salt, password_hash, failed_login_count, locked_until_millis
            FROM account_phone_credentials
            WHERE account_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredPhoneCredential(
                        accountId = rs.getLong("account_id"),
                        phone = rs.getString("phone"),
                        passwordSalt = rs.getString("password_salt"),
                        passwordHash = rs.getString("password_hash"),
                        failedLoginCount = rs.getInt("failed_login_count"),
                        lockedUntilMillis = rs.getLong("locked_until_millis")
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun queryUserByPhone(connection: Connection, phone: String): StoredUser? {
        return connection.prepareStatement(
            """
            SELECT a.account_id, p.phone, p.password_salt, p.password_hash, p.failed_login_count,
                   p.locked_until_millis, a.deletion_requested_at_millis, a.created_at_millis
            FROM account_phone_credentials p
            JOIN accounts a ON a.account_id = p.account_id
            WHERE p.phone = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, phone)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredUser() else null
            }
        }
    }

    private fun insertPhoneCredential(
        connection: Connection,
        accountId: Long,
        phone: String,
        passwordSalt: String,
        passwordHash: String,
        now: Long
    ) {
        connection.prepareStatement(
            """
            INSERT INTO account_phone_credentials (
                account_id, phone, password_salt, password_hash, failed_login_count, locked_until_millis, updated_at_millis
            ) VALUES (?, ?, ?, ?, 0, 0, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, phone)
            statement.setString(3, passwordSalt)
            statement.setString(4, passwordHash)
            statement.setLong(5, now)
            statement.executeUpdate()
        }
    }

    private fun queryOneTimeTicket(connection: Connection, ticketHash: String): StoredOneTimeTicket? {
        return connection.prepareStatement(
            """
            SELECT ticket_hash, ticket_type, account_id, payload_json, expires_at_millis, used_at_millis
            FROM account_one_time_tickets
            WHERE ticket_hash = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ticketHash)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredOneTimeTicket() else null
            }
        }
    }

    private fun markOneTimeTicketUsed(connection: Connection, ticketHash: String, usedAtMillis: Long): Int {
        return connection.prepareStatement(
            """
            UPDATE account_one_time_tickets
            SET used_at_millis = ?
            WHERE ticket_hash = ? AND used_at_millis IS NULL AND expires_at_millis >= ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, usedAtMillis)
            statement.setString(2, ticketHash)
            statement.setLong(3, usedAtMillis)
            statement.executeUpdate()
        }
    }

    private fun queryWechatIdentityByOpenid(connection: Connection, appId: String, openid: String): StoredWechatIdentity? {
        return connection.prepareStatement(
            """
            SELECT account_id, app_id, openid, unionid, nickname, avatar_url, created_at_millis, updated_at_millis
            FROM account_wechat_identities
            WHERE app_id = ? AND openid = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, appId)
            statement.setString(2, openid)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredWechatIdentity() else null
            }
        }
    }

    private fun queryWechatIdentityByUnionid(connection: Connection, unionid: String?): StoredWechatIdentity? {
        if (unionid.isNullOrBlank()) return null
        return connection.prepareStatement(
            """
            SELECT account_id, app_id, openid, unionid, nickname, avatar_url, created_at_millis, updated_at_millis
            FROM account_wechat_identities
            WHERE unionid = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, unionid)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredWechatIdentity() else null
            }
        }
    }

    private fun queryWechatIdentityByAccountId(connection: Connection, accountId: Long): StoredWechatIdentity? {
        return connection.prepareStatement(
            """
            SELECT account_id, app_id, openid, unionid, nickname, avatar_url, created_at_millis, updated_at_millis
            FROM account_wechat_identities
            WHERE account_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredWechatIdentity() else null
            }
        }
    }

    private fun insertWechatIdentity(connection: Connection, identity: StoredWechatIdentity) {
        connection.prepareStatement(
            """
            INSERT INTO account_wechat_identities (
                account_id, app_id, openid, unionid, nickname, avatar_url, created_at_millis, updated_at_millis
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, identity.accountId)
            statement.setString(2, identity.appId)
            statement.setString(3, identity.openid)
            statement.setString(4, identity.unionid)
            statement.setString(5, identity.nickname)
            statement.setString(6, identity.avatarUrl)
            statement.setLong(7, identity.createdAtMillis)
            statement.setLong(8, identity.updatedAtMillis)
            statement.executeUpdate()
        }
    }

    private fun insertSession(connection: Connection, session: StoredSession) {
        connection.prepareStatement(
            """
            INSERT INTO account_sessions (token_hash, account_id, device_id, issued_at_millis)
            VALUES (?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, session.tokenHash)
            statement.setLong(2, session.accountId)
            statement.setString(3, session.deviceId)
            statement.setLong(4, session.issuedAtMillis)
            statement.executeUpdate()
        }
    }

    private fun deleteSmsCode(connection: Connection, phone: String) {
        connection.prepareStatement("DELETE FROM account_sms_codes WHERE phone = ?").use { statement ->
            statement.setString(1, phone)
            statement.executeUpdate()
        }
    }

    private fun upsertRegisteredDevice(connection: Connection, device: StoredRegisteredDevice) {
        val updated = connection.prepareStatement(
            """
            UPDATE registered_devices
            SET last_seen_at_millis = ?, ip_address = ?
            WHERE account_id = ? AND device_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, device.lastSeenAtMillis)
            statement.setString(2, device.ipAddress)
            statement.setLong(3, device.accountId)
            statement.setString(4, device.deviceId)
            statement.executeUpdate()
        }
        if (updated == 0) {
            connection.prepareStatement(
                """
                INSERT INTO registered_devices (account_id, device_id, first_seen_at_millis, last_seen_at_millis, ip_address)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, device.accountId)
                statement.setString(2, device.deviceId)
                statement.setLong(3, device.firstSeenAtMillis)
                statement.setLong(4, device.lastSeenAtMillis)
                statement.setString(5, device.ipAddress)
                statement.executeUpdate()
            }
        }
    }

    private fun queryAccount(connection: Connection, accountId: Long): StoredAccount? {
        return connection.prepareStatement(
            """
            SELECT account_id, deletion_requested_at_millis, created_at_millis
            FROM accounts
            WHERE account_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredAccount() else null
            }
        }
    }


    override fun mergeAccounts(
        ticketHash: String,
        targetAccountId: Long,
        deviceId: String,
        ipAddress: String,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken> = connection().use { connection ->
        connection.autoCommit = false
        try {
            val ticket = findOneTimeTicketWithConnection(connection, ticketHash)
                ?: run { connection.rollback(); return AccountResult.Failure(AccountError.TICKET_EXPIRED) }
            if (ticket.ticketType != "ACCOUNT_MERGE" || ticket.expiresAtMillis < now) {
                connection.rollback()
                return AccountResult.Failure(AccountError.TICKET_EXPIRED)
            }
            if (ticket.usedAtMillis != null) {
                connection.rollback()
                return AccountResult.Failure(AccountError.TICKET_ALREADY_USED)
            }

            val jsonObj = runCatching { Json.parseToJsonElement(ticket.payloadJson).jsonObject }.getOrNull()
                ?: run { connection.rollback(); return AccountResult.Failure(AccountError.TICKET_EXPIRED) }
            val ticketTargetAccountId = jsonObj["targetAccountId"]?.jsonPrimitive?.longOrNull
                ?: run { connection.rollback(); return AccountResult.Failure(AccountError.TICKET_EXPIRED) }
            val sourceAccountId = jsonObj["sourceAccountId"]?.jsonPrimitive?.longOrNull
                ?: run { connection.rollback(); return AccountResult.Failure(AccountError.TICKET_EXPIRED) }

            if (ticketTargetAccountId != targetAccountId || sourceAccountId == targetAccountId) {
                connection.rollback()
                return AccountResult.Failure(AccountError.MERGE_BLOCKED)
            }

            val firstId = minOf(targetAccountId, sourceAccountId)
            val secondId = maxOf(targetAccountId, sourceAccountId)
            val lockedAccounts = lockAccountsForUpdateInternal(connection, firstId, secondId)
            if (lockedAccounts.size < 2) {
                connection.rollback()
                return AccountResult.Failure(AccountError.INVALID_REQUEST)
            }

            if (lockedAccounts.any { it.deletionRequestedAtMillis != null }) {
                connection.rollback()
                return AccountResult.Failure(AccountError.ACCOUNT_DELETION_PENDING)
            }

            val targetPhoneCred = findPhoneCredentialInternal(connection, targetAccountId)
            val sourcePhoneCred = findPhoneCredentialInternal(connection, sourceAccountId)
            if (targetPhoneCred != null && sourcePhoneCred != null) {
                connection.rollback()
                return AccountResult.Failure(AccountError.MERGE_BLOCKED)
            }

            val targetWechat = findWechatIdentityInternal(connection, targetAccountId)
            val sourceWechat = findWechatIdentityInternal(connection, sourceAccountId)
            if (targetWechat != null && sourceWechat != null) {
                connection.rollback()
                return AccountResult.Failure(AccountError.MERGE_BLOCKED)
            }

            // Transfer credentials
            if (sourcePhoneCred != null) {
                connection.prepareStatement(
                    "UPDATE account_phone_credentials SET account_id = ? WHERE account_id = ?"
                ).use { stmt ->
                    stmt.setLong(1, targetAccountId)
                    stmt.setLong(2, sourceAccountId)
                    stmt.executeUpdate()
                }
            }

            if (sourceWechat != null) {
                connection.prepareStatement(
                    "UPDATE account_wechat_identities SET account_id = ? WHERE account_id = ?"
                ).use { stmt ->
                    stmt.setLong(1, targetAccountId)
                    stmt.setLong(2, sourceAccountId)
                    stmt.executeUpdate()
                }
            }

            // Merge cloud config
            mergeCloudConfigsInternal(connection, targetAccountId, sourceAccountId, now)

            // Merge devices
            mergeRegisteredDevicesInternal(connection, targetAccountId, sourceAccountId)

            // Delete source AI logs
            connection.prepareStatement(
                "DELETE FROM ai_categorization_logs WHERE account_id = ?"
            ).use { stmt ->
                stmt.setLong(1, sourceAccountId)
                stmt.executeUpdate()
            }

            // Session rotation & cleanup
            connection.prepareStatement(
                "DELETE FROM account_sessions WHERE account_id = ? OR account_id = ?"
            ).use { stmt ->
                stmt.setLong(1, targetAccountId)
                stmt.setLong(2, sourceAccountId)
                stmt.executeUpdate()
            }

            val token = tokenGenerator()
            val tokenHash = hashTokenString(token)
            connection.prepareStatement(
                "INSERT INTO account_sessions (token_hash, account_id, device_id, issued_at_millis) VALUES (?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, tokenHash)
                stmt.setLong(2, targetAccountId)
                stmt.setString(3, deviceId)
                stmt.setLong(4, now)
                stmt.executeUpdate()
            }

            if (deviceId.isNotBlank()) {
                upsertRegisteredDeviceInternal(
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

            // Delete tickets and source account
            connection.prepareStatement(
                "DELETE FROM account_sms_codes WHERE phone = ?"
            ).use { stmt ->
                stmt.setString(1, sourcePhoneCred?.phone.orEmpty())
                stmt.executeUpdate()
            }

            connection.prepareStatement(
                "DELETE FROM account_one_time_tickets WHERE account_id = ?"
            ).use { stmt ->
                stmt.setLong(1, sourceAccountId)
                stmt.executeUpdate()
            }

            connection.prepareStatement(
                "UPDATE account_one_time_tickets SET used_at_millis = ? WHERE ticket_hash = ?"
            ).use { stmt ->
                stmt.setLong(1, now)
                stmt.setString(2, ticketHash)
                stmt.executeUpdate()
            }

            connection.prepareStatement(
                "DELETE FROM accounts WHERE account_id = ?"
            ).use { stmt ->
                stmt.setLong(1, sourceAccountId)
                stmt.executeUpdate()
            }

            connection.commit()

            val finalPhone = sourcePhoneCred?.phone ?: targetPhoneCred?.phone
            val finalWechat = targetWechat ?: sourceWechat

            AccountResult.Success(
                AccountToken(
                    accountId = targetAccountId,
                    phone = finalPhone,
                    token = token,
                    wechatLinked = finalWechat != null,
                    nickname = finalWechat?.nickname,
                    avatarUrl = finalWechat?.avatarUrl
                )
            )
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    private fun findOneTimeTicketWithConnection(connection: Connection, ticketHash: String): StoredOneTimeTicket? {
        return connection.prepareStatement(
            "SELECT ticket_hash, ticket_type, account_id, payload_json, expires_at_millis, used_at_millis FROM account_one_time_tickets WHERE ticket_hash = ?"
        ).use { stmt ->
            stmt.setString(1, ticketHash)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredOneTimeTicket() else null
            }
        }
    }

    private fun lockAccountsForUpdateInternal(connection: Connection, firstId: Long, secondId: Long): List<StoredAccount> {
        val list = mutableListOf<StoredAccount>()
        connection.prepareStatement(
            """
            SELECT account_id, deletion_requested_at_millis, created_at_millis
            FROM accounts
            WHERE account_id IN (?, ?)
            ORDER BY account_id
            FOR UPDATE
            """.trimIndent()
        ).use { stmt ->
            stmt.setLong(1, firstId)
            stmt.setLong(2, secondId)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    list += rs.toStoredAccount()
                }
            }
        }
        return list
    }

    private fun findPhoneCredentialInternal(connection: Connection, accountId: Long): StoredPhoneCredential? {
        return connection.prepareStatement(
            "SELECT account_id, phone, password_salt, password_hash, failed_login_count, locked_until_millis FROM account_phone_credentials WHERE account_id = ?"
        ).use { stmt ->
            stmt.setLong(1, accountId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredPhoneCredential(
                        accountId = rs.getLong("account_id"),
                        phone = rs.getString("phone"),
                        passwordSalt = rs.getString("password_salt"),
                        passwordHash = rs.getString("password_hash"),
                        failedLoginCount = rs.getInt("failed_login_count"),
                        lockedUntilMillis = rs.getLong("locked_until_millis")
                    )
                } else null
            }
        }
    }

    private fun findWechatIdentityInternal(connection: Connection, accountId: Long): StoredWechatIdentity? {
        return connection.prepareStatement(
            "SELECT account_id, app_id, openid, unionid, nickname, avatar_url, created_at_millis, updated_at_millis FROM account_wechat_identities WHERE account_id = ?"
        ).use { stmt ->
            stmt.setLong(1, accountId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredWechatIdentity() else null
            }
        }
    }

    private fun mergeCloudConfigsInternal(connection: Connection, targetAccountId: Long, sourceAccountId: Long, now: Long) {
        val targetConfig = findCloudConfigInternal(connection, targetAccountId)
        val sourceConfig = findCloudConfigInternal(connection, sourceAccountId)

        if (targetConfig == null && sourceConfig != null) {
            connection.prepareStatement(
                "UPDATE cloud_config SET account_id = ? WHERE account_id = ?"
            ).use { stmt ->
                stmt.setLong(1, targetAccountId)
                stmt.setLong(2, sourceAccountId)
                stmt.executeUpdate()
            }
        } else if (targetConfig != null && sourceConfig == null) {
            connection.prepareStatement(
                "DELETE FROM cloud_config WHERE account_id = ?"
            ).use { stmt ->
                stmt.setLong(1, sourceAccountId)
                stmt.executeUpdate()
            }
        } else if (targetConfig != null && sourceConfig != null) {
            val mergedFlags = mutableMapOf<String, Boolean>()
            mergedFlags.putAll(sourceConfig.featureFlags)
            mergedFlags.putAll(targetConfig.featureFlags)

            val mergedFlagsJson = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer(), mergedFlags
            )
            connection.prepareStatement(
                "UPDATE cloud_config SET ai_consent_granted = ?, enhanced_context_granted = ?, feature_flags = ?, updated_at_millis = ? WHERE account_id = ?"
            ).use { stmt ->
                stmt.setBoolean(1, targetConfig.aiConsentGranted)
                stmt.setBoolean(2, targetConfig.enhancedContextGranted)
                stmt.setString(3, mergedFlagsJson)
                stmt.setLong(4, now)
                stmt.setLong(5, targetAccountId)
                stmt.executeUpdate()
            }
            connection.prepareStatement(
                "DELETE FROM cloud_config WHERE account_id = ?"
            ).use { stmt ->
                stmt.setLong(1, sourceAccountId)
                stmt.executeUpdate()
            }
        }
    }

    private data class CloudConfigRow(
        val accountId: Long,
        val aiConsentGranted: Boolean,
        val enhancedContextGranted: Boolean,
        val featureFlags: Map<String, Boolean>
    )

    private fun findCloudConfigInternal(connection: Connection, accountId: Long): CloudConfigRow? {
        return connection.prepareStatement(
            "SELECT account_id, ai_consent_granted, enhanced_context_granted, feature_flags FROM cloud_config WHERE account_id = ?"
        ).use { stmt ->
            stmt.setLong(1, accountId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val flagsJson = rs.getString("feature_flags").orEmpty()
                    val flags = runCatching {
                        kotlinx.serialization.json.Json.decodeFromString<Map<String, Boolean>>(flagsJson)
                    }.getOrDefault(emptyMap())
                    CloudConfigRow(
                        accountId = rs.getLong("account_id"),
                        aiConsentGranted = rs.getBoolean("ai_consent_granted"),
                        enhancedContextGranted = rs.getBoolean("enhanced_context_granted"),
                        featureFlags = flags
                    )
                } else null
            }
        }
    }

    private fun mergeRegisteredDevicesInternal(connection: Connection, targetAccountId: Long, sourceAccountId: Long) {
        val targetDevices = findRegisteredDevicesInternal(connection, targetAccountId)
        val sourceDevices = findRegisteredDevicesInternal(connection, sourceAccountId)

        val targetMap = targetDevices.associateBy { it.deviceId }
        val sourceMap = sourceDevices.associateBy { it.deviceId }

        for ((deviceId, sourceDev) in sourceMap) {
            val targetDev = targetMap[deviceId]
            if (targetDev == null) {
                connection.prepareStatement(
                    "UPDATE registered_devices SET account_id = ? WHERE account_id = ? AND device_id = ?"
                ).use { stmt ->
                    stmt.setLong(1, targetAccountId)
                    stmt.setLong(2, sourceAccountId)
                    stmt.setString(3, deviceId)
                    stmt.executeUpdate()
                }
            } else {
                val mergedFirstSeen = minOf(targetDev.firstSeenAtMillis, sourceDev.firstSeenAtMillis)
                val mergedLastSeen = maxOf(targetDev.lastSeenAtMillis, sourceDev.lastSeenAtMillis)
                val mergedIp = if (targetDev.lastSeenAtMillis >= sourceDev.lastSeenAtMillis) targetDev.ipAddress else sourceDev.ipAddress

                connection.prepareStatement(
                    "UPDATE registered_devices SET first_seen_at_millis = ?, last_seen_at_millis = ?, ip_address = ? WHERE account_id = ? AND device_id = ?"
                ).use { stmt ->
                    stmt.setLong(1, mergedFirstSeen)
                    stmt.setLong(2, mergedLastSeen)
                    stmt.setString(3, mergedIp)
                    stmt.setLong(4, targetAccountId)
                    stmt.setString(5, deviceId)
                    stmt.executeUpdate()
                }
                connection.prepareStatement(
                    "DELETE FROM registered_devices WHERE account_id = ? AND device_id = ?"
                ).use { stmt ->
                    stmt.setLong(1, sourceAccountId)
                    stmt.setString(2, deviceId)
                    stmt.executeUpdate()
                }
            }
        }
    }

    private fun findRegisteredDevicesInternal(connection: Connection, accountId: Long): List<StoredRegisteredDevice> {
        val list = mutableListOf<StoredRegisteredDevice>()
        connection.prepareStatement(
            "SELECT account_id, device_id, first_seen_at_millis, last_seen_at_millis, ip_address FROM registered_devices WHERE account_id = ?"
        ).use { stmt ->
            stmt.setLong(1, accountId)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    list += StoredRegisteredDevice(
                        accountId = rs.getLong("account_id"),
                        deviceId = rs.getString("device_id"),
                        firstSeenAtMillis = rs.getLong("first_seen_at_millis"),
                        lastSeenAtMillis = rs.getLong("last_seen_at_millis"),
                        ipAddress = rs.getString("ip_address").orEmpty()
                    )
                }
            }
        }
        return list
    }

    private fun upsertRegisteredDeviceInternal(connection: Connection, device: StoredRegisteredDevice) {
        val updated = connection.prepareStatement(
            "UPDATE registered_devices SET last_seen_at_millis = ?, ip_address = ? WHERE account_id = ? AND device_id = ?"
        ).use { stmt ->
            stmt.setLong(1, device.lastSeenAtMillis)
            stmt.setString(2, device.ipAddress)
            stmt.setLong(3, device.accountId)
            stmt.setString(4, device.deviceId)
            stmt.executeUpdate()
        }
        if (updated == 0) {
            connection.prepareStatement(
                "INSERT INTO registered_devices (account_id, device_id, first_seen_at_millis, last_seen_at_millis, ip_address) VALUES (?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setLong(1, device.accountId)
                stmt.setString(2, device.deviceId)
                stmt.setLong(3, device.firstSeenAtMillis)
                stmt.setLong(4, device.lastSeenAtMillis)
                stmt.setString(5, device.ipAddress)
                stmt.executeUpdate()
            }
        }
    }

    private fun hashTokenString(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun connection() = jdbcConnection(jdbcUrl, username, password)


    data class Config(
        val jdbcUrl: String,
        val username: String = "",
        val password: String = ""
    )

    companion object {
        fun configFromEnvironment(env: Map<String, String> = System.getenv()): Config? {
            val url = env["AUTO_ACCOUNTING_DATABASE_URL"].orEmpty()
            if (url.isBlank()) return null
            return Config(
                jdbcUrl = url,
                username = env["AUTO_ACCOUNTING_DATABASE_USER"].orEmpty(),
                password = env["AUTO_ACCOUNTING_DATABASE_PASSWORD"].orEmpty()
            )
        }
    }
}

private const val UNIQUE_VIOLATION_SQL_STATE = "23505"

private fun ResultSet.toStoredAccount(): StoredAccount {
    return StoredAccount(
        accountId = getLong("account_id"),
        deletionRequestedAtMillis = getNullableLong("deletion_requested_at_millis"),
        createdAtMillis = getLong("created_at_millis")
    )
}

private fun ResultSet.toStoredUser(): StoredUser {

    return StoredUser(
        accountId = getLong("account_id"),
        phone = getString("phone").orEmpty(),
        passwordSalt = getString("password_salt").orEmpty(),
        passwordHash = getString("password_hash").orEmpty(),
        failedLoginCount = getInt("failed_login_count"),
        lockedUntilMillis = getLong("locked_until_millis"),
        deletionRequestedAtMillis = getNullableLong("deletion_requested_at_millis"),
        createdAtMillis = getLong("created_at_millis")
    )
}

private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) {
        setNull(index, java.sql.Types.BIGINT)
    } else {
        setLong(index, value)
    }
}

private fun ResultSet.getNullableLong(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

private fun ResultSet.toStoredWechatIdentity(): StoredWechatIdentity {
    return StoredWechatIdentity(
        accountId = getLong("account_id"),
        appId = getString("app_id"),
        openid = getString("openid"),
        unionid = getString("unionid"),
        nickname = getString("nickname"),
        avatarUrl = getString("avatar_url"),
        createdAtMillis = getLong("created_at_millis"),
        updatedAtMillis = getLong("updated_at_millis")
    )
}

private fun ResultSet.toStoredOneTimeTicket(): StoredOneTimeTicket {
    return StoredOneTimeTicket(
        ticketHash = getString("ticket_hash"),
        ticketType = getString("ticket_type"),
        accountId = getNullableLong("account_id"),
        payloadJson = getString("payload_json"),
        expiresAtMillis = getLong("expires_at_millis"),
        usedAtMillis = getNullableLong("used_at_millis")
    )
}
