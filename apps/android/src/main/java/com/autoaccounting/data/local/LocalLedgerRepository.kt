package com.autoaccounting.data.local

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    val fundingAccounts: List<FundingAccountEntity> = emptyList(),
    val defaultFundingAccountSyncId: String? = null
)

private data class LedgerRepositoryComponents(
    val ledgerBooks: RoomLedgerBookRepository,
    val ledgerEntries: RoomLedgerEntryRepository,
    val fundingAccounts: RoomFundingAccountRepository
)

private fun createLedgerRepositoryComponents(
    database: AutoAccountingDatabase,
    clock: () -> Long,
    idGenerator: () -> String
): LedgerRepositoryComponents {
    val ledgerBooks = RoomLedgerBookRepository(database, clock, idGenerator)
    val fundingAccounts = RoomFundingAccountRepository(database, clock, idGenerator)
    return LedgerRepositoryComponents(
        ledgerBooks = ledgerBooks,
        ledgerEntries = RoomLedgerEntryRepository(
            database = database,
            clock = clock,
            idGenerator = idGenerator,
            ledgerBookRepository = ledgerBooks,
            fundingAccountRepository = fundingAccounts
        ),
        fundingAccounts = fundingAccounts
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class LocalLedgerRepository private constructor(
    private val database: AutoAccountingDatabase,
    private val clock: () -> Long,
    private val idGenerator: () -> String,
    private val components: LedgerRepositoryComponents
) : LedgerBookRepository by components.ledgerBooks,
    LedgerEntryRepository by components.ledgerEntries,
    FundingAccountRepository by components.fundingAccounts {
    constructor(
        database: AutoAccountingDatabase,
        clock: () -> Long = { System.currentTimeMillis() },
        idGenerator: () -> String = { UUID.randomUUID().toString() }
    ) : this(database, clock, idGenerator, createLedgerRepositoryComponents(database, clock, idGenerator))

    val pendingEntries = database.pendingEntryDao().observePendingEntries()
    // Compatibility API for backup/tests; the main UI consumes state below instead.
    val ledgerEntries = activeLedgerBook.flatMapLatest { ledgerBook ->
        ledgerBook?.let { ledgerEntries(it.id) } ?: flowOf(emptyList())
    }
    val deletedLedgerEntries = activeLedgerBook.flatMapLatest { ledgerBook ->
        ledgerBook?.let { deletedLedgerEntries(it.id) } ?: flowOf(emptyList())
    }
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
        }.combine(database.localSettingsDao().observeById()) { state, settings ->
            state.copy(defaultFundingAccountSyncId = settings?.defaultFundingAccountSyncId)
        }
    }

    fun recoverableIgnoredEntries(nowEpochMillis: Long) =
        database.ignoredEntryDao().observeRecoverable(nowEpochMillis)

    suspend fun seedSystemCategories() {
        val defaults = DefaultCategories.systemDefaults(clock())
        database.withTransaction {
            val recorder = LocalSyncMutationRecorder(database, clock, idGenerator)
            database.categoryDao().insertIgnore(defaults)
            defaults.forEach { category ->
                database.categoryDao().updateSystemCategory(
                    id = category.id,
                    name = category.name,
                    kind = category.kind,
                    sortOrder = category.sortOrder
                )
                database.categoryDao().getCategory(category.id)?.let { recorder.record(it) }
            }
        }
    }

    suspend fun createManualEntry(input: LedgerEntryInput): LedgerEntryEntity {
        val targetLedgerBookId = ensureDefaultLedgerBook().id
        return createManualEntry(targetLedgerBookId, input)
    }

    suspend fun listAllLedgerEntries(): List<LedgerEntryEntity> =
        database.ledgerEntryDao().listAllLedgerEntries()

    suspend fun listLedgerEntriesBetween(
        startEpochMillis: Long,
        endEpochMillis: Long
    ): List<LedgerEntryEntity> =
        database.ledgerEntryDao().listLedgerEntriesBetween(startEpochMillis, endEpochMillis)

    suspend fun purgeExpiredDeletedLedgerEntries(): Int =
        purgeExpiredDeletedLedgerEntries(clock())

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
        val targetLedgerBook = components.ledgerBooks.requireForWrite(ledgerBookId)
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
            fundingAccountId = components.fundingAccounts.resolvePending(pending),
            note = note ?: pending.note,
            evidenceSummary = pending.evidenceSummary,
            parsedFieldsText = pending.parsedFieldsText,
            confirmedAtEpochMillis = confirmedAtEpochMillis,
            updatedAtEpochMillis = confirmedAtEpochMillis,
            deletedAtEpochMillis = null
        )

        database.ledgerEntryDao().upsert(ledgerEntry)
        database.pendingEntryDao().deleteById(pendingEntryId)
        LocalSyncMutationRecorder(database, clock, idGenerator).record(ledgerEntry)
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

    suspend fun moveLedgerEntryToDeletedByOriginPendingEntryId(pendingEntryId: String) {
        val ledgerEntryId = database.ledgerEntryDao().findIdByOriginPendingEntryId(pendingEntryId)
            ?: return
        components.ledgerEntries.moveLedgerEntryToDeleted(ledgerEntryId)
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
        database.ledgerBookDao().deleteAll()
        database.categorizationRuleDao().deleteAll()
        database.localSettingsDao().deleteAll()
        database.defaultFundingAccountCacheDao().deleteAll()
        database.ledgerBookDao().insert(components.ledgerBooks.defaultLedgerBook())
        database.categoryDao().insertIgnore(DefaultCategories.systemDefaults(clock()))
        database.categorizationRuleDao().insertIgnore(DefaultCategorizationRules.rules)
        database.localSettingsDao().upsert(
            LocalSettingsEntity(
                aiConsentGranted = false,
                enhancedContextGranted = false,
                activeLedgerId = DEFAULT_LEDGER_BOOK_ID
            )
        )
        database.ledgerSyncDao().upsertState(AccountSyncStateEntity())
        database.ledgerSyncDao().deleteAllMetadata()
        database.ledgerSyncDao().deleteAllOutbox()
        database.ledgerSyncDao().deleteAllConflicts()
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
