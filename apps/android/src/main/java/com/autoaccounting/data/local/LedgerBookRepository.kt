package com.autoaccounting.data.local

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository interface for managing ledger books.
 */
interface LedgerBookRepository {
    val ledgerBooks: Flow<List<LedgerBookEntity>>
    val activeLedgerBook: Flow<LedgerBookEntity?>

    suspend fun ensureDefaultLedgerBook(): LedgerBookEntity
    suspend fun createLedgerBook(name: String): LedgerBookEntity
    suspend fun selectLedgerBook(ledgerBookId: String): LedgerBookEntity
    suspend fun deleteLedgerBook(ledgerBookId: String): LedgerBookDeleteResult
}

internal class RoomLedgerBookRepository(
    private val database: AutoAccountingDatabase,
    private val clock: () -> Long,
    private val idGenerator: () -> String
) : LedgerBookRepository {
    override val ledgerBooks = database.ledgerBookDao().observeAll()
    override val activeLedgerBook = database.ledgerBookDao().observeActive()

    override suspend fun ensureDefaultLedgerBook(): LedgerBookEntity = database.withTransaction {
        ensureLedgerBookState()
    }

    override suspend fun createLedgerBook(name: String): LedgerBookEntity = database.withTransaction {
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

    override suspend fun selectLedgerBook(ledgerBookId: String): LedgerBookEntity =
        database.withTransaction {
            val ledgerBook = requireNotNull(database.ledgerBookDao().getById(ledgerBookId)) {
                "Ledger book not found: $ledgerBookId"
            }
            database.localSettingsDao().upsert(
                currentSettingsEntity(ledgerBook.id).copy(activeLedgerId = ledgerBook.id)
            )
            ledgerBook
        }

    override suspend fun deleteLedgerBook(ledgerBookId: String): LedgerBookDeleteResult =
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

    internal suspend fun requireForWrite(ledgerBookId: String): LedgerBookEntity {
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

    internal fun defaultLedgerBook(): LedgerBookEntity = LedgerBookEntity(
        id = DEFAULT_LEDGER_BOOK_ID,
        name = DEFAULT_LEDGER_BOOK_NAME,
        createdAtEpochMillis = clock()
    )

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

    private suspend fun currentSettingsEntity(activeLedgerId: String): LocalSettingsEntity =
        database.localSettingsDao().getById() ?: LocalSettingsEntity(
            aiConsentGranted = false,
            enhancedContextGranted = false,
            continuousBillSyncCompleted = false,
            continuousMonitoringEnabled = false,
            activeLedgerId = activeLedgerId
        )
}
