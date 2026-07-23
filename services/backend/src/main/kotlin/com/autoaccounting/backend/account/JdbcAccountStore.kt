@file:Suppress("NestedBlockDepth", "TooManyFunctions")

package com.autoaccounting.backend.account

import com.autoaccounting.backend.jdbcConnection
import com.autoaccounting.backend.runBackendMigrations
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

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
                    invalidated = ?, device_id = ?, ip_address = ?
                WHERE phone = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, record.codeHash)
                statement.setLong(2, record.expiresAtMillis)
                statement.setInt(3, record.failedAttempts)
                statement.setBoolean(4, record.invalidated)
                statement.setString(5, record.deviceId)
                statement.setString(6, record.ipAddress)
                statement.setString(7, record.phone)
                statement.executeUpdate()
            }
            if (updated == 0) {
                connection.prepareStatement(
                    """
                    INSERT INTO account_sms_codes (
                        phone, code_hash, expires_at_millis, failed_attempts,
                        invalidated, device_id, ip_address
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, record.phone)
                    statement.setString(2, record.codeHash)
                    statement.setLong(3, record.expiresAtMillis)
                    statement.setInt(4, record.failedAttempts)
                    statement.setBoolean(5, record.invalidated)
                    statement.setString(6, record.deviceId)
                    statement.setString(7, record.ipAddress)
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun findSmsCode(phone: String): StoredSmsCode? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT phone, code_hash, expires_at_millis, failed_attempts,
                   invalidated, device_id, ip_address
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
                        ipAddress = rs.getString("ip_address").orEmpty()
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
        connection.prepareStatement(
            """
            UPDATE account_one_time_tickets
            SET used_at_millis = ?
            WHERE ticket_hash = ? AND used_at_millis IS NULL AND expires_at_millis >= ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, usedAtMillis)
            statement.setString(2, ticketHash)
            statement.setLong(3, usedAtMillis)
            val rows = statement.executeUpdate()
            rows > 0
        }
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
