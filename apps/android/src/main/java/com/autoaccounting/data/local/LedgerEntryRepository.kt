package com.autoaccounting.data.local

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository interface for managing active and soft-deleted ledger entries.
 */
interface LedgerEntryRepository {
    fun ledgerEntries(ledgerBookId: String): Flow<List<LedgerEntryEntity>>
    fun deletedLedgerEntries(ledgerBookId: String): Flow<List<LedgerEntryEntity>>
    suspend fun listLedgerEntries(): List<LedgerEntryEntity>
    suspend fun getLedgerEntry(ledgerEntryId: String): LedgerEntryEntity?
    suspend fun createManualEntry(ledgerBookId: String, input: LedgerEntryInput): LedgerEntryEntity
    suspend fun updateLedgerEntry(ledgerEntryId: String, input: LedgerEntryInput): LedgerEntryEntity
    suspend fun moveLedgerEntryToDeleted(ledgerEntryId: String): LedgerEntryEntity
    suspend fun restoreDeletedLedgerEntry(ledgerEntryId: String): LedgerEntryEntity
    suspend fun permanentlyDeleteLedgerEntry(ledgerEntryId: String)
    suspend fun purgeExpiredDeletedLedgerEntries(nowEpochMillis: Long): Int
}

internal class RoomLedgerEntryRepository(
    private val database: AutoAccountingDatabase,
    private val clock: () -> Long,
    private val idGenerator: () -> String,
    private val ledgerBookRepository: RoomLedgerBookRepository,
    private val fundingAccountRepository: RoomFundingAccountRepository
) : LedgerEntryRepository {
    private val syncRecorder = LocalSyncMutationRecorder(database, clock, idGenerator)
    override fun ledgerEntries(ledgerBookId: String): Flow<List<LedgerEntryEntity>> =
        database.ledgerEntryDao().observeLedgerEntriesForBook(ledgerBookId)

    override fun deletedLedgerEntries(ledgerBookId: String): Flow<List<LedgerEntryEntity>> =
        database.ledgerEntryDao().observeDeletedLedgerEntriesForBook(ledgerBookId)

    override suspend fun listLedgerEntries(): List<LedgerEntryEntity> =
        database.ledgerEntryDao().listLedgerEntries()

    override suspend fun getLedgerEntry(ledgerEntryId: String): LedgerEntryEntity? =
        database.ledgerEntryDao().getById(ledgerEntryId)

    override suspend fun createManualEntry(
        ledgerBookId: String,
        input: LedgerEntryInput
    ): LedgerEntryEntity = database.withTransaction {
        val targetLedgerBook = ledgerBookRepository.requireForWrite(ledgerBookId)
        val validated = input.validated(clock())
        val fundingAccountId = fundingAccountRepository.resolve(validated)
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
            currency = LocalLedgerRepository.SUPPORTED_CURRENCY,
            merchantTitle = validated.merchantTitle.trim(),
            transactionTimeEpochMillis = validated.transactionTimeEpochMillis,
            categoryId = validated.categoryId ?: LocalLedgerRepository.DEFAULT_CATEGORY_ID,
            fundingAccountId = fundingAccountId,
            note = validated.note?.trim()?.ifBlank { null },
            evidenceSummary = null,
            parsedFieldsText = null,
            confirmedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            deletedAtEpochMillis = null
        )
        database.ledgerEntryDao().upsert(entry)
        syncRecorder.record(entry)
        entry
    }

    override suspend fun updateLedgerEntry(
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
            currency = LocalLedgerRepository.SUPPORTED_CURRENCY,
            merchantTitle = validated.merchantTitle.trim(),
            transactionTimeEpochMillis = validated.transactionTimeEpochMillis,
            categoryId = validated.categoryId ?: LocalLedgerRepository.DEFAULT_CATEGORY_ID,
            fundingAccountId = fundingAccountRepository.resolve(validated),
            note = validated.note?.trim()?.ifBlank { null },
            updatedAtEpochMillis = clock()
        )
        database.ledgerEntryDao().upsert(updated)
        syncRecorder.record(updated)
        updated
    }

    override suspend fun moveLedgerEntryToDeleted(ledgerEntryId: String): LedgerEntryEntity =
        database.withTransaction {
            val existing = requireNotNull(database.ledgerEntryDao().getById(ledgerEntryId)) {
                "Ledger entry not found: $ledgerEntryId"
            }
            require(existing.deletedAtEpochMillis == null) { "Ledger entry is already deleted" }
            val deletedAt = clock()
            check(database.ledgerEntryDao().moveToDeleted(ledgerEntryId, deletedAt) == 1)
            existing.copy(deletedAtEpochMillis = deletedAt).also { syncRecorder.record(it) }
        }

    override suspend fun restoreDeletedLedgerEntry(ledgerEntryId: String): LedgerEntryEntity =
        database.withTransaction {
            val existing = requireNotNull(database.ledgerEntryDao().getById(ledgerEntryId)) {
                "Ledger entry not found: $ledgerEntryId"
            }
            val deletedAt = requireNotNull(existing.deletedAtEpochMillis) {
                "Ledger entry is not deleted"
            }
            require(deletedAt > clock() - LocalLedgerRepository.DELETED_RETENTION_MILLIS) {
                "Ledger entry recovery period has expired"
            }
            check(database.ledgerEntryDao().restoreDeleted(ledgerEntryId) == 1)
            existing.copy(deletedAtEpochMillis = null).also { syncRecorder.record(it) }
        }

    override suspend fun permanentlyDeleteLedgerEntry(ledgerEntryId: String) = database.withTransaction {
        check(database.ledgerEntryDao().permanentlyDelete(ledgerEntryId) == 1) {
            "Only a deleted ledger entry can be permanently deleted"
        }
        syncRecorder.recordDelete(
            com.autoaccounting.api.LedgerSyncEntityTypeContract.LEDGER_ENTRY,
            ledgerEntryId
        )
    }

    override suspend fun purgeExpiredDeletedLedgerEntries(nowEpochMillis: Long): Int =
        database.withTransaction {
            val cutoff = nowEpochMillis - LocalLedgerRepository.DELETED_RETENTION_MILLIS
            val expired = database.ledgerEntryDao().listDeletedBefore(cutoff)
            syncRecorder.recordDeletes(
                com.autoaccounting.api.LedgerSyncEntityTypeContract.LEDGER_ENTRY,
                expired.map(LedgerEntryEntity::id)
            )
            database.ledgerEntryDao().purgeDeletedBefore(cutoff)
        }
}

private fun LedgerEntryInput.validated(now: Long): LedgerEntryInput {
    require(amountMinor > 0) { "Amount must be greater than zero" }
    require(transactionTimeEpochMillis <= now) { "Transaction time cannot be in the future" }
    return this
}
