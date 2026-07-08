package com.autoaccounting.data.local

import androidx.room.withTransaction
import java.util.UUID

class LocalLedgerRepository(
    private val database: AutoAccountingDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    val pendingEntries = database.pendingEntryDao().observePendingEntries()

    suspend fun seedSystemCategories() {
        database.categoryDao().insertIgnore(DefaultCategories.systemDefaults(clock()))
    }

    suspend fun ensureFundingAccount(
        source: PaymentSource,
        label: String
    ): FundingAccountEntity = database.withTransaction {
        val existing = database.fundingAccountDao().findBySourceAndLabel(source, label)
        if (existing != null) {
            return@withTransaction existing
        }

        val newAccount = FundingAccountEntity(
            source = source,
            label = label,
            createdAtEpochMillis = clock()
        )
        val id = database.fundingAccountDao().insertIgnore(newAccount)
        if (id == -1L) {
            requireNotNull(database.fundingAccountDao().findBySourceAndLabel(source, label))
        } else {
            newAccount.copy(id = id)
        }
    }

    suspend fun upsertPending(entry: PendingEntryEntity) {
        database.pendingEntryDao().upsert(entry)
    }

    suspend fun confirmPending(
        pendingEntryId: String,
        categoryId: String? = null,
        note: String? = null,
        confirmedAtEpochMillis: Long = clock()
    ): LedgerEntryEntity = database.withTransaction {
        val pending = requireNotNull(database.pendingEntryDao().getById(pendingEntryId)) {
            "Pending entry not found: $pendingEntryId"
        }
        val ledgerEntry = LedgerEntryEntity(
            id = idGenerator(),
            source = pending.source,
            originPendingEntryId = pending.id,
            transactionKind = pending.transactionKind,
            amountMinor = pending.amountMinor,
            currency = pending.currency,
            merchantTitle = pending.merchantTitle,
            transactionTimeEpochMillis = pending.transactionTimeEpochMillis,
            categoryId = categoryId ?: pending.suggestedCategoryId,
            fundingAccountId = pending.fundingAccountId,
            note = note ?: pending.note,
            confirmedAtEpochMillis = confirmedAtEpochMillis
        )

        database.ledgerEntryDao().upsert(ledgerEntry)
        database.pendingEntryDao().deleteById(pendingEntryId)
        ledgerEntry
    }

    suspend fun ignorePending(
        pendingEntryId: String,
        reason: IgnoreReason = IgnoreReason.USER_IGNORED,
        ignoredAtEpochMillis: Long = clock()
    ): IgnoredEntryEntity = database.withTransaction {
        val pending = requireNotNull(database.pendingEntryDao().getById(pendingEntryId)) {
            "Pending entry not found: $pendingEntryId"
        }
        val ignoredEntry = IgnoredEntryEntity(
            id = idGenerator(),
            originalPendingEntryId = pending.id,
            source = pending.source,
            transactionKind = pending.transactionKind,
            amountMinor = pending.amountMinor,
            currency = pending.currency,
            merchantTitle = pending.merchantTitle,
            transactionTimeEpochMillis = pending.transactionTimeEpochMillis,
            suggestedCategoryId = pending.suggestedCategoryId,
            fundingAccountId = pending.fundingAccountId,
            ignoredAtEpochMillis = ignoredAtEpochMillis,
            expiresAtEpochMillis = ignoredAtEpochMillis + IGNORED_RETENTION_MILLIS,
            reason = reason
        )

        database.ignoredEntryDao().upsert(ignoredEntry)
        database.pendingEntryDao().deleteById(pendingEntryId)
        ignoredEntry
    }

    companion object {
        const val IGNORED_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L
    }
}
