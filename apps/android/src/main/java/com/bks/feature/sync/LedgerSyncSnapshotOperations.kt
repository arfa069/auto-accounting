package com.bks.feature.sync

import com.bks.api.LedgerSyncConflictContract
import com.bks.api.LedgerSyncEntityTypeContract
import com.bks.api.LedgerSyncJsonContracts
import com.bks.api.LedgerSyncMutationResultContract
import com.bks.api.LedgerSyncPayloadContract
import com.bks.api.LedgerSyncRecordContract
import com.bks.data.local.AccountSyncMetadataEntity
import com.bks.data.local.AccountSyncOutboxEntity
import com.bks.data.local.BksDatabase
import com.bks.data.local.FundingAccountSourceScope
import com.bks.data.local.IgnoredEntryEntity
import com.bks.data.local.PendingEntryEntity

internal class LedgerSyncSnapshotOperations(
    private val database: BksDatabase,
    private val recordApplier: LedgerSyncRecordApplier,
    private val clock: () -> Long,
    private val idGenerator: () -> String
) {
    suspend fun applyPushResults(results: List<LedgerSyncMutationResultContract>) {
        results.forEach { result -> applyPushResult(database, recordApplier, result) }
    }

    suspend fun applyRemote(
        records: List<LedgerSyncRecordContract>,
        conflicts: List<LedgerSyncConflictContract>
    ) {
        records.latestByEntity().sortedWith(remoteApplyComparator).forEach { record ->
            recordApplier.apply(record)
            database.ledgerSyncDao().upsertMetadata(record.toMetadata())
        }
        conflicts.forEach { conflict ->
            if (conflict.isPristineGeneratedDefaultCandidate()) {
                val canonical = conflict.toCanonicalRecord()
                recordApplier.apply(canonical)
                database.ledgerSyncDao().upsertMetadata(canonical.toMetadata())
                database.ledgerSyncDao().deleteConflict(conflict.conflictId)
            } else {
                database.ledgerSyncDao().upsertConflict(conflict.toEntity())
                val metadata = database.ledgerSyncDao().getMetadata(conflict.entityType.name, conflict.entityId)
                database.ledgerSyncDao().upsertMetadata(
                    (metadata ?: AccountSyncMetadataEntity(
                        conflict.entityType.name,
                        conflict.entityId,
                        conflict.canonicalVersion,
                        conflict.canonicalPayload?.let {
                            LedgerSyncJsonContracts.encodePayload(conflict.entityType, it)
                        },
                        conflict.canonicalDeleted
                    )).copy(blockedByConflict = true)
                )
            }
        }
    }

    suspend fun replaceFormalData(
        records: List<LedgerSyncRecordContract>,
        resetActiveLedger: Boolean
    ) {
        val pendingEntries = database.pendingEntryDao().listPendingEntries()
        val ignoredEntries = database.ignoredEntryDao().listAll()
        database.ledgerEntryDao().deleteAll()
        database.fundingAccountDao().deleteAll()
        database.categoryDao().deleteAll()
        database.ledgerBookDao().deleteAll()
        database.categorizationRuleDao().deleteAll()
        database.ledgerSyncDao().deleteAllMetadata()
        database.ledgerSyncDao().deleteAllOutbox()
        database.ledgerSyncDao().deleteAllConflicts()
        records.latestByEntity().sortedWith(remoteApplyComparator).forEach { record ->
            recordApplier.apply(record)
            database.ledgerSyncDao().upsertMetadata(record.toMetadata())
        }
        recordApplier.ensureAtLeastOneLedgerBook()
        restoreDeviceQueueReferences(pendingEntries, ignoredEntries)
        val firstLedger = database.ledgerBookDao().getAll().first()
        database.localSettingsDao().getById()?.let { settings ->
            if (resetActiveLedger || database.ledgerBookDao().getById(settings.activeLedgerId) == null) {
                database.localSettingsDao().upsert(settings.copy(activeLedgerId = firstLedger.id))
            }
        }
    }

    suspend fun mergeSnapshot(records: List<LedgerSyncRecordContract>) {
        recordApplier.ensureFundingSyncIds()
        canonicalizeBusinessKeys(database, recordApplier, records)
        val local = currentPayloads().associateBy { it.first to it.second }
        records.latestByEntity().sortedWith(remoteApplyComparator).forEach { record ->
            val key = record.entityType to record.entityId
            val localPayload = local[key]?.third
            val localContract = localPayload?.let {
                LedgerSyncJsonContracts.parsePayload(record.entityType, it)
            }
            val remotePayload = record.payload?.let {
                LedgerSyncJsonContracts.encodePayload(record.entityType, it)
            }
            database.ledgerSyncDao().upsertMetadata(record.toMetadata())
            if (localPayload == null) {
                recordApplier.apply(record)
            } else if (localPayload != remotePayload || record.deleted) {
                recordApplier.apply(record)
                if (localContract?.isPristineGeneratedDefault() != true) {
                    enqueue(record.entityType, record.entityId, baseVersion = 0, deleted = false, payload = localPayload)
                }
            }
        }
        reconcileOutbox()
    }

    suspend fun resolved(record: LedgerSyncRecordContract, conflictId: String) {
        recordApplier.apply(record)
        database.ledgerSyncDao().upsertMetadata(record.toMetadata())
        database.ledgerSyncDao().deleteConflict(conflictId)
    }

    suspend fun reconcileOutbox() {
        val current = currentPayloads()
        val currentKeys = current.mapTo(mutableSetOf()) { it.first.name to it.second }
        current.forEach { (type, id, payload) ->
            val metadata = database.ledgerSyncDao().getMetadata(type.name, id)
            if (
                metadata?.blockedByConflict != true &&
                metadata?.syncedPayload != payload &&
                database.ledgerSyncDao().findOutbox(type.name, id) == null
            ) {
                enqueue(type, id, metadata?.serverVersion ?: 0, deleted = false, payload = payload)
            }
        }
        database.ledgerSyncDao().getAllMetadata()
            .filter { !it.deleted && !it.blockedByConflict && (it.entityType to it.entityId) !in currentKeys }
            .forEach { metadata ->
                if (database.ledgerSyncDao().findOutbox(metadata.entityType, metadata.entityId) == null) {
                    enqueue(
                        LedgerSyncEntityTypeContract.valueOf(metadata.entityType),
                        metadata.entityId,
                        metadata.serverVersion,
                        deleted = true,
                        payload = null
                    )
                }
            }
    }

    private suspend fun currentPayloads(): List<Triple<LedgerSyncEntityTypeContract, String, String>> {
        val funding = database.fundingAccountDao().getAllFundingAccounts()
        val fundingById = funding.associateBy { it.id }
        return buildList {
            val addPayload: (LedgerSyncPayloadContract) -> Unit = { payload ->
                val type = payload.entityType()
                add(Triple(type, payload.entityId(), LedgerSyncJsonContracts.encodePayload(type, payload)))
            }
            database.categoryDao().getAllCategories().forEach { entity -> addPayload(entity.toPayload()) }
            funding.forEach { entity -> addPayload(entity.toPayload()) }
            database.ledgerBookDao().getAll().forEach { entity -> addPayload(entity.toPayload()) }
            database.ledgerEntryDao().listAllLedgerEntries().forEach { entity ->
                addPayload(entity.toPayload(fundingById[entity.fundingAccountId]?.syncId))
            }
            database.categorizationRuleDao().listRules().forEach { entity -> addPayload(entity.toPayload()) }
        }
    }

    private suspend fun enqueue(
        type: LedgerSyncEntityTypeContract,
        id: String,
        baseVersion: Long,
        deleted: Boolean,
        payload: String?
    ) {
        database.ledgerSyncDao().upsertOutbox(
            AccountSyncOutboxEntity(
                mutationId = idGenerator(),
                entityType = type.name,
                entityId = id,
                baseVersion = baseVersion,
                deleted = deleted,
                payload = payload,
                createdAtMillis = clock()
            )
        )
    }

    private suspend fun restoreDeviceQueueReferences(
        pendingEntries: List<PendingEntryEntity>,
        ignoredEntries: List<IgnoredEntryEntity>
    ) {
        val validCategoryIds = database.categoryDao().getAllCategories().mapTo(mutableSetOf()) { it.id }
        val validFundingIds = database.fundingAccountDao().getAllFundingAccounts().mapTo(mutableSetOf()) { it.id }
        pendingEntries.forEach { previous ->
            database.pendingEntryDao().getById(previous.id)?.let { current ->
                database.pendingEntryDao().upsert(
                    current.copy(
                        suggestedCategoryId = previous.suggestedCategoryId?.takeIf(validCategoryIds::contains),
                        fundingAccountId = previous.fundingAccountId?.takeIf(validFundingIds::contains)
                    )
                )
            }
        }
        ignoredEntries.forEach { previous ->
            database.ignoredEntryDao().getById(previous.id)?.let { current ->
                database.ignoredEntryDao().upsert(
                    current.copy(
                        suggestedCategoryId = previous.suggestedCategoryId?.takeIf(validCategoryIds::contains),
                        fundingAccountId = previous.fundingAccountId?.takeIf(validFundingIds::contains)
                    )
                )
            }
        }
    }

}

private suspend fun applyPushResult(
    database: BksDatabase,
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

private suspend fun canonicalizeBusinessKeys(
    database: BksDatabase,
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
    database: BksDatabase,
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
    database: BksDatabase,
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
