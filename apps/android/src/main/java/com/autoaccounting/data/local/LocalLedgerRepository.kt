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
    val deletedLedgerEntries = database.ledgerEntryDao().observeDeletedLedgerEntries()
    val fundingAccounts = database.fundingAccountDao().observeFundingAccounts()
    val categories = database.categoryDao().observeCategories()

    suspend fun listLedgerEntries(): List<LedgerEntryEntity> =
        database.ledgerEntryDao().listLedgerEntries()

    suspend fun listAllLedgerEntries(): List<LedgerEntryEntity> =
        database.ledgerEntryDao().listAllLedgerEntries()

    suspend fun getLedgerEntry(id: String): LedgerEntryEntity? =
        database.ledgerEntryDao().getById(id)

    fun recoverableIgnoredEntries(nowEpochMillis: Long) =
        database.ignoredEntryDao().observeRecoverable(nowEpochMillis)

    suspend fun seedSystemCategories() {
        database.categoryDao().insertIgnore(DefaultCategories.systemDefaults(clock()))
    }

    suspend fun ensureFundingAccount(
        source: PaymentSource,
        label: String
    ): FundingAccountEntity = database.withTransaction {
        val normalizedLabel = label.trim()
        require(normalizedLabel.isNotEmpty()) { "Funding account label is required" }
        val sourceScope = source.toFundingAccountSourceScope()
        val existing = database.fundingAccountDao().findByScopeAndLabel(sourceScope, normalizedLabel)
        if (existing != null) {
            return@withTransaction existing
        }

        val newAccount = FundingAccountEntity(
            sourceScope = sourceScope,
            paymentSource = source,
            label = normalizedLabel,
            createdAtEpochMillis = clock()
        )
        val id = database.fundingAccountDao().insertIgnore(newAccount)
        if (id == -1L) {
            requireNotNull(database.fundingAccountDao().findByScopeAndLabel(sourceScope, normalizedLabel))
        } else {
            newAccount.copy(id = id)
        }
    }

    suspend fun createManualEntry(input: LedgerEntryInput): LedgerEntryEntity = database.withTransaction {
        val validated = input.validated(clock())
        val fundingAccountId = resolveFundingAccount(validated)
        val now = clock()
        val entry = LedgerEntryEntity(
            id = idGenerator(),
            paymentSource = validated.paymentSource,
            originalCaptureSource = null,
            entryOrigin = EntryOrigin.MANUAL,
            originPendingEntryId = null,
            flowDirection = validated.flowDirection,
            transactionKind = validated.transactionKind,
            amountMinor = validated.amountMinor,
            currency = SUPPORTED_CURRENCY,
            merchantTitle = validated.merchantTitle.trim(),
            transactionTimeEpochMillis = validated.transactionTimeEpochMillis,
            categoryId = validated.categoryId ?: DEFAULT_CATEGORY_ID,
            fundingAccountId = fundingAccountId,
            note = validated.note?.trim()?.ifBlank { null },
            evidenceSummary = null,
            parsedFieldsText = null,
            confirmedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            deletedAtEpochMillis = null
        )
        database.ledgerEntryDao().upsert(entry)
        entry
    }

    suspend fun updateLedgerEntry(
        ledgerEntryId: String,
        input: LedgerEntryInput
    ): LedgerEntryEntity = database.withTransaction {
        val existing = requireNotNull(database.ledgerEntryDao().getById(ledgerEntryId)) {
            "Ledger entry not found: $ledgerEntryId"
        }
        require(existing.deletedAtEpochMillis == null) { "Deleted ledger entry cannot be edited" }
        val validated = input.validated(clock())
        val updated = existing.copy(
            paymentSource = validated.paymentSource,
            flowDirection = validated.flowDirection,
            transactionKind = validated.transactionKind,
            amountMinor = validated.amountMinor,
            currency = SUPPORTED_CURRENCY,
            merchantTitle = validated.merchantTitle.trim(),
            transactionTimeEpochMillis = validated.transactionTimeEpochMillis,
            categoryId = validated.categoryId ?: DEFAULT_CATEGORY_ID,
            fundingAccountId = resolveFundingAccount(validated),
            note = validated.note?.trim()?.ifBlank { null },
            updatedAtEpochMillis = clock()
        )
        database.ledgerEntryDao().upsert(updated)
        updated
    }

    suspend fun moveLedgerEntryToDeleted(ledgerEntryId: String): LedgerEntryEntity =
        database.withTransaction {
            val existing = requireNotNull(database.ledgerEntryDao().getById(ledgerEntryId)) {
                "Ledger entry not found: $ledgerEntryId"
            }
            require(existing.deletedAtEpochMillis == null) { "Ledger entry is already deleted" }
            val deletedAt = clock()
            check(database.ledgerEntryDao().moveToDeleted(ledgerEntryId, deletedAt) == 1)
            existing.copy(deletedAtEpochMillis = deletedAt)
        }

    suspend fun restoreDeletedLedgerEntry(ledgerEntryId: String): LedgerEntryEntity =
        database.withTransaction {
            val existing = requireNotNull(database.ledgerEntryDao().getById(ledgerEntryId)) {
                "Ledger entry not found: $ledgerEntryId"
            }
            val deletedAt = requireNotNull(existing.deletedAtEpochMillis) { "Ledger entry is not deleted" }
            require(deletedAt > clock() - DELETED_RETENTION_MILLIS) {
                "Ledger entry recovery period has expired"
            }
            check(database.ledgerEntryDao().restoreDeleted(ledgerEntryId) == 1)
            existing.copy(deletedAtEpochMillis = null)
        }

    suspend fun permanentlyDeleteLedgerEntry(ledgerEntryId: String) {
        check(database.ledgerEntryDao().permanentlyDelete(ledgerEntryId) == 1) {
            "Only a deleted ledger entry can be permanently deleted"
        }
    }

    suspend fun purgeExpiredDeletedLedgerEntries(nowEpochMillis: Long = clock()): Int =
        database.ledgerEntryDao().purgeDeletedBefore(
            nowEpochMillis - DELETED_RETENTION_MILLIS
        )

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
            paymentSource = pending.source,
            originalCaptureSource = pending.source,
            entryOrigin = pending.captureReason.toEntryOrigin(),
            originPendingEntryId = pending.id,
            flowDirection = pending.transactionKind.defaultFlowDirection(),
            transactionKind = pending.transactionKind,
            amountMinor = pending.amountMinor,
            currency = pending.currency,
            merchantTitle = pending.merchantTitle,
            transactionTimeEpochMillis = pending.transactionTimeEpochMillis,
            categoryId = categoryId ?: pending.suggestedCategoryId,
            fundingAccountId = pending.fundingAccountId,
            note = note ?: pending.note,
            evidenceSummary = pending.evidenceSummary,
            parsedFieldsText = pending.parsedFieldsText,
            confirmedAtEpochMillis = confirmedAtEpochMillis,
            updatedAtEpochMillis = confirmedAtEpochMillis,
            deletedAtEpochMillis = null
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

    private suspend fun resolveFundingAccount(input: LedgerEntryInput): Long? {
        require(input.fundingAccountId == null || input.newFundingAccountLabel.isNullOrBlank()) {
            "Choose an existing funding account or create a new one, not both"
        }
        input.fundingAccountId?.let { return it }
        val label = input.newFundingAccountLabel?.trim().orEmpty()
        if (label.isEmpty()) {
            return null
        }
        val scope = input.paymentSource?.toFundingAccountSourceScope() ?: FundingAccountSourceScope.USER
        val existing = database.fundingAccountDao().findByScopeAndLabel(scope, label)
        if (existing != null) {
            return existing.id
        }
        val account = FundingAccountEntity(
            sourceScope = scope,
            paymentSource = input.paymentSource,
            label = label,
            createdAtEpochMillis = clock()
        )
        val id = database.fundingAccountDao().insertIgnore(account)
        return if (id == -1L) {
            requireNotNull(database.fundingAccountDao().findByScopeAndLabel(scope, label)).id
        } else {
            id
        }
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
        const val DELETED_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L
        const val SUPPORTED_CURRENCY = "CNY"
        const val DEFAULT_CATEGORY_ID = "uncategorized"
    }
}

data class LedgerEntryInput(
    val flowDirection: FlowDirection,
    val transactionKind: TransactionKind,
    val amountMinor: Long,
    val transactionTimeEpochMillis: Long,
    val merchantTitle: String,
    val categoryId: String?,
    val fundingAccountId: Long?,
    val newFundingAccountLabel: String?,
    val note: String?,
    val paymentSource: PaymentSource?
)

private fun LedgerEntryInput.validated(now: Long): LedgerEntryInput {
    require(amountMinor > 0) { "Amount must be greater than zero" }
    require(transactionTimeEpochMillis <= now) { "Transaction time cannot be in the future" }
    return this
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
