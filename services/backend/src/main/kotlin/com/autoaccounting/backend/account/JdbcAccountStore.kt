@file:Suppress("NestedBlockDepth", "TooManyFunctions", "LargeClass", "LongMethod", "CyclomaticComplexMethod", "LongParameterList")


package com.autoaccounting.backend.account

import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncJsonContracts
import com.autoaccounting.api.LedgerSyncPayloadContract
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
import java.util.UUID

class JdbcAccountStore(
    private val jdbcUrl: String,
    private val username: String = "",
    private val password: String = ""
) : AccountStore {
    init {
        runBackendMigrations(jdbcUrl, username, password)
    }

    override fun findAccount(accountId: Long): StoredAccount? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT account_id, primary_identifier_type, deletion_requested_at_millis, created_at_millis
            FROM accounts
            WHERE account_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredAccount(
                        accountId = rs.getLong("account_id"),
                        primaryIdentifierType = rs.getString("primary_identifier_type"),
                        deletionRequestedAtMillis = rs.getNullableLong("deletion_requested_at_millis"),
                        createdAtMillis = rs.getLong("created_at_millis")
                    )
                } else {
                    null
                }
            }
        }
    }

    override fun updateAccountDeletionRequestedAt(accountId: Long, requestedAtMillis: Long?) {
        connection().use { connection ->
            connection.prepareStatement(
                "UPDATE accounts SET deletion_requested_at_millis = ? WHERE account_id = ?"
            ).use { statement ->
                if (requestedAtMillis == null) statement.setNull(1, java.sql.Types.BIGINT)
                else statement.setLong(1, requestedAtMillis)
                statement.setLong(2, accountId)
                statement.executeUpdate()
            }
        }
    }

    override fun accountsPendingDeletion(): List<StoredAccount> = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT account_id, primary_identifier_type, deletion_requested_at_millis, created_at_millis
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
                                primaryIdentifierType = rs.getString("primary_identifier_type"),
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

    // Unified Identifier & Credential Store Implementations

    override fun findAccountByIdentifier(identifierType: String, normalizedValue: String): StoredAccount? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT a.account_id, a.primary_identifier_type, a.deletion_requested_at_millis, a.created_at_millis
            FROM account_identifiers i
            JOIN accounts a ON a.account_id = i.account_id
            WHERE i.identifier_type = ? AND i.normalized_value = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, identifierType)
            statement.setString(2, normalizedValue)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredAccount(
                        accountId = rs.getLong("account_id"),
                        primaryIdentifierType = rs.getString("primary_identifier_type"),
                        deletionRequestedAtMillis = rs.getNullableLong("deletion_requested_at_millis"),
                        createdAtMillis = rs.getLong("created_at_millis")
                    )
                } else null
            }
        }
    }

    override fun findPasswordCredentialByAccountId(accountId: Long): StoredPasswordCredential? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT account_id, password_salt, password_hash, failed_login_count, locked_until_millis, updated_at_millis
            FROM account_password_credentials
            WHERE account_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredPasswordCredential(
                        accountId = rs.getLong("account_id"),
                        passwordSalt = rs.getString("password_salt"),
                        passwordHash = rs.getString("password_hash"),
                        failedLoginCount = rs.getInt("failed_login_count"),
                        lockedUntilMillis = rs.getLong("locked_until_millis"),
                        updatedAtMillis = rs.getLong("updated_at_millis")
                    )
                } else null
            }
        }
    }

    override fun findIdentifiersByAccountId(accountId: Long): List<StoredAccountIdentifier> = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT id, account_id, identifier_type, raw_value, normalized_value, verified, created_at_millis, updated_at_millis
            FROM account_identifiers
            WHERE account_id = ?
            ORDER BY id
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StoredAccountIdentifier(
                                id = rs.getLong("id"),
                                accountId = rs.getLong("account_id"),
                                identifierType = rs.getString("identifier_type"),
                                rawValue = rs.getString("raw_value"),
                                normalizedValue = rs.getString("normalized_value"),
                                verified = rs.getBoolean("verified"),
                                createdAtMillis = rs.getLong("created_at_millis"),
                                updatedAtMillis = rs.getLong("updated_at_millis")
                            )
                        )
                    }
                }
            }
        }
    }

    override fun findIdentifierByValue(identifierType: String, normalizedValue: String): StoredAccountIdentifier? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT id, account_id, identifier_type, raw_value, normalized_value, verified, created_at_millis, updated_at_millis
            FROM account_identifiers
            WHERE identifier_type = ? AND normalized_value = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, identifierType)
            statement.setString(2, normalizedValue)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredAccountIdentifier(
                        id = rs.getLong("id"),
                        accountId = rs.getLong("account_id"),
                        identifierType = rs.getString("identifier_type"),
                        rawValue = rs.getString("raw_value"),
                        normalizedValue = rs.getString("normalized_value"),
                        verified = rs.getBoolean("verified"),
                        createdAtMillis = rs.getLong("created_at_millis"),
                        updatedAtMillis = rs.getLong("updated_at_millis")
                    )
                } else null
            }
        }
    }

    override fun updatePasswordCredential(credential: StoredPasswordCredential) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE account_password_credentials
                SET password_salt = ?, password_hash = ?, failed_login_count = ?, locked_until_millis = ?, updated_at_millis = ?
                WHERE account_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, credential.passwordSalt)
                statement.setString(2, credential.passwordHash)
                statement.setInt(3, credential.failedLoginCount)
                statement.setLong(4, credential.lockedUntilMillis)
                statement.setLong(5, credential.updatedAtMillis)
                statement.setLong(6, credential.accountId)
                statement.executeUpdate()
            }
        }
    }

    override fun resetPasswordAndRotateSession(
        credential: StoredPasswordCredential,
        verificationIdentifierType: String,
        verificationNormalizedIdentifier: String,
        verificationPurpose: String,
        deviceId: String,
        ipAddress: String,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken> = connection().use { connection ->
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                """
                UPDATE account_password_credentials
                SET password_salt = ?, password_hash = ?, failed_login_count = ?,
                    locked_until_millis = ?, updated_at_millis = ?
                WHERE account_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, credential.passwordSalt)
                statement.setString(2, credential.passwordHash)
                statement.setInt(3, credential.failedLoginCount)
                statement.setLong(4, credential.lockedUntilMillis)
                statement.setLong(5, credential.updatedAtMillis)
                statement.setLong(6, credential.accountId)
                if (statement.executeUpdate() != 1) {
                    connection.rollback()
                    return AccountResult.Failure(AccountError.LOGIN_FAILED)
                }
            }
            connection.prepareStatement("DELETE FROM account_sessions WHERE account_id = ?").use { statement ->
                statement.setLong(1, credential.accountId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "DELETE FROM verification_codes WHERE identifier_type = ? AND normalized_identifier = ? AND purpose = ?"
            ).use { statement ->
                statement.setString(1, verificationIdentifierType)
                statement.setString(2, verificationNormalizedIdentifier)
                statement.setString(3, verificationPurpose)
                statement.executeUpdate()
            }
            if (deviceId.isNotBlank()) {
                upsertRegisteredDevice(
                    connection,
                    StoredRegisteredDevice(
                        accountId = credential.accountId,
                        deviceId = deviceId,
                        firstSeenAtMillis = now,
                        lastSeenAtMillis = now,
                        ipAddress = ipAddress
                    )
                )
            }
            val token = tokenGenerator()
            insertSession(
                connection,
                StoredSession(hashTokenString(token), credential.accountId, deviceId, now)
            )
            connection.commit()
            AccountResult.Success(AccountToken(accountId = credential.accountId, token = token))
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    override fun createAccountWithIdentifier(
        primaryIdentifierType: String,
        rawValue: String,
        normalizedValue: String,
        passwordSalt: String?,
        passwordHash: String?,
        verified: Boolean,
        now: Long
    ): StoredAccount? = connection().use { connection ->
        connection.autoCommit = false
        try {
            var accountId: Long = -1
            connection.prepareStatement(
                """
                INSERT INTO accounts (primary_identifier_type, created_at_millis)
                VALUES (?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.setString(1, primaryIdentifierType)
                statement.setLong(2, now)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    if (keys.next()) accountId = keys.getLong(1)
                }
            }
            if (accountId == -1L) {
                connection.rollback()
                return null
            }

            connection.prepareStatement(
                """
                INSERT INTO account_identifiers (account_id, identifier_type, raw_value, normalized_value, verified, created_at_millis, updated_at_millis)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.setString(2, primaryIdentifierType)
                statement.setString(3, rawValue)
                statement.setString(4, normalizedValue)
                statement.setBoolean(5, verified)
                statement.setLong(6, now)
                statement.setLong(7, now)
                statement.executeUpdate()
            }

            if (passwordSalt != null && passwordHash != null) {
                connection.prepareStatement(
                    """
                    INSERT INTO account_password_credentials (account_id, password_salt, password_hash, failed_login_count, locked_until_millis, updated_at_millis)
                    VALUES (?, ?, ?, 0, 0, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, accountId)
                    statement.setString(2, passwordSalt)
                    statement.setString(3, passwordHash)
                    statement.setLong(4, now)
                    statement.executeUpdate()
                }
            }

            connection.commit()
            StoredAccount(accountId = accountId, primaryIdentifierType = primaryIdentifierType, createdAtMillis = now)
        } catch (error: SQLException) {
            connection.rollback()
            if (error.sqlState == UNIQUE_VIOLATION_SQL_STATE) null else throw error
        } finally {
            connection.autoCommit = true
        }
    }

    override fun addIdentifierToAccount(
        accountId: Long,
        identifierType: String,
        rawValue: String,
        normalizedValue: String,
        verified: Boolean,
        now: Long
    ): Boolean = connection().use { connection ->
        try {
            connection.prepareStatement(
                """
                INSERT INTO account_identifiers (account_id, identifier_type, raw_value, normalized_value, verified, created_at_millis, updated_at_millis)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.setString(2, identifierType)
                statement.setString(3, rawValue)
                statement.setString(4, normalizedValue)
                statement.setBoolean(5, verified)
                statement.setLong(6, now)
                statement.setLong(7, now)
                statement.executeUpdate()
            }
            true
        } catch (error: SQLException) {
            if (error.sqlState == UNIQUE_VIOLATION_SQL_STATE) false else throw error
        }
    }

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
    ): AccountResult<AccountToken> = connection().use { connection ->
        connection.autoCommit = false
        try {
            val ticketValid = connection.prepareStatement(
                """
                SELECT ticket_type, account_id, expires_at_millis, used_at_millis
                FROM account_one_time_tickets
                WHERE ticket_hash = ? FOR UPDATE
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, ticketHash)
                statement.executeQuery().use { rs ->
                    rs.next() && rs.getString("ticket_type") == "IDENTIFIER_LINK" &&
                        rs.getLong("account_id") == accountId && rs.getObject("used_at_millis") == null &&
                        rs.getLong("expires_at_millis") >= now
                }
            }
            if (!ticketValid) {
                connection.rollback()
                return AccountResult.Failure(AccountError.TICKET_EXPIRED)
            }
            val hasPassword = connection.prepareStatement(
                "SELECT account_id FROM account_password_credentials WHERE account_id = ? FOR UPDATE"
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { it.next() }
            }
            if (!hasPassword && (newPasswordSalt == null || newPasswordHash == null)) {
                connection.rollback()
                return AccountResult.Failure(AccountError.INVALID_REQUEST)
            }
            if (!hasPassword) {
                connection.prepareStatement(
                    """
                    INSERT INTO account_password_credentials (
                        account_id, password_salt, password_hash, failed_login_count,
                        locked_until_millis, updated_at_millis
                    ) VALUES (?, ?, ?, 0, 0, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, accountId)
                    statement.setString(2, newPasswordSalt)
                    statement.setString(3, newPasswordHash)
                    statement.setLong(4, now)
                    statement.executeUpdate()
                }
            }
            connection.prepareStatement(
                """
                INSERT INTO account_identifiers (
                    account_id, identifier_type, raw_value, normalized_value, verified,
                    created_at_millis, updated_at_millis
                ) VALUES (?, ?, ?, ?, TRUE, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.setString(2, identifierType)
                statement.setString(3, rawValue)
                statement.setString(4, normalizedValue)
                statement.setLong(5, now)
                statement.setLong(6, now)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "UPDATE accounts SET primary_identifier_type = COALESCE(primary_identifier_type, ?) WHERE account_id = ?"
            ).use { statement ->
                statement.setString(1, identifierType)
                statement.setLong(2, accountId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "UPDATE account_one_time_tickets SET used_at_millis = ? WHERE ticket_hash = ? AND used_at_millis IS NULL"
            ).use { statement ->
                statement.setLong(1, now)
                statement.setString(2, ticketHash)
                if (statement.executeUpdate() != 1) error("Identifier link ticket was concurrently consumed")
            }
            connection.prepareStatement(
                "DELETE FROM verification_codes WHERE identifier_type = ? AND normalized_identifier = ? AND purpose = 'IDENTIFIER_LINK'"
            ).use { statement ->
                statement.setString(1, identifierType)
                statement.setString(2, normalizedValue)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM account_sessions WHERE account_id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeUpdate()
            }
            if (deviceId.isNotBlank()) {
                upsertRegisteredDevice(
                    connection,
                    StoredRegisteredDevice(accountId, deviceId, now, now, ipAddress)
                )
            }
            val token = tokenGenerator()
            insertSession(connection, StoredSession(hashTokenString(token), accountId, deviceId, now))
            connection.commit()
            AccountResult.Success(AccountToken(accountId = accountId, token = token))
        } catch (error: java.sql.SQLException) {
            connection.rollback()
            AccountResult.Failure(AccountError.IDENTIFIER_CONFLICT)
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    override fun upsertVerificationCode(code: StoredVerificationCode) {
        connection().use { connection ->
            val databaseProduct = connection.metaData.databaseProductName
            val sql = if (databaseProduct == "H2") {
                """
                MERGE INTO verification_codes (
                    identifier_type, normalized_identifier, purpose, code_hash,
                    expires_at_millis, failed_attempts, invalidated, device_id, ip_address, context_key
                ) KEY (identifier_type, normalized_identifier, purpose)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            } else {
                """
                INSERT INTO verification_codes (
                    identifier_type, normalized_identifier, purpose, code_hash,
                    expires_at_millis, failed_attempts, invalidated, device_id, ip_address, context_key
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (identifier_type, normalized_identifier, purpose) DO UPDATE SET
                    code_hash = EXCLUDED.code_hash,
                    expires_at_millis = EXCLUDED.expires_at_millis,
                    failed_attempts = EXCLUDED.failed_attempts,
                    invalidated = EXCLUDED.invalidated,
                    device_id = EXCLUDED.device_id,
                    ip_address = EXCLUDED.ip_address,
                    context_key = EXCLUDED.context_key
                """.trimIndent()
            }
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, code.identifierType)
                statement.setString(2, code.normalizedIdentifier)
                statement.setString(3, code.purpose)
                statement.setString(4, code.codeHash)
                statement.setLong(5, code.expiresAtMillis)
                statement.setInt(6, code.failedAttempts)
                statement.setBoolean(7, code.invalidated)
                statement.setString(8, code.deviceId)
                statement.setString(9, code.ipAddress)
                statement.setString(10, code.contextKey)
                statement.executeUpdate()
            }
        }
    }

    override fun findVerificationCode(identifierType: String, normalizedIdentifier: String, purpose: String): StoredVerificationCode? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT identifier_type, normalized_identifier, purpose, code_hash, expires_at_millis, failed_attempts, invalidated, device_id, ip_address, context_key
            FROM verification_codes
            WHERE identifier_type = ? AND normalized_identifier = ? AND purpose = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, identifierType)
            statement.setString(2, normalizedIdentifier)
            statement.setString(3, purpose)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredVerificationCode(
                        identifierType = rs.getString("identifier_type"),
                        normalizedIdentifier = rs.getString("normalized_identifier"),
                        purpose = rs.getString("purpose"),
                        codeHash = rs.getString("code_hash"),
                        expiresAtMillis = rs.getLong("expires_at_millis"),
                        failedAttempts = rs.getInt("failed_attempts"),
                        invalidated = rs.getBoolean("invalidated"),
                        deviceId = rs.getString("device_id"),
                        ipAddress = rs.getString("ip_address"),
                        contextKey = rs.getString("context_key")
                    )
                } else null
            }
        }
    }

    override fun deleteVerificationCode(identifierType: String, normalizedIdentifier: String, purpose: String) {
        connection().use { connection ->
            deleteVerificationCode(connection, identifierType, normalizedIdentifier, purpose)
        }
    }

    private fun deleteVerificationCode(
        connection: Connection,
        identifierType: String,
        normalizedIdentifier: String,
        purpose: String
    ) {
        connection.prepareStatement(
            """
            DELETE FROM verification_codes
            WHERE identifier_type = ? AND normalized_identifier = ? AND purpose = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, identifierType)
            statement.setString(2, normalizedIdentifier)
            statement.setString(3, purpose)
            statement.executeUpdate()
        }
    }

    override fun recordVerificationSendLog(channelType: String, scopeType: String, scopeValue: String, issuedAtMillis: Long) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO verification_code_send_logs (channel_type, scope_type, scope_value, issued_at_millis)
                VALUES (?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, channelType)
                statement.setString(2, scopeType)
                statement.setString(3, scopeValue)
                statement.setLong(4, issuedAtMillis)
                statement.executeUpdate()
            }
        }
    }

    override fun countVerificationSendLogs(channelType: String, scopeType: String, scopeValue: String, sinceMillis: Long): Int = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT COUNT(*) AS issue_count
            FROM verification_code_send_logs
            WHERE channel_type = ? AND scope_type = ? AND scope_value = ? AND issued_at_millis >= ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, channelType)
            statement.setString(2, scopeType)
            statement.setString(3, scopeValue)
            statement.setLong(4, sinceMillis)
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getInt("issue_count")
            }
        }
    }

    override fun latestVerificationSendLogMillis(channelType: String, scopeType: String, scopeValue: String): Long? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT MAX(issued_at_millis) AS latest_issue
            FROM verification_code_send_logs
            WHERE channel_type = ? AND scope_type = ? AND scope_value = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, channelType)
            statement.setString(2, scopeType)
            statement.setString(3, scopeValue)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getNullableLong("latest_issue") else null
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

            connection.prepareStatement("DELETE FROM account_sessions WHERE account_id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeUpdate()
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

    override fun unlinkWechatIdentity(
        accountId: Long,
        phone: String?,
        deviceId: String,
        ipAddress: String,
        verificationCodeToDelete: StoredVerificationCode?,
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

    private fun queryPasswordCredential(connection: Connection, accountId: Long): StoredPasswordCredential? {
        return connection.prepareStatement(
            """
            SELECT account_id, password_salt, password_hash, failed_login_count, locked_until_millis, updated_at_millis
            FROM account_password_credentials
            WHERE account_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                StoredPasswordCredential(
                    accountId = rs.getLong("account_id"),
                    passwordSalt = rs.getString("password_salt"),
                    passwordHash = rs.getString("password_hash"),
                    failedLoginCount = rs.getInt("failed_login_count"),
                    lockedUntilMillis = rs.getLong("locked_until_millis"),
                    updatedAtMillis = rs.getLong("updated_at_millis")
                )
            }
        }
    }

    private fun queryIdentifiersByAccountId(connection: Connection, accountId: Long): List<StoredAccountIdentifier> {
        return connection.prepareStatement(
            """
            SELECT id, account_id, identifier_type, raw_value, normalized_value, verified,
                   created_at_millis, updated_at_millis
            FROM account_identifiers
            WHERE account_id = ?
            ORDER BY id
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StoredAccountIdentifier(
                                id = rs.getLong("id"),
                                accountId = rs.getLong("account_id"),
                                identifierType = rs.getString("identifier_type"),
                                rawValue = rs.getString("raw_value"),
                                normalizedValue = rs.getString("normalized_value"),
                                verified = rs.getBoolean("verified"),
                                createdAtMillis = rs.getLong("created_at_millis"),
                                updatedAtMillis = rs.getLong("updated_at_millis")
                            )
                        )
                    }
                }
            }
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
            SELECT account_id, primary_identifier_type, deletion_requested_at_millis, created_at_millis
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

            val targetAccount = lockedAccounts.first { it.accountId == targetAccountId }
            val sourceAccount = lockedAccounts.first { it.accountId == sourceAccountId }
            val targetPassCred = queryPasswordCredential(connection, targetAccountId)
            val sourcePassCred = queryPasswordCredential(connection, sourceAccountId)
            if (targetPassCred != null && sourcePassCred != null) {
                connection.rollback()
                return AccountResult.Failure(AccountError.MERGE_BLOCKED)
            }

            val targetWechat = findWechatIdentityInternal(connection, targetAccountId)
            val sourceWechat = findWechatIdentityInternal(connection, sourceAccountId)
            if (targetWechat != null && sourceWechat != null) {
                connection.rollback()
                return AccountResult.Failure(AccountError.MERGE_BLOCKED)
            }

            val targetIdentifiers = queryIdentifiersByAccountId(connection, targetAccountId)
            val sourceIdentifiers = queryIdentifiersByAccountId(connection, sourceAccountId)
            if (targetIdentifiers.any { target ->
                    sourceIdentifiers.any { source -> source.identifierType == target.identifierType }
                }
            ) {
                connection.rollback()
                return AccountResult.Failure(AccountError.MERGE_BLOCKED)
            }

            // Transfer credentials
            if (sourcePassCred != null) {
                connection.prepareStatement(
                    "UPDATE account_password_credentials SET account_id = ? WHERE account_id = ?"
                ).use { stmt ->
                    stmt.setLong(1, targetAccountId)
                    stmt.setLong(2, sourceAccountId)
                    stmt.executeUpdate()
                }
            }

            connection.prepareStatement(
                "UPDATE account_identifiers SET account_id = ? WHERE account_id = ?"
            ).use { stmt ->
                stmt.setLong(1, targetAccountId)
                stmt.setLong(2, sourceAccountId)
                stmt.executeUpdate()
            }

            if (targetAccount.primaryIdentifierType == null && sourceAccount.primaryIdentifierType != null) {
                connection.prepareStatement(
                    "UPDATE accounts SET primary_identifier_type = ? WHERE account_id = ?"
                ).use { stmt ->
                    stmt.setString(1, sourceAccount.primaryIdentifierType)
                    stmt.setLong(2, targetAccountId)
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

            // Move distinct synced ledger records and preserve same-record candidates as conflicts.
            mergeLedgerSyncInternal(connection, targetAccountId, sourceAccountId, now)

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

            // Delete verification codes for every identifier moved from the source account.
            connection.prepareStatement(
                "DELETE FROM verification_codes WHERE identifier_type = ? AND normalized_identifier = ?"
            ).use { stmt ->
                for (identifier in sourceIdentifiers) {
                    stmt.setString(1, identifier.identifierType)
                    stmt.setString(2, identifier.normalizedValue)
                    stmt.addBatch()
                }
                stmt.executeBatch()
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

            val finalPhone = queryIdentifiersByAccountId(connection, targetAccountId)
                .firstOrNull { it.identifierType == "PHONE" }
                ?.rawValue
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

    private fun mergeLedgerSyncInternal(
        connection: Connection,
        targetAccountId: Long,
        sourceAccountId: Long,
        now: Long
    ) {
        val sourceRecords = connection.prepareStatement(
            """
            SELECT entity_type, entity_id, version, deleted, payload, business_key
            FROM ledger_sync_records
            WHERE account_id = ?
            ORDER BY entity_type, entity_id
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, sourceAccountId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            SyncMergeRecord(
                                entityType = result.getString("entity_type"),
                                entityId = result.getString("entity_id"),
                                version = result.getLong("version"),
                                deleted = result.getBoolean("deleted"),
                                payload = result.getString("payload"),
                                businessKey = result.getString("business_key")
                            )
                        )
                    }
                }
            }
        }
        val categoryRemaps = mutableMapOf<String, String>()
        val fundingAccountRemaps = mutableMapOf<String, String>()
        sourceRecords.forEach { source ->
            val target = findTargetSyncRecord(connection, targetAccountId, source)
            if (target != null && target.entityId != source.entityId) {
                when (LedgerSyncEntityTypeContract.valueOf(source.entityType)) {
                    LedgerSyncEntityTypeContract.CATEGORY -> categoryRemaps[source.entityId] = target.entityId
                    LedgerSyncEntityTypeContract.FUNDING_ACCOUNT ->
                        fundingAccountRemaps[source.entityId] = target.entityId
                    else -> Unit
                }
            }
            val candidatePayload = remapSyncPayload(
                source = source,
                canonicalId = target?.entityId ?: source.entityId,
                categoryRemaps = categoryRemaps,
                fundingAccountRemaps = fundingAccountRemaps
            )
            if (target == null) {
                val revision = connection.prepareStatement(
                    """
                    INSERT INTO ledger_sync_changes(
                        account_id, entity_type, entity_id, version, deleted, payload, changed_at_millis
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS
                ).use { statement ->
                    statement.setLong(1, targetAccountId)
                    statement.setString(2, source.entityType)
                    statement.setString(3, source.entityId)
                    statement.setLong(4, source.version)
                    statement.setBoolean(5, source.deleted)
                    statement.setNullableString(6, candidatePayload)
                    statement.setLong(7, now)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys -> check(keys.next()); keys.getLong(1) }
                }
                connection.prepareStatement(
                    """
                    UPDATE ledger_sync_records
                    SET account_id = ?, revision = ?, payload = ?, updated_at_millis = ?
                    WHERE account_id = ? AND entity_type = ? AND entity_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, targetAccountId)
                    statement.setLong(2, revision)
                    statement.setNullableString(3, candidatePayload)
                    statement.setLong(4, now)
                    statement.setLong(5, sourceAccountId)
                    statement.setString(6, source.entityType)
                    statement.setString(7, source.entityId)
                    statement.executeUpdate()
                }
            } else {
                if (target.deleted == source.deleted && target.payload == candidatePayload) return@forEach
                connection.prepareStatement(
                    """
                    INSERT INTO ledger_sync_conflicts(
                        conflict_id, account_id, entity_type, entity_id, canonical_version,
                        canonical_deleted, canonical_payload, candidate_deleted,
                        candidate_payload, created_at_millis, resolved
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, UUID.randomUUID().toString())
                    statement.setLong(2, targetAccountId)
                    statement.setString(3, source.entityType)
                    statement.setString(4, target.entityId)
                    statement.setLong(5, target.version)
                    statement.setBoolean(6, target.deleted)
                    statement.setNullableString(7, target.payload)
                    statement.setBoolean(8, source.deleted)
                    statement.setNullableString(9, candidatePayload)
                    statement.setLong(10, now)
                    statement.executeUpdate()
                }
            }
        }
        connection.prepareStatement("UPDATE ledger_sync_conflicts SET account_id = ? WHERE account_id = ?").use { statement ->
            statement.setLong(1, targetAccountId)
            statement.setLong(2, sourceAccountId)
            statement.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM ledger_sync_records WHERE account_id = ?").use { statement ->
            statement.setLong(1, sourceAccountId)
            statement.executeUpdate()
        }
        val targetHasProfile = connection.prepareStatement(
            "SELECT 1 FROM ledger_sync_profiles WHERE account_id = ?"
        ).use { statement ->
            statement.setLong(1, targetAccountId)
            statement.executeQuery().use { it.next() }
        }
        if (targetHasProfile) {
            connection.prepareStatement("DELETE FROM ledger_sync_profiles WHERE account_id = ?").use { statement ->
                statement.setLong(1, sourceAccountId)
                statement.executeUpdate()
            }
        } else {
            connection.prepareStatement("UPDATE ledger_sync_profiles SET account_id = ? WHERE account_id = ?").use { statement ->
                statement.setLong(1, targetAccountId)
                statement.setLong(2, sourceAccountId)
                statement.executeUpdate()
            }
        }
    }

    private fun findTargetSyncRecord(
        connection: Connection,
        targetAccountId: Long,
        source: SyncMergeRecord
    ): SyncMergeRecord? {
        val businessKeyClause = if (source.businessKey == null) "" else " OR business_key = ?"
        return connection.prepareStatement(
            """
            SELECT entity_id, version, deleted, payload, business_key
            FROM ledger_sync_records
            WHERE account_id = ? AND entity_type = ?
              AND (entity_id = ?$businessKeyClause)
            ORDER BY CASE WHEN entity_id = ? THEN 0 ELSE 1 END
            LIMIT 1
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, targetAccountId)
            statement.setString(2, source.entityType)
            statement.setString(3, source.entityId)
            var index = 4
            source.businessKey?.let { statement.setString(index++, it) }
            statement.setString(index, source.entityId)
            statement.executeQuery().use { result ->
                if (result.next()) SyncMergeRecord(
                    source.entityType,
                    result.getString("entity_id"),
                    result.getLong("version"),
                    result.getBoolean("deleted"),
                    result.getString("payload"),
                    result.getString("business_key")
                ) else null
            }
        }
    }

    private data class SyncMergeRecord(
        val entityType: String,
        val entityId: String,
        val version: Long,
        val deleted: Boolean,
        val payload: String?,
        val businessKey: String?
    )

    private fun remapSyncPayload(
        source: SyncMergeRecord,
        canonicalId: String,
        categoryRemaps: Map<String, String>,
        fundingAccountRemaps: Map<String, String>
    ): String? {
        val body = source.payload ?: return null
        val type = LedgerSyncEntityTypeContract.valueOf(source.entityType)
        val payload = LedgerSyncJsonContracts.parsePayload(type, body)
        val remapped = when (payload) {
            is LedgerSyncPayloadContract.Category -> payload.copy(id = canonicalId)
            is LedgerSyncPayloadContract.FundingAccount -> payload.copy(syncId = canonicalId)
            is LedgerSyncPayloadContract.LedgerEntry -> payload.copy(
                categoryId = payload.categoryId?.let { categoryRemaps[it] ?: it },
                fundingAccountSyncId = payload.fundingAccountSyncId?.let { fundingAccountRemaps[it] ?: it }
            )
            else -> payload
        }
        return LedgerSyncJsonContracts.encodePayload(type, remapped)
    }

    private fun lockAccountsForUpdateInternal(connection: Connection, firstId: Long, secondId: Long): List<StoredAccount> {
        val list = mutableListOf<StoredAccount>()
        connection.prepareStatement(
            """
            SELECT account_id, primary_identifier_type, deletion_requested_at_millis, created_at_millis
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
        primaryIdentifierType = getString("primary_identifier_type"),
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

private fun java.sql.PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) {
        setNull(index, java.sql.Types.VARCHAR)
    } else {
        setString(index, value)
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
