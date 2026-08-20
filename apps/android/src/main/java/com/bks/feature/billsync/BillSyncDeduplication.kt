package com.bks.feature.billsync

import com.bks.feature.dedupe.DedupeEngine
import com.bks.feature.dedupe.DedupeMatchLevel
import com.bks.feature.review.ReviewQueueEntry

internal data class BillSyncDeduplicationResult(
    val createdEntries: List<ReviewQueueEntry>,
    val mergedEntries: List<ReviewQueueEntry>,
    val pendingEntries: List<ReviewQueueEntry>,
    val duplicateSkippedCount: Int
)

internal class BillSyncDeduplication(
    existingPendingEntries: List<ReviewQueueEntry>,
    private val existingLedgerEntries: List<ReviewQueueEntry>,
    private val existingIgnoredEntries: List<ReviewQueueEntry>
) {
    private val dedupeEngine = DedupeEngine()
    private val createdEntries = mutableListOf<ReviewQueueEntry>()
    private val mergedEntries = mutableListOf<ReviewQueueEntry>()
    private var pendingEntries = existingPendingEntries
    private var duplicateSkippedCount = 0

    fun addCandidate(candidate: ReviewQueueEntry) {
        if (dedupeEngine.addCandidate(existingIgnoredEntries, candidate).matchLevel == DedupeMatchLevel.HIGH_CONFIDENCE) {
            duplicateSkippedCount += 1
            return
        }
        val ledgerResult = dedupeEngine.addCandidate(existingLedgerEntries, candidate)
        if (ledgerResult.matchLevel == DedupeMatchLevel.HIGH_CONFIDENCE) {
            duplicateSkippedCount += 1
            return
        }
        val pendingResult = dedupeEngine.addCandidate(pendingEntries, candidate)
        pendingEntries = pendingResult.pendingEntries
        when (pendingResult.matchLevel) {
            DedupeMatchLevel.NONE,
            DedupeMatchLevel.LOW_CONFIDENCE -> {
                createdEntries += pendingResult.pendingEntries.first { it.id == candidate.id }
            }
            DedupeMatchLevel.HIGH_CONFIDENCE -> {
                duplicateSkippedCount += 1
                pendingResult.matchedEntry?.let { mergedEntries += it }
            }
        }
    }

    fun result(): BillSyncDeduplicationResult = BillSyncDeduplicationResult(
        createdEntries = createdEntries,
        mergedEntries = mergedEntries,
        pendingEntries = pendingEntries,
        duplicateSkippedCount = duplicateSkippedCount
    )
}
