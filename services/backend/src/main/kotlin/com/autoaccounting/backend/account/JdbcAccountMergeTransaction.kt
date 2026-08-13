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
internal class JdbcAccountMergeTransaction(
    context: JdbcAccountStoreContext,
    private val ledgerSyncMerger: JdbcAccountLedgerSyncMerger = JdbcAccountLedgerSyncMerger()
) : JdbcAccountStoreComponent(context) {
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    fun mergeAccounts(
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
            val lockedAccounts = lockAccountsForUpdate(connection, firstId, secondId)
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
            ledgerSyncMerger.merge(connection, targetAccountId, sourceAccountId, now)

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
                "UPDATE cloud_config SET ai_consent_granted = ?, enhanced_context_granted = ?, feature_flags = ?, default_funding_account_sync_id = ?, updated_at_millis = ? WHERE account_id = ?"
            ).use { stmt ->
                stmt.setBoolean(1, targetConfig.aiConsentGranted)
                stmt.setBoolean(2, targetConfig.enhancedContextGranted)
                stmt.setString(3, mergedFlagsJson)
                stmt.setString(4, targetConfig.defaultFundingAccountSyncId ?: sourceConfig.defaultFundingAccountSyncId)
                stmt.setLong(5, now)
                stmt.setLong(6, targetAccountId)
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
        val featureFlags: Map<String, Boolean>,
        val defaultFundingAccountSyncId: String?
    )

    private fun findCloudConfigInternal(connection: Connection, accountId: Long): CloudConfigRow? {
        return connection.prepareStatement(
            "SELECT account_id, ai_consent_granted, enhanced_context_granted, feature_flags, default_funding_account_sync_id FROM cloud_config WHERE account_id = ?"
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
                        featureFlags = flags,
                        defaultFundingAccountSyncId = rs.getString("default_funding_account_sync_id")
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
}
