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
internal class JdbcAccountLedgerSyncMerger {
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun merge(
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

}

