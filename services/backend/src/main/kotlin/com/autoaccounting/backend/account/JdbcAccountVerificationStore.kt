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
internal class JdbcAccountVerificationStore(
    context: JdbcAccountStoreContext
) : JdbcAccountStoreComponent(context), AccountVerificationStore {
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

}

