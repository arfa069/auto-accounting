package com.autoaccounting.backend.sync

import com.autoaccounting.api.LedgerSyncConflictChoiceContract
import com.autoaccounting.api.LedgerSyncConflictContract
import com.autoaccounting.api.LedgerSyncMutationContract
import com.autoaccounting.api.LedgerSyncMutationResultContract
import com.autoaccounting.api.LedgerSyncRecordContract
import java.util.UUID

data class StoredLedgerSyncProfile(
    val accountId: Long,
    val profileKey: String,
    val createdAtMillis: Long
)

data class LedgerSyncPullPage(
    val records: List<LedgerSyncRecordContract>,
    val conflicts: List<LedgerSyncConflictContract>,
    val nextCursor: Long,
    val hasMore: Boolean
)

sealed interface LedgerSyncResolutionResult {
    data class Resolved(val record: LedgerSyncRecordContract) : LedgerSyncResolutionResult
    data object Missing : LedgerSyncResolutionResult
    data object Stale : LedgerSyncResolutionResult
}

interface LedgerSyncStore {
    fun getOrCreateProfile(accountId: Long, now: Long): StoredLedgerSyncProfile
    fun recordCount(accountId: Long): Int
    fun currentCursor(accountId: Long): Long
    fun snapshot(accountId: Long, offset: Int, limit: Int): List<LedgerSyncRecordContract>
    fun push(
        accountId: Long,
        deviceId: String,
        mutations: List<LedgerSyncMutationContract>,
        now: Long
    ): List<LedgerSyncMutationResultContract>
    fun pull(accountId: Long, afterCursor: Long, limit: Int): LedgerSyncPullPage
    fun resolve(
        accountId: Long,
        conflictId: String,
        expectedCanonicalVersion: Long,
        choice: LedgerSyncConflictChoiceContract,
        now: Long
    ): LedgerSyncResolutionResult
    fun deleteForAccount(accountId: Long)
}

class InMemoryLedgerSyncStore : LedgerSyncStore {
    private val profiles = mutableMapOf<Long, StoredLedgerSyncProfile>()
    private val records = mutableMapOf<InMemoryLedgerSyncRecordKey, LedgerSyncRecordContract>()
    private val changes = mutableListOf<Pair<Long, LedgerSyncRecordContract>>()
    private val conflicts = mutableMapOf<String, Pair<Long, LedgerSyncConflictContract>>()
    private val mutationResults = mutableMapOf<Pair<Long, String>, LedgerSyncMutationResultContract>()
    private val businessKeys = mutableMapOf<InMemoryLedgerSyncRecordKey, String>()
    private var revision = 0L
    private val mutationOperations = InMemoryLedgerSyncMutationOperations(
        records = records,
        changes = changes,
        conflicts = conflicts,
        mutationResults = mutationResults,
        businessKeys = businessKeys,
        nextRevision = { ++revision }
    )

    @Synchronized
    override fun getOrCreateProfile(accountId: Long, now: Long): StoredLedgerSyncProfile =
        profiles.getOrPut(accountId) {
            StoredLedgerSyncProfile(accountId, UUID.randomUUID().toString(), now)
        }

    @Synchronized
    override fun recordCount(accountId: Long): Int = records.keys.count { it.accountId == accountId }

    @Synchronized
    override fun currentCursor(accountId: Long): Long = changes.asSequence()
        .filter { it.first == accountId }
        .maxOfOrNull { it.second.revision }
        ?: 0L

    @Synchronized
    override fun snapshot(accountId: Long, offset: Int, limit: Int): List<LedgerSyncRecordContract> =
        records.entries.asSequence()
            .filter { it.key.accountId == accountId }
            .sortedWith(compareBy({ it.key.entityType.name }, { it.key.entityId }))
            .drop(offset)
            .take(limit)
            .map { it.value }
            .toList()

    @Synchronized
    override fun push(
        accountId: Long,
        deviceId: String,
        mutations: List<LedgerSyncMutationContract>,
        now: Long
    ): List<LedgerSyncMutationResultContract> {
        val normalized = normalizeBusinessMutations(mutations) { type, payload ->
            records.entries.asSequence()
                .filter { it.key.accountId == accountId && it.key.entityType == type }
                .map { it.value }
                .firstOrNull { record ->
                    businessKeys[
                        InMemoryLedgerSyncRecordKey(accountId, record.entityType, record.entityId)
                    ] == payload.businessKey()
                }
        }
        return normalized.map { item ->
            mutationOperations.apply(accountId, item, now)
        }
    }

    @Synchronized
    override fun pull(accountId: Long, afterCursor: Long, limit: Int): LedgerSyncPullPage {
        val page = changes.asSequence()
            .filter { it.first == accountId && it.second.revision > afterCursor }
            .map { it.second }
            .take(limit + 1)
            .toList()
        val visible = page.take(limit)
        return LedgerSyncPullPage(
                records = visible,
                conflicts = conflicts.values.asSequence()
                    .filter { it.first == accountId }
                    .map { mutationOperations.withCurrentCanonical(it.second, accountId) }
                    .sortedBy { it.createdAtMillis }
                .toList(),
            nextCursor = visible.lastOrNull()?.revision ?: afterCursor,
            hasMore = page.size > limit
        )
    }

    @Synchronized
    override fun resolve(
        accountId: Long,
        conflictId: String,
        expectedCanonicalVersion: Long,
        choice: LedgerSyncConflictChoiceContract,
        now: Long
    ): LedgerSyncResolutionResult {
        val stored = conflicts[conflictId]?.takeIf { it.first == accountId }
            ?: return LedgerSyncResolutionResult.Missing
        val conflict = stored.second
        val key = InMemoryLedgerSyncRecordKey(accountId, conflict.entityType, conflict.entityId)
        val current = records[key]
        val currentVersion = current?.version ?: 0L
        if (currentVersion != expectedCanonicalVersion) return LedgerSyncResolutionResult.Stale
        val resolved = if (choice == LedgerSyncConflictChoiceContract.CANONICAL) {
            current ?: return LedgerSyncResolutionResult.Stale
        } else {
            mutationOperations.newRecord(
                key = key,
                version = currentVersion + 1,
                deleted = conflict.candidateDeleted,
                payload = conflict.candidatePayload
            ).also { records[key] = it; changes += accountId to it }
        }
        conflicts.remove(conflictId)
        return LedgerSyncResolutionResult.Resolved(resolved)
    }

    @Synchronized
    override fun deleteForAccount(accountId: Long) {
        profiles.remove(accountId)
        records.keys.removeAll { it.accountId == accountId }
        changes.removeAll { it.first == accountId }
        conflicts.entries.removeAll { it.value.first == accountId }
        mutationResults.keys.removeAll { it.first == accountId }
        businessKeys.keys.removeAll { it.accountId == accountId }
    }
}
