package com.autoaccounting.data.local

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

sealed interface LedgerBookDeleteResult {
    data object Deleted : LedgerBookDeleteResult
    data object NotFound : LedgerBookDeleteResult
    data object LastLedgerBook : LedgerBookDeleteResult
    data class NotEmpty(
        val activeEntryCount: Int,
        val deletedEntryCount: Int
    ) : LedgerBookDeleteResult
}

sealed interface FundingAccountDeleteResult {
    data object Deleted : FundingAccountDeleteResult
    data object NotFound : FundingAccountDeleteResult
    data class Referenced(
        val activeLedgerEntryCount: Int,
        val deletedLedgerEntryCount: Int,
        val pendingEntryCount: Int,
        val ignoredEntryCount: Int
    ) : FundingAccountDeleteResult
}

data class LedgerRepositoryState(
    val ledgerBooks: List<LedgerBookEntryCounts> = emptyList(),
    val activeLedgerBook: LedgerBookEntity? = null,
    val ledgerEntries: List<LedgerEntryEntity> = emptyList(),
    val deletedLedgerEntries: List<LedgerEntryEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val fundingAccounts: List<FundingAccountEntity> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class LocalLedgerRepository(
    private val database: AutoAccountingDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    val pendingEntries = database.pendingEntryDao().observePendingEntries()
    // Compatibility API for backup/tests; the main UI consumes state below instead.
    val ledgerBooks = database.ledgerBookDao().observeAll()
    val activeLedgerBook = database.ledgerBookDao().observeActive()
    val ledgerEntries = activeLedgerBook.flatMapLatest { ledgerBook ->
        ledgerBook?.let { ledgerEntries(it.id) } ?: flowOf(emptyList())
    }
    val deletedLedgerEntries = activeLedgerBook.flatMapLatest { ledgerBook ->
        ledgerBook?.let { deletedLedgerEntries(it.id) } ?: flowOf(emptyList())
    }
    val fundingAccounts = database.fundingAccountDao().observeFundingAccounts()
    val categories = database.categoryDao().observeCategories()
    val state = activeLedgerBook.flatMapLatest { activeLedgerBook ->
        combine(
            database.ledgerBookDao().observeEntryCounts(),
            activeLedgerBook?.let { ledgerEntries(it.id) } ?: flowOf(emptyList()),
            activeLedgerBook?.let { deletedLedgerEntries(it.id) } ?: flowOf(emptyList()),
            categories,
            fundingAccounts
        ) { ledgerBooks, ledgerEntries, deletedLedgerEntries, categories, fundingAccounts ->
            LedgerRepositoryState(
                ledgerBooks = ledgerBooks,
                activeLedgerBook = activeLedgerBook,
                ledgerEntries = ledgerEntries,
                deletedLedgerEntries = deletedLedgerEntries,
                categories = categories,
                fundingAccounts = fundingAccounts
            )
        }
    }

    fun ledgerEntries(ledgerBookId: String): Flow<List<LedgerEntryEntity>> =
        database.ledgerEntryDao().observeLedgerEntriesForBook(ledgerBookId)

    fun deletedLedgerEntries(ledgerBookId: String): Flow<List<LedgerEntryEntity>> =
        database.ledgerEntryDao().observeDeletedLedgerEntriesForBook(ledgerBookId)

    suspend fun listLedgerEntries(): List<LedgerEntryEntity> =
        database.ledgerEntryDao().listLedgerEntries()

    suspend fun listAllLedgerEntries(): List<LedgerEntryEntity> =
        database.ledgerEntryDao().listAllLedgerEntries()

    suspend fun getLedgerEntry(id: String): LedgerEntryEntity? =
        database.ledgerEntryDao().getById(id)

    suspend fun ensureDefaultLedgerBook(): LedgerBookEntity = database.withTransaction {
        ensureLedgerBookState()
    }

    suspend fun createLedgerBook(name: String): LedgerBookEntity = database.withTransaction {
        ensureLedgerBookState()
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Ledger book name is required" }
        require(database.ledgerBookDao().findByName(normalizedName) == null) {
            "Ledger book name already exists"
        }
        val ledgerBook = LedgerBookEntity(
            id = idGenerator(),
            name = normalizedName,
            createdAtEpochMillis = clock()
        )
        database.ledgerBookDao().insert(ledgerBook)
        database.localSettingsDao().upsert(
            currentSettingsEntity(ledgerBook.id).copy(activeLedgerId = ledgerBook.id)
        )
        ledgerBook
    }

    suspend fun selectLedgerBook(ledgerBookId: String): LedgerBookEntity =
        database.withTransaction {
            val ledgerBook = requireNotNull(database.ledgerBookDao().getById(ledgerBookId)) {
                "Ledger book not found: $ledgerBookId"
            }
            database.localSettingsDao().upsert(
                currentSettingsEntity(ledgerBook.id).copy(activeLedgerId = ledgerBook.id)
            )
            ledgerBook
        }

    suspend fun deleteLedgerBook(ledgerBookId: String): LedgerBookDeleteResult =
        database.withTransaction {
            val ledgerBooks = database.ledgerBookDao().getAll()
            val target = ledgerBooks.firstOrNull { it.id == ledgerBookId }
                ?: return@withTransaction LedgerBookDeleteResult.NotFound
            if (ledgerBooks.size <= 1) {
                return@withTransaction LedgerBookDeleteResult.LastLedgerBook
            }
            val activeEntryCount =
                database.ledgerEntryDao().countActiveByLedgerBookId(target.id)
            val deletedEntryCount =
                database.ledgerEntryDao().countDeletedByLedgerBookId(target.id)
            if (activeEntryCount > 0 || deletedEntryCount > 0) {
                return@withTransaction LedgerBookDeleteResult.NotEmpty(
                    activeEntryCount = activeEntryCount,
                    deletedEntryCount = deletedEntryCount
                )
            }

            val settings = database.localSettingsDao().getById()
            val currentActiveId = ledgerBooks
                .firstOrNull { it.id == settings?.activeLedgerId }
                ?.id
                ?: ledgerBooks.first().id
            val nextActiveId = if (currentActiveId == target.id) {
                ledgerBooks.first { it.id != target.id }.id
            } else {
                currentActiveId
            }
            if (settings == null || settings.activeLedgerId != nextActiveId) {
                database.localSettingsDao().upsert(
                    currentSettingsEntity(nextActiveId).copy(activeLedgerId = nextActiveId)
                )
            }
            check(database.ledgerBookDao().deleteById(target.id) == 1)
            LedgerBookDeleteResult.Deleted
        }

    fun recoverableIgnoredEntries(nowEpochMillis: Long) =
        database.ignoredEntryDao().observeRecoverable(nowEpochMillis)

    suspend fun seedSystemCategories() {
        val defaults = DefaultCategories.systemDefaults(clock())
        database.withTransaction {
            database.categoryDao().insertIgnore(defaults)
            defaults.forEach { category ->
                database.categoryDao().updateSystemCategory(
                    id = category.id,
                    name = category.name,
                    kind = category.kind,
                    sortOrder = category.sortOrder
                )
            }
        }
    }

    suspend fun ensureFundingAccount(
        source: PaymentSource,
        label: String
    ): FundingAccountEntity = database.withTransaction {
        val normalizedLabel = label.trim()
        require(normalizedLabel.isNotEmpty()) { "Funding account label is required" }
        val sourceScope = fundingAccountScope(source)
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

    suspend fun createFundingAccount(
        label: String,
        paymentSource: PaymentSource?
    ): FundingAccountEntity = database.withTransaction {
        val normalizedLabel = normalizeFundingAccountLabel(label)
        val sourceScope = fundingAccountScope(paymentSource)
        require(database.fundingAccountDao().findByScopeAndLabel(sourceScope, normalizedLabel) == null) {
            "Funding account already exists for this payment source"
        }
        val account = FundingAccountEntity(
            sourceScope = sourceScope,
            paymentSource = paymentSource,
            label = normalizedLabel,
            createdAtEpochMillis = clock()
        )
        val id = database.fundingAccountDao().insertIgnore(account)
        require(id != -1L) { "Funding account already exists for this payment source" }
        account.copy(id = id)
    }

    suspend fun updateFundingAccount(
        fundingAccountId: Long,
        label: String,
        paymentSource: PaymentSource?
    ): FundingAccountEntity = database.withTransaction {
        val existing = requireNotNull(database.fundingAccountDao().getById(fundingAccountId)) {
            "Funding account not found: $fundingAccountId"
        }
        val normalizedLabel = normalizeFundingAccountLabel(label)
        val sourceScope = fundingAccountScope(paymentSource)
        val duplicate = database.fundingAccountDao().findByScopeAndLabel(
            sourceScope,
            normalizedLabel
        )
        require(duplicate == null || duplicate.id == existing.id) {
            "Funding account already exists for this payment source"
        }
        check(
            database.fundingAccountDao().update(
                id = existing.id,
                sourceScope = sourceScope,
                paymentSource = paymentSource,
                label = normalizedLabel
            ) == 1
        )
        existing.copy(
            sourceScope = sourceScope,
            paymentSource = paymentSource,
            label = normalizedLabel
        )
    }

    suspend fun deleteFundingAccount(
        fundingAccountId: Long
    ): FundingAccountDeleteResult = database.withTransaction {
        if (database.fundingAccountDao().getById(fundingAccountId) == null) {
            return@withTransaction FundingAccountDeleteResult.NotFound
        }
        val activeLedgerEntryCount =
            database.ledgerEntryDao().countActiveByFundingAccountId(fundingAccountId)
        val deletedLedgerEntryCount =
            database.ledgerEntryDao().countDeletedByFundingAccountId(fundingAccountId)
        val pendingEntryCount =
            database.pendingEntryDao().countByFundingAccountId(fundingAccountId)
        val ignoredEntryCount =
            database.ignoredEntryDao().countByFundingAccountId(fundingAccountId)
        if (
            activeLedgerEntryCount > 0 ||
            deletedLedgerEntryCount > 0 ||
            pendingEntryCount > 0 ||
            ignoredEntryCount > 0
        ) {
            return@withTransaction FundingAccountDeleteResult.Referenced(
                activeLedgerEntryCount = activeLedgerEntryCount,
                deletedLedgerEntryCount = deletedLedgerEntryCount,
                pendingEntryCount = pendingEntryCount,
                ignoredEntryCount = ignoredEntryCount
            )
        }
        check(database.fundingAccountDao().deleteById(fundingAccountId) == 1)
        FundingAccountDeleteResult.Deleted
    }

    suspend fun createManualEntry(input: LedgerEntryInput): LedgerEntryEntity {
        val targetLedgerBookId = ensureDefaultLedgerBook().id
        return createManualEntry(targetLedgerBookId, input)
    }

    suspend fun createManualEntry(
        ledgerBookId: String,
        input: LedgerEntryInput
    ): LedgerEntryEntity = database.withTransaction {
        val targetLedgerBook = requireLedgerBookForWrite(ledgerBookId)
        val validated = input.validated(clock())
        val fundingAccountId = resolveFundingAccount(validated)
        val now = clock()
        val entry = LedgerEntryEntity(
            id = idGenerator(),
            ledgerBookId = targetLedgerBook.id,
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
    ): LedgerEntryEntity {
        val targetLedgerBookId = ensureDefaultLedgerBook().id
        return confirmPending(
            pendingEntryId = pendingEntryId,
            ledgerBookId = targetLedgerBookId,
            categoryId = categoryId,
            note = note,
            confirmedAtEpochMillis = confirmedAtEpochMillis
        )
    }

    suspend fun confirmPending(
        pendingEntryId: String,
        ledgerBookId: String,
        categoryId: String? = null,
        note: String? = null,
        confirmedAtEpochMillis: Long = clock()
    ): LedgerEntryEntity = database.withTransaction {
        val targetLedgerBook = requireLedgerBookForWrite(ledgerBookId)
        val pending = requireNotNull(database.pendingEntryDao().getById(pendingEntryId)) {
            "Pending entry not found: $pendingEntryId"
        }
        val ledgerEntry = LedgerEntryEntity(
            id = idGenerator(),
            ledgerBookId = targetLedgerBook.id,
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
            fundingAccountId = resolvePendingFundingAccount(pending),
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
        input.fundingAccountId?.let { fundingAccountId ->
            requireNotNull(database.fundingAccountDao().getById(fundingAccountId)) {
                "Funding account not found: $fundingAccountId"
            }
            return fundingAccountId
        }
        val label = input.newFundingAccountLabel?.trim().orEmpty()
        if (label.isEmpty()) {
            return null
        }
        val scope = fundingAccountScope(input.paymentSource)
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

    private suspend fun resolvePendingFundingAccount(pending: PendingEntryEntity): Long? {
        pending.fundingAccountId?.let { fundingAccountId ->
            if (database.fundingAccountDao().getById(fundingAccountId) != null) {
                return fundingAccountId
            }
        }
        val normalizedLabel = pending.fundingAccountLabel?.trim().orEmpty()
        if (normalizedLabel.isEmpty()) {
            return null
        }
        return database.fundingAccountDao().findByScopeAndLabel(
            sourceScope = fundingAccountScope(pending.source),
            label = normalizedLabel
        )?.id
    }

    suspend fun clearLocalData() = database.withTransaction {
        database.ledgerEntryDao().deleteAll()
        database.pendingEntryDao().deleteAll()
        database.ignoredEntryDao().deleteAll()
        database.fundingAccountDao().deleteAll()
        database.categoryDao().deleteAll()
        database.ledgerBookDao().deleteAll()
        database.categorizationRuleDao().deleteAll()
        database.localSettingsDao().deleteAll()
        database.ledgerBookDao().insert(defaultLedgerBook())
        database.categoryDao().insertIgnore(DefaultCategories.systemDefaults(clock()))
        database.categorizationRuleDao().insertIgnore(DefaultCategorizationRules.rules)
        database.localSettingsDao().upsert(
            LocalSettingsEntity(
                aiConsentGranted = false,
                enhancedContextGranted = false,
                continuousBillSyncCompleted = false,
                continuousMonitoringEnabled = false,
                activeLedgerId = DEFAULT_LEDGER_BOOK_ID
            )
        )
    }

    private suspend fun ensureLedgerBookState(): LedgerBookEntity {
        val ledgerBooks = database.ledgerBookDao().getAll()
        val fallback = if (ledgerBooks.isEmpty()) {
            defaultLedgerBook().also { database.ledgerBookDao().insert(it) }
        } else {
            ledgerBooks.first()
        }
        val settings = database.localSettingsDao().getById()
        val activeLedgerBook = ledgerBooks
            .firstOrNull { it.id == settings?.activeLedgerId }
            ?: fallback
        if (settings == null || settings.activeLedgerId != activeLedgerBook.id) {
            database.localSettingsDao().upsert(
                currentSettingsEntity(activeLedgerBook.id).copy(
                    activeLedgerId = activeLedgerBook.id
                )
            )
        }
        return activeLedgerBook
    }

    private suspend fun requireLedgerBookForWrite(ledgerBookId: String): LedgerBookEntity {
        database.ledgerBookDao().getById(ledgerBookId)?.let { return it }
        if (
            ledgerBookId == DEFAULT_LEDGER_BOOK_ID &&
            database.ledgerBookDao().count() == 0
        ) {
            val defaultLedgerBook = defaultLedgerBook()
            database.ledgerBookDao().insert(defaultLedgerBook)
            database.localSettingsDao().upsert(
                currentSettingsEntity(defaultLedgerBook.id).copy(
                    activeLedgerId = defaultLedgerBook.id
                )
            )
            return defaultLedgerBook
        }
        error("Ledger book not found: $ledgerBookId")
    }

    private suspend fun currentSettingsEntity(activeLedgerId: String): LocalSettingsEntity =
        database.localSettingsDao().getById() ?: LocalSettingsEntity(
            aiConsentGranted = false,
            enhancedContextGranted = false,
            continuousBillSyncCompleted = false,
            continuousMonitoringEnabled = false,
            activeLedgerId = activeLedgerId
        )

    private fun defaultLedgerBook(): LedgerBookEntity = LedgerBookEntity(
        id = DEFAULT_LEDGER_BOOK_ID,
        name = DEFAULT_LEDGER_BOOK_NAME,
        createdAtEpochMillis = clock()
    )

    private fun normalizeFundingAccountLabel(label: String): String =
        label.trim().also {
            require(it.isNotEmpty()) { "Funding account label is required" }
        }

    private fun fundingAccountScope(
        paymentSource: PaymentSource?
    ): FundingAccountSourceScope =
        paymentSource?.toFundingAccountSourceScope() ?: FundingAccountSourceScope.USER

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
