package com.autoaccounting.feature.review

import com.autoaccounting.data.local.LocalLedgerRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class ReviewQueuePersistence(
    private val repository: LocalLedgerRepository,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
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

    suspend fun ensureSystemCategories() {
        repository.seedSystemCategories()
    }

    suspend fun persistTransition(previous: ReviewQueueState, next: ReviewQueueState) {
        val previousPendingIds = previous.pendingEntries.map { it.id }.toSet()
        val nextPendingIds = next.pendingEntries.map { it.id }.toSet()
        val previousConfirmedOriginIds = previous.confirmedEntries.map { it.originPendingId }.toSet()
        val nextConfirmedOriginIds = next.confirmedEntries.map { it.originPendingId }.toSet()
        val previousIgnoredById = previous.ignoredEntries.associateBy { it.id }
        val nextIgnoredById = next.ignoredEntries.associateBy { it.id }
        val nextIgnoredOriginalIds = next.ignoredEntries.map { it.originalPendingId }.toSet()

        next.confirmedEntries
            .filterNot { it.originPendingId in previousConfirmedOriginIds }
            .forEach { confirmed ->
                confirmThroughRepository(confirmed)
            }

        nextIgnoredById.values
            .filterNot { it.id in previousIgnoredById }
            .forEach { ignored ->
                repository.upsertIgnored(ignored.toEntity(zoneId))
                repository.deletePending(ignored.originalPendingId)
            }

        next.pendingEntries.forEach { entry ->
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
                repository.deleteLedgerByOriginPendingEntryId(pendingEntryId)
            }

        previousPendingIds
            .filterNot { it in nextPendingIds }
            .filterNot { it in nextConfirmedOriginIds }
            .filterNot { it in nextIgnoredOriginalIds }
            .forEach { pendingEntryId ->
                repository.deletePending(pendingEntryId)
            }
    }

    private suspend fun confirmThroughRepository(confirmed: ReviewQueueConfirmedEntry) {
        val result = runCatching {
            repository.confirmPending(
                pendingEntryId = confirmed.originPendingId,
                categoryId = confirmed.entry.category.toCategoryIdOrNull(
                    confirmed.entry.kindLabel.toTransactionKind()
                ),
                note = confirmed.entry.note
            )
        }
        if (result.isFailure) {
            repository.upsertPending(confirmed.entry.toEntity(zoneId))
            repository.confirmPending(
                pendingEntryId = confirmed.originPendingId,
                categoryId = confirmed.entry.category.toCategoryIdOrNull(
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
