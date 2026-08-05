package com.autoaccounting.feature.sync

import com.autoaccounting.api.LedgerSyncConflictContract
import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncJsonContracts
import com.autoaccounting.api.LedgerSyncMutationResultContract
import com.autoaccounting.api.LedgerSyncPayloadContract
import com.autoaccounting.api.LedgerSyncRecordContract
import com.autoaccounting.data.local.AccountSyncMetadataEntity
import com.autoaccounting.data.local.AutoAccountingDatabase
import com.autoaccounting.data.local.FundingAccountSourceScope

internal suspend fun applyPushResult(
    database: AutoAccountingDatabase,
    recordApplier: LedgerSyncRecordApplier,
    result: LedgerSyncMutationResultContract
) {
    val outbox = database.ledgerSyncDao().getOutbox(result.mutationId) ?: return
    val canonicalId = result.canonicalEntityId ?: outbox.entityId
    val canonicalPayload = if (canonicalId == outbox.entityId || outbox.payload == null) {
        outbox.payload
    } else {
        val type = LedgerSyncEntityTypeContract.valueOf(outbox.entityType)
        val payload = LedgerSyncJsonContracts.parsePayload(type, outbox.payload)
        val remapped = when (payload) {
            is LedgerSyncPayloadContract.Category -> {
                database.categoryDao().getCategory(outbox.entityId)?.let { local ->
                    recordApplier.remapCategoryIdentity(local, canonicalId, local.name)
                }
                payload.copy(id = canonicalId)
            }
            is LedgerSyncPayloadContract.FundingAccount -> {
                database.fundingAccountDao().findBySyncId(outbox.entityId)?.let { local ->
                    check(database.fundingAccountDao().setSyncId(local.id, canonicalId) == 1)
                }
                payload.copy(syncId = canonicalId)
            }
            else -> payload
        }
        LedgerSyncJsonContracts.encodePayload(type, remapped)
    }
    if (result.accepted) {
        database.ledgerSyncDao().upsertMetadata(
            AccountSyncMetadataEntity(
                entityType = outbox.entityType,
                entityId = canonicalId,
                serverVersion = requireNotNull(result.version),
                syncedPayload = canonicalPayload,
                deleted = outbox.deleted,
                blockedByConflict = false
            )
        )
    } else {
        val metadata = database.ledgerSyncDao().getMetadata(outbox.entityType, canonicalId)
        database.ledgerSyncDao().upsertMetadata(
            (metadata ?: AccountSyncMetadataEntity(
                outbox.entityType, canonicalId, outbox.baseVersion, canonicalPayload, true
            )).copy(blockedByConflict = true)
        )
    }
    if (canonicalId != outbox.entityId) {
        database.ledgerSyncDao().deleteMetadata(outbox.entityType, outbox.entityId)
    }
    database.ledgerSyncDao().deleteOutbox(result.mutationId)
}

internal suspend fun canonicalizeBusinessKeys(
    database: AutoAccountingDatabase,
    recordApplier: LedgerSyncRecordApplier,
    records: List<LedgerSyncRecordContract>
) {
    records.forEach { record ->
        if (record.deleted) return@forEach
        when (val payload = record.payload) {
            is LedgerSyncPayloadContract.Category ->
                canonicalizeCategory(database, recordApplier, payload)
            is LedgerSyncPayloadContract.FundingAccount ->
                canonicalizeFundingAccount(database, payload)
            else -> Unit
        }
    }
}

private suspend fun canonicalizeCategory(
    database: AutoAccountingDatabase,
    recordApplier: LedgerSyncRecordApplier,
    payload: LedgerSyncPayloadContract.Category
) {
    val local = database.categoryDao().findByName(payload.name)
    if (local == null || local.id == payload.id) return
    recordApplier.remapCategoryIdentity(local, payload.id, payload.name)
    database.ledgerSyncDao().deleteOutboxForEntity(
        LedgerSyncEntityTypeContract.CATEGORY.name,
        local.id
    )
}

private suspend fun canonicalizeFundingAccount(
    database: AutoAccountingDatabase,
    payload: LedgerSyncPayloadContract.FundingAccount
) {
    val scope = FundingAccountSourceScope.valueOf(payload.sourceScope)
    val local = database.fundingAccountDao().findByScopeAndLabel(scope, payload.label)
    if (local == null || local.syncId == payload.syncId) return
    local.syncId?.let { oldSyncId ->
        database.ledgerSyncDao().deleteOutboxForEntity(
            LedgerSyncEntityTypeContract.FUNDING_ACCOUNT.name,
            oldSyncId
        )
    }
    check(database.fundingAccountDao().setSyncId(local.id, payload.syncId) == 1)
}
