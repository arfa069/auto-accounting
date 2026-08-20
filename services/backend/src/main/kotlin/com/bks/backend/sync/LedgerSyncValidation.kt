package com.bks.backend.sync

import com.bks.api.LEDGER_SYNC_MAX_BATCH_SIZE
import com.bks.api.LedgerSyncMutationContract
import com.bks.api.LedgerSyncPayloadContract

internal const val MAX_SYNC_DEVICE_ID_LENGTH = 128

internal fun isValidLedgerSyncSnapshotRequest(offset: Int, limit: Int): Boolean =
    offset >= 0 && limit in 1..LEDGER_SYNC_MAX_BATCH_SIZE

internal fun String.isValidLedgerSyncDeviceId(): Boolean = isNotBlank() && length <= MAX_SYNC_DEVICE_ID_LENGTH

internal fun isValidLedgerSyncPushRequest(
    deviceId: String,
    mutations: List<LedgerSyncMutationContract>
): Boolean =
    deviceId.isValidLedgerSyncDeviceId() &&
        mutations.isNotEmpty() &&
        mutations.size <= LEDGER_SYNC_MAX_BATCH_SIZE &&
        mutations.map { it.mutationId }.distinct().size == mutations.size &&
        mutations.all(LedgerSyncMutationContract::isValid)

internal fun isValidLedgerSyncPullRequest(deviceId: String, afterCursor: Long, limit: Int): Boolean =
    deviceId.isValidLedgerSyncDeviceId() &&
        afterCursor >= 0 &&
        limit in 1..LEDGER_SYNC_MAX_BATCH_SIZE

internal fun isValidLedgerSyncResolveRequest(conflictId: String, expectedCanonicalVersion: Long): Boolean =
    conflictId.isNotBlank() &&
        conflictId.length <= MAX_SYNC_CONFLICT_ID_LENGTH &&
        expectedCanonicalVersion >= 0

internal fun LedgerSyncMutationContract.isValid(): Boolean {
    if (!hasValidIdentity()) return false
    if (deleted != (payload == null)) return false
    return payload?.isValidForEntity(entityId) ?: true
}

private fun LedgerSyncMutationContract.hasValidIdentity(): Boolean =
    mutationId.isNotBlank() &&
        mutationId.length <= MAX_SYNC_MUTATION_ID_LENGTH &&
        entityId.isNotBlank() &&
        entityId.length <= MAX_SYNC_ENTITY_ID_LENGTH &&
        baseVersion >= 0

private fun LedgerSyncPayloadContract.isValidForEntity(entityId: String): Boolean = when (this) {
    is LedgerSyncPayloadContract.Category -> id == entityId && name.isNotBlank()
    is LedgerSyncPayloadContract.FundingAccount -> syncId == entityId && label.isNotBlank()
    is LedgerSyncPayloadContract.LedgerBook -> id == entityId && name.isNotBlank()
    is LedgerSyncPayloadContract.LedgerEntry ->
        id == entityId && ledgerBookId.isNotBlank() && amountMinor > 0 &&
            currency == "CNY" && merchantTitle.isNotBlank()
    is LedgerSyncPayloadContract.CategorizationRule -> id == entityId && category.isNotBlank()
}

private const val MAX_SYNC_MUTATION_ID_LENGTH = 64
private const val MAX_SYNC_ENTITY_ID_LENGTH = 128
private const val MAX_SYNC_CONFLICT_ID_LENGTH = 64
