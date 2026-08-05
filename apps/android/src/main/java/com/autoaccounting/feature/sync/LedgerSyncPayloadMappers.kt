package com.autoaccounting.feature.sync

import com.autoaccounting.api.LedgerSyncConflictContract
import com.autoaccounting.api.LedgerSyncJsonContracts
import com.autoaccounting.api.LedgerSyncMutationContract
import com.autoaccounting.api.LedgerSyncPayloadContract
import com.autoaccounting.api.LedgerSyncRecordContract
import com.autoaccounting.data.local.AccountSyncConflictEntity
import com.autoaccounting.data.local.AccountSyncMetadataEntity
import com.autoaccounting.data.local.AccountSyncOutboxEntity
import com.autoaccounting.data.local.CategorizationRuleEntity
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.LedgerBookEntity
import com.autoaccounting.data.local.LedgerEntryEntity

internal fun AccountSyncOutboxEntity.toContract(): LedgerSyncMutationContract {
    val type = com.autoaccounting.api.LedgerSyncEntityTypeContract.valueOf(entityType)
    return LedgerSyncMutationContract(
        mutationId = mutationId,
        entityType = type,
        entityId = entityId,
        baseVersion = baseVersion,
        deleted = deleted,
        payload = payload?.let { LedgerSyncJsonContracts.parsePayload(type, it) }
    )
}

internal fun LedgerSyncRecordContract.toMetadata() = AccountSyncMetadataEntity(
    entityType = entityType.name,
    entityId = entityId,
    serverVersion = version,
    syncedPayload = payload?.let { LedgerSyncJsonContracts.encodePayload(entityType, it) },
    deleted = deleted,
    blockedByConflict = false
)

internal fun LedgerSyncConflictContract.toEntity() = AccountSyncConflictEntity(
    conflictId = conflictId,
    entityType = entityType.name,
    entityId = entityId,
    canonicalVersion = canonicalVersion,
    canonicalDeleted = canonicalDeleted,
    canonicalPayload = canonicalPayload?.let { LedgerSyncJsonContracts.encodePayload(entityType, it) },
    candidateDeleted = candidateDeleted,
    candidatePayload = candidatePayload?.let { LedgerSyncJsonContracts.encodePayload(entityType, it) },
    createdAtMillis = createdAtMillis
)

internal fun LedgerSyncConflictContract.toCanonicalRecord() = LedgerSyncRecordContract(
    entityType = entityType,
    entityId = entityId,
    version = canonicalVersion,
    revision = 0,
    deleted = canonicalDeleted,
    payload = canonicalPayload
)

internal fun LedgerSyncConflictContract.isPristineGeneratedDefaultCandidate(): Boolean =
    !candidateDeleted && candidatePayload?.isPristineGeneratedDefault() == true

internal fun CategoryEntity.toPayload() = LedgerSyncPayloadContract.Category(
    id, name, kind?.name, sortOrder, isSystem, createdAtEpochMillis
)

internal fun FundingAccountEntity.toPayload() = LedgerSyncPayloadContract.FundingAccount(
    requireNotNull(syncId), sourceScope.name, paymentSource?.name, label, createdAtEpochMillis
)

internal fun LedgerBookEntity.toPayload() = LedgerSyncPayloadContract.LedgerBook(id, name, createdAtEpochMillis)

internal fun LedgerEntryEntity.toPayload(fundingSyncId: String?) = LedgerSyncPayloadContract.LedgerEntry(
    id, ledgerBookId, paymentSource?.name, originalCaptureSource?.name, entryOrigin.name,
    flowDirection.name, transactionKind.name, amountMinor, currency, merchantTitle,
    transactionTimeEpochMillis, categoryId, fundingSyncId, note, confirmedAtEpochMillis,
    updatedAtEpochMillis, deletedAtEpochMillis
)

internal fun CategorizationRuleEntity.toPayload() = LedgerSyncPayloadContract.CategorizationRule(
    id, merchantContains, titleContains, sourceLabel, transactionKind, category,
    priority, enabled, updatedAtEpochMillis
)
