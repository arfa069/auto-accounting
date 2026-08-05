package com.autoaccounting.feature.sync

import com.autoaccounting.api.LedgerSyncConflictContract
import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncJsonContracts
import com.autoaccounting.api.LedgerSyncMutationResultContract
import com.autoaccounting.api.LedgerSyncPayloadContract
import com.autoaccounting.api.LedgerSyncRecordContract
import com.autoaccounting.data.local.AccountSyncMetadataEntity
import com.autoaccounting.data.local.AccountSyncOutboxEntity
import com.autoaccounting.data.local.AutoAccountingDatabase
import com.autoaccounting.data.local.FundingAccountSourceScope
import com.autoaccounting.data.local.IgnoredEntryEntity
import com.autoaccounting.data.local.PendingEntryEntity

internal class LedgerSyncSnapshotOperations(
    private val database: AutoAccountingDatabase,
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
