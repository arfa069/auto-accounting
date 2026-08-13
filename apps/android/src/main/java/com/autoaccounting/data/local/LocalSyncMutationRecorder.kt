package com.autoaccounting.data.local

import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncJsonContracts
import com.autoaccounting.api.LedgerSyncPayloadContract
import java.util.UUID

@Suppress("TooManyFunctions")
internal class LocalSyncMutationRecorder(
    private val database: AutoAccountingDatabase,
    private val clock: () -> Long,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    suspend fun reconcileAll() {
        if (database.ledgerSyncDao().getState()?.enabled != true) return
        val fundingAccounts = database.fundingAccountDao().getAllFundingAccounts()
        fundingAccounts.filter { it.syncId == null }
            .forEach { account -> check(database.fundingAccountDao().setSyncId(account.id, idGenerator()) == 1) }
        val currentFundingAccounts = if (fundingAccounts.any { it.syncId == null }) {
            database.fundingAccountDao().getAllFundingAccounts()
        } else {
            fundingAccounts
        }
        val fundingSyncIdsById = currentFundingAccounts.associate { it.id to requireNotNull(it.syncId) }
        val currentKeys = mutableSetOf<Pair<String, String>>()
        database.categoryDao().getAllCategories().forEach {
            record(it)
            currentKeys += LedgerSyncEntityTypeContract.CATEGORY.name to it.id
        }
        currentFundingAccounts.forEach {
            record(it)
            currentKeys += LedgerSyncEntityTypeContract.FUNDING_ACCOUNT.name to requireNotNull(it.syncId)
        }
        database.ledgerBookDao().getAll().forEach {
            record(it)
            currentKeys += LedgerSyncEntityTypeContract.LEDGER_BOOK.name to it.id
        }
        database.ledgerEntryDao().listAllLedgerEntries().forEach {
            record(it, fundingSyncIdsById)
            currentKeys += LedgerSyncEntityTypeContract.LEDGER_ENTRY.name to it.id
        }
        database.categorizationRuleDao().listRules().forEach {
            record(it)
            currentKeys += LedgerSyncEntityTypeContract.CATEGORIZATION_RULE.name to it.id
        }
        database.ledgerSyncDao().getAllMetadata()
            .filter { !it.deleted && !it.blockedByConflict && (it.entityType to it.entityId) !in currentKeys }
            .groupBy { LedgerSyncEntityTypeContract.valueOf(it.entityType) }
            .forEach { (type, metadata) -> recordDeletes(type, metadata.map(AccountSyncMetadataEntity::entityId)) }
    }

    suspend fun record(category: CategoryEntity) = recordPayload(
        LedgerSyncEntityTypeContract.CATEGORY,
        category.id,
        LedgerSyncPayloadContract.Category(
            category.id, category.name, category.kind?.name, category.sortOrder,
            category.isSystem, category.createdAtEpochMillis
        )
    )

    suspend fun record(account: FundingAccountEntity) = recordPayload(
        LedgerSyncEntityTypeContract.FUNDING_ACCOUNT,
        requireNotNull(account.syncId),
        LedgerSyncPayloadContract.FundingAccount(
            requireNotNull(account.syncId), account.sourceScope.name, account.paymentSource?.name,
            account.label, account.createdAtEpochMillis
        )
    )

    suspend fun record(book: LedgerBookEntity) = recordPayload(
        LedgerSyncEntityTypeContract.LEDGER_BOOK,
        book.id,
        LedgerSyncPayloadContract.LedgerBook(book.id, book.name, book.createdAtEpochMillis)
    )

    suspend fun record(entry: LedgerEntryEntity) {
        record(entry, null)
    }

    private suspend fun record(
        entry: LedgerEntryEntity,
        fundingSyncIdsById: Map<Long, String>?
    ) {
        val fundingSyncId = entry.fundingAccountId?.let { fundingAccountId ->
            fundingSyncIdsById?.get(fundingAccountId)
                ?: database.fundingAccountDao().getById(fundingAccountId)?.syncId
        }
        recordPayload(
            LedgerSyncEntityTypeContract.LEDGER_ENTRY,
            entry.id,
            LedgerSyncPayloadContract.LedgerEntry(
                entry.id, entry.ledgerBookId, entry.paymentSource?.name,
                entry.originalCaptureSource?.name, entry.entryOrigin.name,
                entry.flowDirection.name, entry.transactionKind.name, entry.amountMinor,
                entry.currency, entry.merchantTitle, entry.transactionTimeEpochMillis,
                entry.categoryId, fundingSyncId, entry.note, entry.confirmedAtEpochMillis,
                entry.updatedAtEpochMillis, entry.deletedAtEpochMillis
            )
        )
    }

    suspend fun record(rule: CategorizationRuleEntity) = recordPayload(
        LedgerSyncEntityTypeContract.CATEGORIZATION_RULE,
        rule.id,
        LedgerSyncPayloadContract.CategorizationRule(
            rule.id, rule.merchantContains, rule.titleContains, rule.sourceLabel,
            rule.transactionKind, rule.category, rule.priority, rule.enabled,
            rule.updatedAtEpochMillis
        )
    )

    suspend fun recordDelete(type: LedgerSyncEntityTypeContract, entityId: String) {
        record(type, entityId, deleted = true, payload = null)
    }

    suspend fun recordDeletes(type: LedgerSyncEntityTypeContract, entityIds: List<String>) {
        if (entityIds.isEmpty() || database.ledgerSyncDao().getState()?.enabled != true) return
        entityIds.forEach { entityId ->
            record(type, entityId, deleted = true, payload = null, skipEnabledCheck = true)
        }
    }

    private suspend fun recordPayload(
        type: LedgerSyncEntityTypeContract,
        entityId: String,
        payload: LedgerSyncPayloadContract
    ) {
        record(
            type,
            entityId,
            deleted = false,
            payload = LedgerSyncJsonContracts.encodePayload(type, payload)
        )
    }

    private suspend fun record(
        type: LedgerSyncEntityTypeContract,
        entityId: String,
        deleted: Boolean,
        payload: String?,
        skipEnabledCheck: Boolean = false
    ) {
        if (!skipEnabledCheck && database.ledgerSyncDao().getState()?.enabled != true) return
        val existing = database.ledgerSyncDao().findOutbox(type.name, entityId)
        if (existing != null) {
            database.ledgerSyncDao().upsertOutbox(existing.copy(deleted = deleted, payload = payload))
            return
        }
        val metadata = database.ledgerSyncDao().getMetadata(type.name, entityId)
        if (metadata?.blockedByConflict == true) return
        if (
            metadata != null &&
            metadata.deleted == deleted &&
            metadata.syncedPayload == payload
        ) {
            return
        }
        database.ledgerSyncDao().upsertOutbox(
            AccountSyncOutboxEntity(
                mutationId = idGenerator(),
                entityType = type.name,
                entityId = entityId,
                baseVersion = metadata?.serverVersion ?: 0,
                deleted = deleted,
                payload = payload,
                createdAtMillis = clock()
            )
        )
    }
}
