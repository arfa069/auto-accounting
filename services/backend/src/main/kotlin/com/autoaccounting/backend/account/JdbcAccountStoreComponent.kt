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
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
@Suppress("TooManyFunctions")
internal abstract class JdbcAccountStoreComponent(
    private val context: JdbcAccountStoreContext
) {
    protected fun connection(): Connection = context.connection()

    protected fun deleteVerificationCode(
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


    protected fun queryPasswordCredential(connection: Connection, accountId: Long): StoredPasswordCredential? {
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

    protected fun queryIdentifiersByAccountId(connection: Connection, accountId: Long): List<StoredAccountIdentifier> {
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

    protected fun queryOneTimeTicket(connection: Connection, ticketHash: String): StoredOneTimeTicket? {
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

    protected fun markOneTimeTicketUsed(connection: Connection, ticketHash: String, usedAtMillis: Long): Int {
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

    protected fun queryWechatIdentityByOpenid(connection: Connection, appId: String, openid: String): StoredWechatIdentity? {
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

    protected fun queryWechatIdentityByUnionid(connection: Connection, unionid: String?): StoredWechatIdentity? {
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

    protected fun queryWechatIdentityByAccountId(connection: Connection, accountId: Long): StoredWechatIdentity? {
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

    protected fun insertWechatIdentity(connection: Connection, identity: StoredWechatIdentity) {
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

    protected fun insertSession(connection: Connection, session: StoredSession) {
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

    protected fun upsertRegisteredDevice(connection: Connection, device: StoredRegisteredDevice) {
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

    protected fun queryAccount(connection: Connection, accountId: Long): StoredAccount? {
        return connection.prepareStatement(
            """
            SELECT account_id, public_id, primary_identifier_type, deletion_requested_at_millis, created_at_millis
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

    protected fun lockAccountsForUpdate(
        connection: Connection,
        firstId: Long,
        secondId: Long
    ): List<StoredAccount> {
        val accounts = mutableListOf<StoredAccount>()
        connection.prepareStatement(
            """
            SELECT account_id, public_id, primary_identifier_type, deletion_requested_at_millis, created_at_millis
            FROM accounts
            WHERE account_id IN (?, ?)
            ORDER BY account_id
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, firstId)
            statement.setLong(2, secondId)
            statement.executeQuery().use { result ->
                while (result.next()) {
                    accounts += result.toStoredAccount()
                }
            }
        }
        return accounts
    }



    protected fun hashTokenString(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

}

