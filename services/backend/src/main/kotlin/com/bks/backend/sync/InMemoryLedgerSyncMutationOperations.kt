package com.bks.backend.sync

import com.bks.api.LedgerSyncConflictContract
import com.bks.api.LedgerSyncEntityTypeContract
import com.bks.api.LedgerSyncMutationContract
import com.bks.api.LedgerSyncMutationResultContract
import com.bks.api.LedgerSyncRecordContract
import java.util.UUID

internal data class InMemoryLedgerSyncRecordKey(
    val accountId: Long,
    val entityType: LedgerSyncEntityTypeContract,
    val entityId: String
)

internal class InMemoryLedgerSyncMutationOperations(
    private val records: MutableMap<InMemoryLedgerSyncRecordKey, LedgerSyncRecordContract>,
    private val changes: MutableList<Pair<Long, LedgerSyncRecordContract>>,
    private val conflicts: MutableMap<String, Pair<Long, LedgerSyncConflictContract>>,
    private val mutationResults: MutableMap<Pair<Long, String>, LedgerSyncMutationResultContract>,
    private val businessKeys: MutableMap<InMemoryLedgerSyncRecordKey, String>,
    private val nextRevision: () -> Long
) {
    fun apply(
        accountId: Long,
        item: NormalizedLedgerSyncMutation,
        now: Long
    ): LedgerSyncMutationResultContract {
        val key = accountId to item.mutation.mutationId
        return mutationResults[key]
            ?: applyMutation(accountId, item.mutation, now)
                .copy(canonicalEntityId = item.canonicalEntityId)
                .also { mutationResults[key] = it }
    }

    fun withCurrentCanonical(
        conflict: LedgerSyncConflictContract,
        accountId: Long
    ): LedgerSyncConflictContract {
        val key = InMemoryLedgerSyncRecordKey(accountId, conflict.entityType, conflict.entityId)
        val current = records[key] ?: return conflict
        return conflict.copy(
            canonicalVersion = current.version,
            canonicalDeleted = current.deleted,
            canonicalPayload = current.payload
        )
    }

    private fun applyMutation(
        accountId: Long,
        mutation: LedgerSyncMutationContract,
        now: Long
    ): LedgerSyncMutationResultContract {
        val key = InMemoryLedgerSyncRecordKey(accountId, mutation.entityType, mutation.entityId)
        val current = records[key]
        val currentVersion = current?.version ?: 0L
        return if (currentVersion != mutation.baseVersion) {
            createConflict(accountId, mutation, current, currentVersion, now)
        } else {
            acceptMutation(accountId, key, mutation, currentVersion)
        }
    }

    private fun createConflict(
        accountId: Long,
        mutation: LedgerSyncMutationContract,
        current: LedgerSyncRecordContract?,
        currentVersion: Long,
        now: Long
    ): LedgerSyncMutationResultContract {
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

    private fun acceptMutation(
        accountId: Long,
        key: InMemoryLedgerSyncRecordKey,
        mutation: LedgerSyncMutationContract,
        currentVersion: Long
    ): LedgerSyncMutationResultContract {
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

    fun newRecord(
        key: InMemoryLedgerSyncRecordKey,
        version: Long,
        deleted: Boolean,
        payload: com.bks.api.LedgerSyncPayloadContract?
    ): LedgerSyncRecordContract = LedgerSyncRecordContract(
        entityType = key.entityType,
        entityId = key.entityId,
        version = version,
        revision = nextRevision(),
        deleted = deleted,
        payload = payload
    )
}
