@file:Suppress("LongMethod", "TooManyFunctions", "NestedBlockDepth")

package com.bks.backend.sync

import com.bks.api.LedgerSyncConflictChoiceContract
import com.bks.api.LedgerSyncConflictContract
import com.bks.api.LedgerSyncEntityTypeContract
import com.bks.api.LedgerSyncJsonContracts
import com.bks.api.LedgerSyncMutationContract
import com.bks.api.LedgerSyncMutationResultContract
import com.bks.api.LedgerSyncPayloadContract
import com.bks.api.LedgerSyncRecordContract
import com.bks.backend.jdbcConnection
import com.bks.backend.runBackendMigrations
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import java.util.UUID

private data class AcceptedRecordWrite(
    val connection: Connection,
    val accountId: Long,
    val entityType: LedgerSyncEntityTypeContract,
    val entityId: String,
    val version: Long,
    val deleted: Boolean,
    val payload: LedgerSyncPayloadContract?,
    val now: Long
)

class JdbcLedgerSyncStore(
    private val jdbcUrl: String,
    private val username: String = "",
    private val password: String = ""
) : LedgerSyncStore {
    init {
        runBackendMigrations(jdbcUrl, username, password)
    }

    override fun getOrCreateProfile(accountId: Long, now: Long): StoredLedgerSyncProfile =
        connection().use { connection ->
            findProfile(connection, accountId) ?: run {
                val profile = StoredLedgerSyncProfile(accountId, UUID.randomUUID().toString(), now)
                runCatching {
                    connection.prepareStatement(
                        "INSERT INTO ledger_sync_profiles(account_id, profile_key, created_at_millis) VALUES (?, ?, ?)"
                    ).use { statement ->
                        statement.setLong(1, accountId)
                        statement.setString(2, profile.profileKey)
                        statement.setLong(3, now)
                        statement.executeUpdate()
                    }
                }.fold(
                    onSuccess = { profile },
                    onFailure = { findProfile(connection, accountId) ?: throw it }
                )
            }
        }

    override fun recordCount(accountId: Long): Int = connection().use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM ledger_sync_records WHERE account_id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
    }

    override fun currentCursor(accountId: Long): Long = connection().use { currentCursor(it, accountId) }

    override fun snapshot(accountId: Long, offset: Int, limit: Int): List<LedgerSyncRecordContract> =
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT entity_type, entity_id, version, revision, deleted, payload
                FROM ledger_sync_records
                WHERE account_id = ?
                ORDER BY entity_type, entity_id
                LIMIT ? OFFSET ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.setInt(2, limit)
                statement.setInt(3, offset)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toRecord()) } }
            }
        }

    override fun push(
        accountId: Long,
        deviceId: String,
        mutations: List<LedgerSyncMutationContract>,
        now: Long
    ): List<LedgerSyncMutationResultContract> = connection().use { connection ->
        connection.inTransaction {
            connection.prepareStatement(
                "SELECT account_id FROM ledger_sync_profiles WHERE account_id = ? FOR UPDATE"
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { result -> check(result.next()) }
            }
            val normalized = normalizeBusinessMutations(mutations) { type, payload ->
                findBusinessCanonical(connection, accountId, type, payload)
            }
            normalized.map { item ->
                findMutationResult(connection, accountId, item.mutation.mutationId)
                    ?: applyMutation(connection, accountId, item.mutation, now)
                        .copy(canonicalEntityId = item.canonicalEntityId)
                        .also { result ->
                        insertMutationResult(connection, accountId, result, now)
                    }
            }
        }
    }

    override fun pull(accountId: Long, afterCursor: Long, limit: Int): LedgerSyncPullPage =
        connection().use { connection ->
            val changes = connection.prepareStatement(
                """
                SELECT revision, entity_type, entity_id, version, deleted, payload
                FROM ledger_sync_changes
                WHERE account_id = ? AND revision > ?
                ORDER BY revision
                LIMIT ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.setLong(2, afterCursor)
                statement.setInt(3, limit + 1)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toRecord()) } }
            }
            val visible = changes.take(limit)
            val conflicts = connection.prepareStatement(
                """
                SELECT conflict_id, entity_type, entity_id, canonical_version,
                       canonical_deleted, canonical_payload, candidate_deleted,
                       candidate_payload, created_at_millis
                FROM ledger_sync_conflicts
                WHERE account_id = ? AND resolved = FALSE
                ORDER BY created_at_millis, conflict_id
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toConflict()) } }
            }.map { conflict ->
                findRecord(connection, accountId, conflict.entityType, conflict.entityId)?.let { current ->
                    conflict.copy(
                        canonicalVersion = current.version,
                        canonicalDeleted = current.deleted,
                        canonicalPayload = current.payload
                    )
                } ?: conflict
            }
            LedgerSyncPullPage(
                records = visible,
                conflicts = conflicts,
                nextCursor = visible.lastOrNull()?.revision ?: afterCursor,
                hasMore = changes.size > limit
            )
        }

    override fun resolve(
        accountId: Long,
        conflictId: String,
        expectedCanonicalVersion: Long,
        choice: LedgerSyncConflictChoiceContract,
        now: Long
    ): LedgerSyncResolutionResult = connection().use { connection ->
        connection.inTransaction {
            val conflict = findConflictForUpdate(connection, accountId, conflictId)
                ?: return@inTransaction LedgerSyncResolutionResult.Missing
            val current = findRecordForUpdate(connection, accountId, conflict.entityType, conflict.entityId)
            if ((current?.version ?: 0L) != expectedCanonicalVersion) {
                return@inTransaction LedgerSyncResolutionResult.Stale
            }
            val resolved = if (choice == LedgerSyncConflictChoiceContract.CANONICAL && current != null) {
                current
            } else {
                val deleted = if (choice == LedgerSyncConflictChoiceContract.CANDIDATE) {
                    conflict.candidateDeleted
                } else {
                    true
                }
                val payload = if (choice == LedgerSyncConflictChoiceContract.CANDIDATE) {
                    conflict.candidatePayload
                } else {
                    null
                }
                writeAcceptedRecord(
                    AcceptedRecordWrite(
                        connection = connection,
                        accountId = accountId,
                        entityType = conflict.entityType,
                        entityId = conflict.entityId,
                        version = (current?.version ?: 0L) + 1,
                        deleted = deleted,
                        payload = payload,
                        now = now
                    )
                )
            }
            connection.prepareStatement(
                "UPDATE ledger_sync_conflicts SET resolved = TRUE WHERE conflict_id = ? AND account_id = ?"
            ).use { statement ->
                statement.setString(1, conflictId)
                statement.setLong(2, accountId)
                statement.executeUpdate()
            }
            LedgerSyncResolutionResult.Resolved(resolved)
        }
    }

    override fun deleteForAccount(accountId: Long) {
        connection().use { connection ->
            connection.inTransaction {
                listOf(
                    "ledger_sync_conflicts",
                    "ledger_sync_mutations",
                    "ledger_sync_changes",
                    "ledger_sync_records",
                    "ledger_sync_profiles"
                ).forEach { table ->
                    connection.prepareStatement("DELETE FROM $table WHERE account_id = ?").use { statement ->
                        statement.setLong(1, accountId)
                        statement.executeUpdate()
                    }
                }
            }
        }
    }

    private fun applyMutation(
        connection: Connection,
        accountId: Long,
        mutation: LedgerSyncMutationContract,
        now: Long
    ): LedgerSyncMutationResultContract {
        val current = findRecordForUpdate(connection, accountId, mutation.entityType, mutation.entityId)
        val currentVersion = current?.version ?: 0L
        if (currentVersion != mutation.baseVersion) {
            val conflictId = UUID.randomUUID().toString()
            connection.prepareStatement(
                """
                INSERT INTO ledger_sync_conflicts(
                    conflict_id, account_id, entity_type, entity_id, canonical_version,
                    canonical_deleted, canonical_payload, candidate_deleted, candidate_payload,
                    created_at_millis, resolved
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, conflictId)
                statement.setLong(2, accountId)
                statement.setString(3, mutation.entityType.name)
                statement.setString(4, mutation.entityId)
                statement.setLong(5, currentVersion)
                statement.setBoolean(6, current?.deleted ?: true)
                statement.setNullableString(7, current?.payload?.let { encodePayload(mutation.entityType, it) })
                statement.setBoolean(8, mutation.deleted)
                statement.setNullableString(9, mutation.payload?.let { encodePayload(mutation.entityType, it) })
                statement.setLong(10, now)
                statement.executeUpdate()
            }
            return LedgerSyncMutationResultContract(mutation.mutationId, false, null, null, conflictId)
        }
        val record = writeAcceptedRecord(
            AcceptedRecordWrite(
                connection = connection,
                accountId = accountId,
                entityType = mutation.entityType,
                entityId = mutation.entityId,
                version = currentVersion + 1,
                deleted = mutation.deleted,
                payload = mutation.payload,
                now = now
            )
        )
        return LedgerSyncMutationResultContract(
            mutationId = mutation.mutationId,
            accepted = true,
            version = record.version,
            revision = record.revision,
            conflictId = null
        )
    }

    private fun writeAcceptedRecord(request: AcceptedRecordWrite): LedgerSyncRecordContract {
        val encoded = request.payload?.let { encodePayload(request.entityType, it) }
        val businessKey = request.payload?.businessKey() ?: findBusinessKey(
            request.connection,
            request.accountId,
            request.entityType,
            request.entityId
        )
        val revision = request.connection.prepareStatement(
            """
            INSERT INTO ledger_sync_changes(
                account_id, entity_type, entity_id, version, deleted, payload, changed_at_millis
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS
        ).use { statement ->
            statement.setLong(1, request.accountId)
            statement.setString(2, request.entityType.name)
            statement.setString(3, request.entityId)
            statement.setLong(4, request.version)
            statement.setBoolean(5, request.deleted)
            statement.setNullableString(6, encoded)
            statement.setLong(7, request.now)
            statement.executeUpdate()
            statement.generatedKeys.use { keys -> check(keys.next()); keys.getLong(1) }
        }
        val updated = request.connection.prepareStatement(
            """
            UPDATE ledger_sync_records
            SET version = ?, revision = ?, deleted = ?, payload = ?, business_key = ?, updated_at_millis = ?
            WHERE account_id = ? AND entity_type = ? AND entity_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, request.version)
            statement.setLong(2, revision)
            statement.setBoolean(3, request.deleted)
            statement.setNullableString(4, encoded)
            statement.setNullableString(5, businessKey)
            statement.setLong(6, request.now)
            statement.setLong(7, request.accountId)
            statement.setString(8, request.entityType.name)
            statement.setString(9, request.entityId)
            statement.executeUpdate()
        }
        if (updated == 0) {
            request.connection.prepareStatement(
                """
                INSERT INTO ledger_sync_records(
                    account_id, entity_type, entity_id, version, revision,
                    deleted, payload, business_key, updated_at_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, request.accountId)
                statement.setString(2, request.entityType.name)
                statement.setString(3, request.entityId)
                statement.setLong(4, request.version)
                statement.setLong(5, revision)
                statement.setBoolean(6, request.deleted)
                statement.setNullableString(7, encoded)
                statement.setNullableString(8, businessKey)
                statement.setLong(9, request.now)
                statement.executeUpdate()
            }
        }
        return LedgerSyncRecordContract(
            request.entityType,
            request.entityId,
            request.version,
            revision,
            request.deleted,
            request.payload
        )
    }

    private fun findProfile(connection: Connection, accountId: Long): StoredLedgerSyncProfile? =
        connection.prepareStatement(
            "SELECT account_id, profile_key, created_at_millis FROM ledger_sync_profiles WHERE account_id = ?"
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { result ->
                if (result.next()) StoredLedgerSyncProfile(
                    result.getLong("account_id"), result.getString("profile_key"), result.getLong("created_at_millis")
                ) else null
            }
        }

    private fun findRecordForUpdate(
        connection: Connection,
        accountId: Long,
        entityType: LedgerSyncEntityTypeContract,
        entityId: String
    ): LedgerSyncRecordContract? = connection.prepareStatement(
        """
        SELECT entity_type, entity_id, version, revision, deleted, payload
        FROM ledger_sync_records
        WHERE account_id = ? AND entity_type = ? AND entity_id = ?
        FOR UPDATE
        """.trimIndent()
    ).use { statement ->
        statement.setLong(1, accountId)
        statement.setString(2, entityType.name)
        statement.setString(3, entityId)
        statement.executeQuery().use { result -> if (result.next()) result.toRecord() else null }
    }

    private fun findRecord(
        connection: Connection,
        accountId: Long,
        entityType: LedgerSyncEntityTypeContract,
        entityId: String
    ): LedgerSyncRecordContract? = connection.prepareStatement(
        """
        SELECT entity_type, entity_id, version, revision, deleted, payload
        FROM ledger_sync_records
        WHERE account_id = ? AND entity_type = ? AND entity_id = ?
        """.trimIndent()
    ).use { statement ->
        statement.setLong(1, accountId)
        statement.setString(2, entityType.name)
        statement.setString(3, entityId)
        statement.executeQuery().use { result -> if (result.next()) result.toRecord() else null }
    }

    private fun findBusinessCanonical(
        connection: Connection,
        accountId: Long,
        entityType: LedgerSyncEntityTypeContract,
        payload: LedgerSyncPayloadContract
    ): LedgerSyncRecordContract? {
        val businessKey = payload.businessKey() ?: return null
        return connection.prepareStatement(
        """
        SELECT entity_type, entity_id, version, revision, deleted, payload
        FROM ledger_sync_records
        WHERE account_id = ? AND entity_type = ? AND business_key = ?
        ORDER BY entity_id
        """.trimIndent()
    ).use { statement ->
        statement.setLong(1, accountId)
        statement.setString(2, entityType.name)
        statement.setString(3, businessKey)
        statement.executeQuery().use { result -> if (result.next()) result.toRecord() else null }
    }
    }

    private fun findBusinessKey(
        connection: Connection,
        accountId: Long,
        entityType: LedgerSyncEntityTypeContract,
        entityId: String
    ): String? = connection.prepareStatement(
        "SELECT business_key FROM ledger_sync_records " +
            "WHERE account_id = ? AND entity_type = ? AND entity_id = ?"
    ).use { statement ->
        statement.setLong(1, accountId)
        statement.setString(2, entityType.name)
        statement.setString(3, entityId)
        statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
    }

    private fun findMutationResult(
        connection: Connection,
        accountId: Long,
        mutationId: String
    ): LedgerSyncMutationResultContract? = connection.prepareStatement(
        "SELECT accepted, version, revision, conflict_id, canonical_entity_id " +
            "FROM ledger_sync_mutations WHERE account_id = ? AND mutation_id = ?"
    ).use { statement ->
        statement.setLong(1, accountId)
        statement.setString(2, mutationId)
        statement.executeQuery().use { result ->
            if (!result.next()) null else LedgerSyncMutationResultContract(
                mutationId, result.getBoolean("accepted"), result.nullableLong("version"),
                result.nullableLong("revision"), result.getString("conflict_id"),
                result.getString("canonical_entity_id")
            )
        }
    }

    private fun insertMutationResult(
        connection: Connection,
        accountId: Long,
        result: LedgerSyncMutationResultContract,
        now: Long
    ) {
        connection.prepareStatement(
            """
            INSERT INTO ledger_sync_mutations(
                account_id, mutation_id, accepted, version, revision, conflict_id,
                canonical_entity_id, processed_at_millis
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, result.mutationId)
            statement.setBoolean(3, result.accepted)
            statement.setNullableLong(4, result.version)
            statement.setNullableLong(5, result.revision)
            statement.setNullableString(6, result.conflictId)
            statement.setNullableString(7, result.canonicalEntityId)
            statement.setLong(8, now)
            statement.executeUpdate()
        }
    }

    private fun findConflictForUpdate(
        connection: Connection,
        accountId: Long,
        conflictId: String
    ): LedgerSyncConflictContract? = connection.prepareStatement(
        """
        SELECT conflict_id, entity_type, entity_id, canonical_version,
               canonical_deleted, canonical_payload, candidate_deleted,
               candidate_payload, created_at_millis
        FROM ledger_sync_conflicts
        WHERE account_id = ? AND conflict_id = ? AND resolved = FALSE
        FOR UPDATE
        """.trimIndent()
    ).use { statement ->
        statement.setLong(1, accountId)
        statement.setString(2, conflictId)
        statement.executeQuery().use { result -> if (result.next()) result.toConflict() else null }
    }

    private fun currentCursor(connection: Connection, accountId: Long): Long =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(revision), 0) FROM ledger_sync_changes WHERE account_id = ?"
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }

    private fun ResultSet.toRecord(): LedgerSyncRecordContract {
        val type = LedgerSyncEntityTypeContract.valueOf(getString("entity_type"))
        val deleted = getBoolean("deleted")
        val payload = getString("payload")?.let { LedgerSyncJsonContracts.parsePayload(type, it) }
        return LedgerSyncRecordContract(
            type, getString("entity_id"), getLong("version"), getLong("revision"), deleted, payload
        )
    }

    private fun ResultSet.toConflict(): LedgerSyncConflictContract {
        val type = LedgerSyncEntityTypeContract.valueOf(getString("entity_type"))
        return LedgerSyncConflictContract(
            conflictId = getString("conflict_id"),
            entityType = type,
            entityId = getString("entity_id"),
            canonicalVersion = getLong("canonical_version"),
            canonicalDeleted = getBoolean("canonical_deleted"),
            canonicalPayload = getString("canonical_payload")?.let { LedgerSyncJsonContracts.parsePayload(type, it) },
            candidateDeleted = getBoolean("candidate_deleted"),
            candidatePayload = getString("candidate_payload")?.let { LedgerSyncJsonContracts.parsePayload(type, it) },
            createdAtMillis = getLong("created_at_millis")
        )
    }

    private fun encodePayload(type: LedgerSyncEntityTypeContract, payload: LedgerSyncPayloadContract): String =
        LedgerSyncJsonContracts.encodePayload(type, payload)

    private fun connection(): Connection = jdbcConnection(jdbcUrl, username, password)
}

private inline fun <T> Connection.inTransaction(block: () -> T): T {
    autoCommit = false
    return try {
        block().also { commit() }
    } catch (error: Throwable) {
        rollback()
        throw error
    } finally {
        autoCommit = true
    }
}

private fun java.sql.PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
}

private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
}

private fun ResultSet.nullableLong(name: String): Long? = getLong(name).let { if (wasNull()) null else it }
