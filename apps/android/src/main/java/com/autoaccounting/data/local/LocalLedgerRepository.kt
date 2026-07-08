package com.autoaccounting.data.local

import androidx.room.withTransaction
import java.util.UUID

class LocalLedgerRepository(
    private val database: AutoAccountingDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    val pendingEntries = database.pendingEntryDao().observePendingEntries()
    val ledgerEntries = database.ledgerEntryDao().observeLedgerEntries()

    fun recoverableIgnoredEntries(nowEpochMillis: Long) =
        database.ignoredEntryDao().observeRecoverable(nowEpochMillis)

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

    suspend fun deletePending(pendingEntryId: String) {
        database.pendingEntryDao().deleteById(pendingEntryId)
    }

    suspend fun upsertIgnored(entry: IgnoredEntryEntity) {
        database.ignoredEntryDao().upsert(entry)
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
            captureReason = pending.captureReason,
            confidence = pending.confidence,
            transactionKind = pending.transactionKind,
            amountMinor = pending.amountMinor,
            currency = pending.currency,
            merchantTitle = pending.merchantTitle,
            transactionTimeEpochMillis = pending.transactionTimeEpochMillis,
            capturedAtEpochMillis = pending.capturedAtEpochMillis,
            suggestedCategoryId = pending.suggestedCategoryId,
            fundingAccountId = pending.fundingAccountId,
            fundingAccountLabel = pending.fundingAccountLabel,
            note = pending.note,
            evidenceSummary = pending.evidenceSummary,
            parsedFieldsText = pending.parsedFieldsText,
            ignoredAtEpochMillis = ignoredAtEpochMillis,
            expiresAtEpochMillis = ignoredAtEpochMillis + IGNORED_RETENTION_MILLIS,
            reason = reason,
            suggestedCategoryLabel = pending.suggestedCategoryLabel
        )

        database.ignoredEntryDao().upsert(ignoredEntry)
        database.pendingEntryDao().deleteById(pendingEntryId)
        ignoredEntry
    }

    suspend fun recoverIgnored(ignoredEntryId: String): PendingEntryEntity = database.withTransaction {
        val ignored = requireNotNull(database.ignoredEntryDao().getById(ignoredEntryId)) {
            "Ignored entry not found: $ignoredEntryId"
        }
        val restored = ignored.toPendingEntry()
        database.pendingEntryDao().upsert(restored)
        database.ignoredEntryDao().deleteById(ignoredEntryId)
        restored
    }

    suspend fun deleteIgnored(ignoredEntryId: String) {
        database.ignoredEntryDao().deleteById(ignoredEntryId)
    }

    suspend fun deleteLedgerByOriginPendingEntryId(pendingEntryId: String) {
        database.ledgerEntryDao().deleteByOriginPendingEntryId(pendingEntryId)
    }

    suspend fun clearLocalData() = database.withTransaction {
        database.ledgerEntryDao().deleteAll()
        database.pendingEntryDao().deleteAll()
        database.ignoredEntryDao().deleteAll()
        database.fundingAccountDao().deleteAll()
        database.categoryDao().deleteAll()
        database.categoryDao().insertIgnore(DefaultCategories.systemDefaults(clock()))
    }

    companion object {
        const val IGNORED_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L
    }
}

private fun IgnoredEntryEntity.toPendingEntry(): PendingEntryEntity = PendingEntryEntity(
    id = originalPendingEntryId,
    source = source,
    captureReason = captureReason,
    confidence = confidence,
    transactionKind = transactionKind,
    amountMinor = amountMinor,
    currency = currency,
    merchantTitle = merchantTitle,
    transactionTimeEpochMillis = transactionTimeEpochMillis,
    capturedAtEpochMillis = capturedAtEpochMillis,
    suggestedCategoryId = suggestedCategoryId,
    fundingAccountId = fundingAccountId,
    fundingAccountLabel = fundingAccountLabel,
    note = note,
    evidenceSummary = evidenceSummary,
    parsedFieldsText = parsedFieldsText,
    suggestedCategoryLabel = suggestedCategoryLabel
)
