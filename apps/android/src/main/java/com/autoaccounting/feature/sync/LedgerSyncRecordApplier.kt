package com.autoaccounting.feature.sync

import androidx.room.withTransaction
import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncPayloadContract
import com.autoaccounting.api.LedgerSyncRecordContract
import com.autoaccounting.data.local.AutoAccountingDatabase
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.DefaultCategories
import com.autoaccounting.data.local.EntryOrigin
import com.autoaccounting.data.local.FundingAccountSourceScope
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.TransactionKind

internal class LedgerSyncRecordApplier(
    private val database: AutoAccountingDatabase,
    private val clock: () -> Long,
    private val idGenerator: () -> String
) {
    suspend fun ensureFundingSyncIds() {
        database.fundingAccountDao().getAllFundingAccounts()
            .filter { it.syncId == null }
            .forEach { account ->
                check(database.fundingAccountDao().setSyncId(account.id, idGenerator()) == 1)
            }
    }

    suspend fun remapCategoryIdentity(
        local: CategoryEntity,
        canonicalId: String,
        canonicalName: String
    ) {
        val temporaryName = uniqueName("${canonicalName}（本机重映射）") {
            database.categoryDao().findByName(it) != null
        }
        check(database.categoryDao().updateName(local.id, temporaryName) == 1)
        database.categoryDao().upsert(local.copy(id = canonicalId, name = canonicalName))
        database.categoryDao().remapLedgerEntries(local.id, canonicalId)
        database.categoryDao().remapPendingEntries(local.id, canonicalId)
        database.categoryDao().remapIgnoredEntries(local.id, canonicalId)
        database.categoryDao().deleteById(local.id)
    }

    suspend fun apply(record: LedgerSyncRecordContract) {
        if (record.deleted) {
            when (record.entityType) {
                LedgerSyncEntityTypeContract.LEDGER_ENTRY -> database.ledgerEntryDao().deleteById(record.entityId)
                LedgerSyncEntityTypeContract.CATEGORIZATION_RULE ->
                    database.categorizationRuleDao().deleteById(record.entityId)
                LedgerSyncEntityTypeContract.LEDGER_BOOK -> database.ledgerBookDao().deleteById(record.entityId)
                LedgerSyncEntityTypeContract.FUNDING_ACCOUNT ->
                    database.fundingAccountDao().findBySyncId(record.entityId)?.let {
                        database.fundingAccountDao().deleteById(it.id)
                    }
                LedgerSyncEntityTypeContract.CATEGORY -> database.categoryDao().deleteById(record.entityId)
            }
            ensureAtLeastOneLedgerBook()
            return
        }
        when (val payload = requireNotNull(record.payload)) {
            is LedgerSyncPayloadContract.Category -> applyCategory(payload)
            is LedgerSyncPayloadContract.FundingAccount -> applyFundingAccount(payload)
            is LedgerSyncPayloadContract.LedgerBook -> applyLedgerBook(payload)
            is LedgerSyncPayloadContract.LedgerEntry -> applyLedgerEntry(payload)
            is LedgerSyncPayloadContract.CategorizationRule ->
                database.categorizationRuleDao().upsert(payload.toEntity())
        }
    }

    suspend fun ensureAtLeastOneLedgerBook() {
        if (database.ledgerBookDao().count() > 0) return
        database.ledgerBookDao().insert(
            com.autoaccounting.data.local.LedgerBookEntity("default-ledger", "默认账本", clock())
        )
    }

    private suspend fun applyCategory(payload: LedgerSyncPayloadContract.Category) {
        val existing = database.categoryDao().getCategory(payload.id)
        val collision = database.categoryDao().findByName(payload.name)
        val name = if (collision != null && collision.id != payload.id) {
            uniqueName(payload.name) { database.categoryDao().findByName(it) != null }
        } else {
            payload.name
        }
        if (existing == null) {
            database.categoryDao().upsert(payload.toEntity(name))
        } else {
            check(
                database.categoryDao().updateSynced(
                    id = payload.id,
                    name = name,
                    kind = payload.kind?.let(TransactionKind::valueOf),
                    sortOrder = payload.sortOrder,
                    isSystem = payload.isSystem,
                    createdAtEpochMillis = payload.createdAtMillis
                ) == 1
            )
        }
    }

    private suspend fun applyFundingAccount(payload: LedgerSyncPayloadContract.FundingAccount) {
        val existing = database.fundingAccountDao().findBySyncId(payload.syncId)
        val sourceScope = FundingAccountSourceScope.valueOf(payload.sourceScope)
        val collision = database.fundingAccountDao().findByScopeAndLabel(sourceScope, payload.label)
        val label = if (collision != null && collision.syncId != payload.syncId) {
            uniqueName("${payload.label}（云端）") {
                database.fundingAccountDao().findByScopeAndLabel(sourceScope, it) != null
            }
        } else {
            payload.label
        }
        if (existing == null) {
            check(database.fundingAccountDao().insertIgnore(payload.toEntity(label)) != -1L)
        } else {
            check(
                database.fundingAccountDao().updateSynced(
                    id = existing.id,
                    syncId = payload.syncId,
                    sourceScope = sourceScope,
                    paymentSource = payload.paymentSource?.let(PaymentSource::valueOf),
                    label = label,
                    createdAtEpochMillis = payload.createdAtMillis
                ) == 1
            )
        }
    }

    private suspend fun applyLedgerBook(payload: LedgerSyncPayloadContract.LedgerBook) {
        val collision = database.ledgerBookDao().findByName(payload.name)
        if (collision != null && collision.id != payload.id) {
            check(
                database.ledgerBookDao().updateSynced(
                    id = collision.id,
                    name = uniqueName("${collision.name}（本机）") {
                        database.ledgerBookDao().findByName(it) != null
                    },
                    createdAtEpochMillis = collision.createdAtEpochMillis
                ) == 1
            )
        }
        val existing = database.ledgerBookDao().getById(payload.id)
        if (existing == null) {
            database.ledgerBookDao().insert(payload.toEntity())
        } else {
            check(
                database.ledgerBookDao().updateSynced(
                    id = payload.id,
                    name = payload.name,
                    createdAtEpochMillis = payload.createdAtMillis
                ) == 1
            )
        }
    }

    private suspend fun applyLedgerEntry(payload: LedgerSyncPayloadContract.LedgerEntry) {
        if (database.ledgerBookDao().getById(payload.ledgerBookId) == null) return
        val existing = database.ledgerEntryDao().getById(payload.id)
        val fundingId = payload.fundingAccountSyncId?.let {
            database.fundingAccountDao().findBySyncId(it)?.id
        }
        database.ledgerEntryDao().upsert(payload.toEntity(existing, fundingId))
    }

    private suspend fun uniqueName(
        base: String,
        exists: suspend (String) -> Boolean
    ): String {
        if (!exists(base)) return base
        var index = 2
        while (exists("$base $index")) index++
        return "$base $index"
    }
}
