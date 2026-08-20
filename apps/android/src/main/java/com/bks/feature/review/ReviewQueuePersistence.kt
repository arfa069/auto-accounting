package com.bks.feature.review

import com.bks.data.local.DEFAULT_LEDGER_BOOK_ID
import com.bks.data.local.LocalLedgerRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DEDUPE_WINDOW_MILLIS = 10 * 60_000L

class ReviewQueuePersistence(
    private val repository: LocalLedgerRepository,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    private val transitionMutex = Mutex()

    fun observeState(): Flow<ReviewQueueState> {
        val nowEpochMillis = nowProvider()
        return combine(
            repository.pendingEntries,
            repository.recoverableIgnoredEntries(nowEpochMillis)
        ) { pendingEntries, ignoredEntries ->
            ReviewQueueState(
                pendingEntries = pendingEntries.map { it.toReviewEntry(zoneId) },
                ignoredEntries = ignoredEntries.map { it.toReviewIgnoredEntry(zoneId) },
                nowEpochMillis = nowEpochMillis,
                todayStartEpochMillis = todayStartEpochMillis(
                    nowEpochMillis = nowEpochMillis,
                    zoneId = zoneId
                )
            )
        }
    }

    suspend fun ledgerEntriesForDedupe(): List<ReviewQueueEntry> =
        repository.listLedgerEntries().map { it.toReviewEntryForDedupe(zoneId) }

    suspend fun ledgerEntriesForDedupe(transactionEpochMillis: Long): List<ReviewQueueEntry> =
        repository.listLedgerEntriesBetween(
            startEpochMillis = transactionEpochMillis - DEDUPE_WINDOW_MILLIS,
            endEpochMillis = transactionEpochMillis + DEDUPE_WINDOW_MILLIS
        ).map { it.toReviewEntryForDedupe(zoneId) }

    suspend fun ensureSystemCategories() {
        repository.seedSystemCategories()
    }

    suspend fun persistTransition(
        previous: ReviewQueueState,
        next: ReviewQueueState,
        targetLedgerBookId: String = DEFAULT_LEDGER_BOOK_ID
    ): Unit = transitionMutex.withLock {
        // Re-read Room while holding the lock so stale UI snapshots cannot erase capture changes.
        val persistedState = observeState().first()
        val previousPendingById = persistedState.pendingEntries.associateBy { it.id }
        val requestedPreviousPendingById = previous.pendingEntries.associateBy { it.id }
        val previousPendingIds = previousPendingById.keys
        val persistedPendingIds = previousPendingById.keys
        val nextPendingIds = next.pendingEntries.map { it.id }.toSet() +
            (persistedPendingIds - previous.pendingEntries.map { it.id }.toSet())
        val previousConfirmedOriginIds = repository.listLedgerEntries()
            .mapNotNull { it.originPendingEntryId }
            .toSet()
        val persistedConfirmedOriginIds = previousConfirmedOriginIds
        val nextConfirmedOriginIds = next.confirmedEntries.map { it.originPendingId }.toSet() +
            (persistedConfirmedOriginIds - previous.confirmedEntries.map { it.originPendingId }.toSet())
        val previousIgnoredById = persistedState.ignoredEntries.associateBy { it.id }
        val nextIgnoredById = next.ignoredEntries.associateBy { it.id } +
            persistedState.ignoredEntries
                .filter { it.id !in previous.ignoredEntries.map { ignored -> ignored.id } }
                .associateBy { it.id }

        next.confirmedEntries
            .filterNot { it.originPendingId in previousConfirmedOriginIds }
            .forEach { confirmed ->
                confirmThroughRepository(confirmed, targetLedgerBookId)
            }

        nextIgnoredById.values
            .filterNot { it.id in previousIgnoredById }
            .forEach { ignored ->
                repository.upsertIgnored(ignored.toEntity(zoneId))
            }

        next.pendingEntries
            .filter { entry -> requestedPreviousPendingById[entry.id] != entry }
            .forEach { entry ->
                repository.upsertPending(entry.toEntity(zoneId))
            }

        previousIgnoredById.values
            .filterNot { it.id in nextIgnoredById }
            .forEach { ignored ->
                val recovered = runCatching { repository.recoverIgnored(ignored.id) }.isSuccess
                if (!recovered) {
                    repository.deleteIgnored(ignored.id)
                    repository.upsertPending(ignored.entry.toEntity(zoneId))
                }
            }

        previousConfirmedOriginIds
            .filterNot { it in nextConfirmedOriginIds }
            .forEach { pendingEntryId ->
                repository.moveLedgerEntryToDeletedByOriginPendingEntryId(pendingEntryId)
            }

        previousPendingIds
            .filterNot { it in nextPendingIds }
            .filterNot { it in nextConfirmedOriginIds }
            .forEach { pendingEntryId ->
                repository.deletePending(pendingEntryId)
            }
    }

    private suspend fun confirmThroughRepository(
        confirmed: ReviewQueueConfirmedEntry,
        targetLedgerBookId: String
    ) {
        repository.upsertPending(confirmed.entry.toEntity(zoneId))
        val result = runCatching {
            repository.confirmPending(
                pendingEntryId = confirmed.originPendingId,
                ledgerBookId = targetLedgerBookId,
                categoryId = confirmed.entry.categoryId
                    ?: confirmed.entry.category.toCategoryIdOrNull(
                        confirmed.entry.kindLabel.toTransactionKind()
                    ),
                note = confirmed.entry.note
            )
        }
        if (result.isFailure) {
            repository.upsertPending(confirmed.entry.toEntity(zoneId))
            repository.confirmPending(
                pendingEntryId = confirmed.originPendingId,
                ledgerBookId = targetLedgerBookId,
                categoryId = confirmed.entry.categoryId
                    ?: confirmed.entry.category.toCategoryIdOrNull(
                        confirmed.entry.kindLabel.toTransactionKind()
                    ),
                note = confirmed.entry.note
            )
        }
    }
}

private fun todayStartEpochMillis(
    nowEpochMillis: Long,
    zoneId: ZoneId
): Long = LocalDate.from(
    Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId)
)
    .atStartOfDay(zoneId)
    .toInstant()
    .toEpochMilli()
