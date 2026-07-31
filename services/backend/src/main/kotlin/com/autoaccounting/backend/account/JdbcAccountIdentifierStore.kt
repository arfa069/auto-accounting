package com.autoaccounting.backend.account

import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncJsonContracts
import com.autoaccounting.api.LedgerSyncPayloadContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
@Suppress("NestedBlockDepth")
internal class JdbcAccountIdentifierStore(
    context: JdbcAccountStoreContext
) : JdbcAccountStoreComponent(context), AccountIdentifierStore {
    override fun findAccountByIdentifier(identifierType: String, normalizedValue: String): StoredAccount? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT a.account_id, a.public_id, a.primary_identifier_type, a.deletion_requested_at_millis, a.created_at_millis
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
                        publicId = rs.getString("public_id"),
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
            val publicId = UUID.randomUUID().toString()
            connection.prepareStatement(
                """
                INSERT INTO accounts (public_id, primary_identifier_type, created_at_millis)
                VALUES (?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.setString(1, publicId)
                statement.setString(2, primaryIdentifierType)
                statement.setLong(3, now)
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
            StoredAccount(
                accountId = accountId,
                publicId = publicId,
                primaryIdentifierType = primaryIdentifierType,
                createdAtMillis = now
            )
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

    @Suppress("LongMethod", "CyclomaticComplexMethod")
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
        tokenGenerator: () -> String,
        replaceExisting: Boolean
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
                    rs.next() && rs.getString("ticket_type") in setOf("IDENTIFIER_LINK", "IDENTIFIER_REPLACE") &&
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
            val existingOfType = connection.prepareStatement(
                """
                SELECT id
                FROM account_identifiers
                WHERE account_id = ? AND identifier_type = ?
                FOR UPDATE
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.setString(2, identifierType)
                statement.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
            }
            if (replaceExisting != (existingOfType != null)) {
                connection.rollback()
                return AccountResult.Failure(AccountError.IDENTIFIER_CONFLICT)
            }
            if (existingOfType == null) {
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
            } else {
                connection.prepareStatement(
                    """
                    UPDATE account_identifiers
                    SET raw_value = ?, normalized_value = ?, verified = TRUE, updated_at_millis = ?
                    WHERE id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, rawValue)
                    statement.setString(2, normalizedValue)
                    statement.setLong(3, now)
                    statement.setLong(4, existingOfType)
                    statement.executeUpdate()
                }
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
            if (error.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                AccountResult.Failure(AccountError.IDENTIFIER_CONFLICT)
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

}

