package com.autoaccounting.backend.sync

import com.autoaccounting.api.LedgerSyncConflictChoiceContract
import com.autoaccounting.api.LedgerSyncConflictContract
import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncMutationContract
import com.autoaccounting.api.LedgerSyncMutationResultContract
import com.autoaccounting.api.LedgerSyncPayloadContract
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
    private val records = mutableMapOf<RecordKey, LedgerSyncRecordContract>()
    private val changes = mutableListOf<Pair<Long, LedgerSyncRecordContract>>()
    private val conflicts = mutableMapOf<String, Pair<Long, LedgerSyncConflictContract>>()
    private val mutationResults = mutableMapOf<Pair<Long, String>, LedgerSyncMutationResultContract>()
    private val businessKeys = mutableMapOf<RecordKey, String>()
    private var revision = 0L

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
                    businessKeys[RecordKey(accountId, record.entityType, record.entityId)] == payload.businessKey()
                }
        }
        return normalized.map { item ->
            mutationResults[accountId to item.mutation.mutationId]
                ?: applyMutation(accountId, item.mutation, now)
                    .copy(canonicalEntityId = item.canonicalEntityId)
                    .also { mutationResults[accountId to item.mutation.mutationId] = it }
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
                .map { it.second.withCurrentCanonical(accountId) }
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
        val key = RecordKey(accountId, conflict.entityType, conflict.entityId)
        val current = records[key]
        val currentVersion = current?.version ?: 0L
        if (currentVersion != expectedCanonicalVersion) return LedgerSyncResolutionResult.Stale
        val resolved = if (choice == LedgerSyncConflictChoiceContract.CANONICAL) {
            current ?: return LedgerSyncResolutionResult.Stale
        } else {
            newRecord(
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

    private fun applyMutation(
        accountId: Long,
        mutation: LedgerSyncMutationContract,
        now: Long
    ): LedgerSyncMutationResultContract {
        val key = RecordKey(accountId, mutation.entityType, mutation.entityId)
        val current = records[key]
        val currentVersion = current?.version ?: 0L
        if (currentVersion != mutation.baseVersion) {
            val conflictId = UUID.randomUUID().toString()
            conflicts[conflictId] = accountId to LedgerSyncConflictContract(
                conflictId = conflictId,
                entityType = mutation.entityType,
                entityId = mutation.entityId,
                canonicalVersion = currentVersion,
                canonicalDeleted = current?.deleted ?: true,
                canonicalPayload = current?.payload,
                candidateDeleted = mutation.deleted,
                candidatePayload = mutation.payload,
                createdAtMillis = now
            )
            return LedgerSyncMutationResultContract(mutation.mutationId, false, null, null, conflictId)
        }
        val record = newRecord(key, currentVersion + 1, mutation.deleted, mutation.payload)
        records[key] = record
        mutation.payload?.businessKey()?.let { businessKeys[key] = it }
        changes += accountId to record
        return LedgerSyncMutationResultContract(
            mutationId = mutation.mutationId,
            accepted = true,
            version = record.version,
            revision = record.revision,
            conflictId = null
        )
    }

    private fun newRecord(
        key: RecordKey,
        version: Long,
        deleted: Boolean,
        payload: LedgerSyncPayloadContract?
    ): LedgerSyncRecordContract = LedgerSyncRecordContract(
        entityType = key.entityType,
        entityId = key.entityId,
        version = version,
        revision = ++revision,
        deleted = deleted,
        payload = payload
    )

    private data class RecordKey(
        val accountId: Long,
        val entityType: LedgerSyncEntityTypeContract,
        val entityId: String
    )

    private fun LedgerSyncConflictContract.withCurrentCanonical(accountId: Long): LedgerSyncConflictContract {
        val current = records[RecordKey(accountId, entityType, entityId)] ?: return this
        return copy(
            canonicalVersion = current.version,
            canonicalDeleted = current.deleted,
            canonicalPayload = current.payload
        )
    }
}

internal fun LedgerSyncPayloadContract.businessKey(): String? = when (this) {
    is LedgerSyncPayloadContract.Category -> "CATEGORY\u001f$name"
    is LedgerSyncPayloadContract.FundingAccount -> "FUNDING_ACCOUNT\u001f$sourceScope\u001f$label"
    else -> null
}

internal data class NormalizedLedgerSyncMutation(
    val mutation: LedgerSyncMutationContract,
    val canonicalEntityId: String?
)

internal fun normalizeBusinessMutations(
    mutations: List<LedgerSyncMutationContract>,
    findCanonical: (LedgerSyncEntityTypeContract, LedgerSyncPayloadContract) -> LedgerSyncRecordContract?
): List<NormalizedLedgerSyncMutation> {
    val categoryRemaps = mutableMapOf<String, String>()
    val fundingRemaps = mutableMapOf<String, String>()
    val batchCanonicals = mutableMapOf<Pair<LedgerSyncEntityTypeContract, String>, LedgerSyncRecordContract>()
    val canonicalized = mutations.map { mutation ->
        val payload = mutation.payload
        val businessKey = payload?.businessKey()
        val canonical = payload?.let { value ->
            findCanonical(mutation.entityType, value)
                ?: businessKey?.let { batchCanonicals[mutation.entityType to it] }
        }
            ?.takeIf { it.entityId != mutation.entityId }
        if (canonical == null) {
            if (payload != null && businessKey != null) {
                batchCanonicals.putIfAbsent(
                    mutation.entityType to businessKey,
                    LedgerSyncRecordContract(
                        entityType = mutation.entityType,
                        entityId = mutation.entityId,
                        version = mutation.baseVersion + 1,
                        revision = 0,
                        deleted = false,
                        payload = payload
                    )
                )
            }
            NormalizedLedgerSyncMutation(mutation, null)
        } else {
            val canonicalPayload = payload.withEntityId(canonical.entityId)
            when (mutation.entityType) {
                LedgerSyncEntityTypeContract.CATEGORY -> categoryRemaps[mutation.entityId] = canonical.entityId
                LedgerSyncEntityTypeContract.FUNDING_ACCOUNT -> fundingRemaps[mutation.entityId] = canonical.entityId
                else -> Unit
            }
            NormalizedLedgerSyncMutation(
                mutation.copy(
                    entityId = canonical.entityId,
                    baseVersion = if (canonical.payload == canonicalPayload) canonical.version else mutation.baseVersion,
                    payload = canonicalPayload
                ),
                canonical.entityId
            )
        }
    }
    return canonicalized.map { item ->
        val entry = item.mutation.payload as? LedgerSyncPayloadContract.LedgerEntry ?: return@map item
        item.copy(
            mutation = item.mutation.copy(
                payload = entry.copy(
                    categoryId = entry.categoryId?.let { categoryRemaps[it] ?: it },
                    fundingAccountSyncId = entry.fundingAccountSyncId?.let { fundingRemaps[it] ?: it }
                )
            )
        )
    }
}

private fun LedgerSyncPayloadContract.withEntityId(entityId: String): LedgerSyncPayloadContract = when (this) {
    is LedgerSyncPayloadContract.Category -> copy(id = entityId)
    is LedgerSyncPayloadContract.FundingAccount -> copy(syncId = entityId)
    else -> this
}
