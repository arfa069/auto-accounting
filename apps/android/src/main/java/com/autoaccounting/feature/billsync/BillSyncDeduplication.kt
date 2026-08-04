package com.autoaccounting.feature.billsync

import com.autoaccounting.feature.dedupe.DedupeEngine
import com.autoaccounting.feature.dedupe.DedupeMatchLevel
import com.autoaccounting.feature.review.ReviewQueueEntry

internal data class BillSyncDeduplicationResult(
    val createdEntries: List<ReviewQueueEntry>,
    val mergedEntries: List<ReviewQueueEntry>,
    val pendingEntries: List<ReviewQueueEntry>,
    val duplicateSkippedCount: Int
)

internal class BillSyncDeduplication(
    existingPendingEntries: List<ReviewQueueEntry>,
    private val existingLedgerEntries: List<ReviewQueueEntry>,
    private val existingIgnoredEntries: List<ReviewQueueEntry>,
    private val isWechatRedPacketAutomaticCapture: Boolean,
    private val isNotificationVerifiedRedPacket: Boolean
) {
    private val dedupeEngine = DedupeEngine()
    private val createdEntries = mutableListOf<ReviewQueueEntry>()
    private val mergedEntries = mutableListOf<ReviewQueueEntry>()
    private var pendingEntries = existingPendingEntries
    private var ledgerDuplicateCount = 0
    private var ignoredDuplicateCount = 0
    private var persistentRedPacketDuplicateCount = 0

    fun addCandidate(candidate: ReviewQueueEntry) {
        if (isIgnoredDuplicate(candidate)) return
        if (isPersistentRedPacketDuplicate(candidate)) return

        val candidateAfterLedgerCheck = resolveLedgerDuplicate(candidate) ?: return
        addToPending(candidateAfterLedgerCheck)
    }

    fun result(): BillSyncDeduplicationResult {
        val duplicateSkippedCount =
            mergedEntries.size + ledgerDuplicateCount + ignoredDuplicateCount +
                persistentRedPacketDuplicateCount
        return BillSyncDeduplicationResult(
            createdEntries = createdEntries,
            mergedEntries = mergedEntries,
            pendingEntries = pendingEntries,
            duplicateSkippedCount = duplicateSkippedCount
        )
    }

    private fun isIgnoredDuplicate(candidate: ReviewQueueEntry): Boolean {
        val dedupeResult = dedupeEngine.addCandidate(existingIgnoredEntries, candidate)
        if (dedupeResult.matchLevel != DedupeMatchLevel.HIGH_CONFIDENCE) {
            return false
        }
        ignoredDuplicateCount += 1
        return true
    }

    private fun isPersistentRedPacketDuplicate(candidate: ReviewQueueEntry): Boolean {
        if (!isWechatRedPacketAutomaticCapture || isNotificationVerifiedRedPacket) {
            return false
        }
        if (
            pendingEntries.any {
                it.hasAutomaticOcrCaptureEvidence &&
                    it.hasSameStableIdentityAs(candidate)
            }
        ) {
            persistentRedPacketDuplicateCount += 1
            return true
        }
        if (existingLedgerEntries.any { it.hasSameStableIdentityAs(candidate) }) {
            persistentRedPacketDuplicateCount += 1
            return true
        }
        return false
    }

    private fun resolveLedgerDuplicate(candidate: ReviewQueueEntry): ReviewQueueEntry? {
        val ledgerEntriesForDedupe = if (isNotificationVerifiedRedPacket) {
            existingLedgerEntries.filterNot { it.hasSameStableIdentityAs(candidate) }
        } else {
            existingLedgerEntries
        }
        val ledgerDedupeResult = dedupeEngine.addCandidate(
            ledgerEntriesForDedupe,
            candidate
        )
        return when (ledgerDedupeResult.matchLevel) {
            DedupeMatchLevel.HIGH_CONFIDENCE -> {
                ledgerDuplicateCount += 1
                null
            }

            DedupeMatchLevel.LOW_CONFIDENCE ->
                ledgerDedupeResult.pendingEntries.first { it.id == candidate.id }

            DedupeMatchLevel.NONE -> candidate
        }
    }

    private fun addToPending(candidate: ReviewQueueEntry) {
        val excludedPriorOcrEntries = if (isNotificationVerifiedRedPacket) {
            pendingEntries.filter {
                it.hasAutomaticOcrCaptureEvidence &&
                    it.hasSameStableIdentityAs(candidate)
            }
        } else {
            emptyList()
        }
        val pendingEntriesForDedupe = if (excludedPriorOcrEntries.isEmpty()) {
            pendingEntries
        } else {
            pendingEntries.filterNot { entry ->
                excludedPriorOcrEntries.any { excluded -> excluded.id == entry.id }
            }
        }
        val dedupeResult = dedupeEngine.addCandidate(
            pendingEntriesForDedupe,
            candidate
        )
        pendingEntries = dedupeResult.pendingEntries + excludedPriorOcrEntries
        when (dedupeResult.matchLevel) {
            DedupeMatchLevel.NONE,
            DedupeMatchLevel.LOW_CONFIDENCE -> {
                createdEntries += dedupeResult.pendingEntries.first {
                    it.id == candidate.id
                }
            }

            DedupeMatchLevel.HIGH_CONFIDENCE -> {
                val matchedId = dedupeResult.matchedEntry?.id
                dedupeResult.pendingEntries.firstOrNull { it.id == matchedId }?.let { merged ->
                    mergedEntries += merged
                }
            }
        }
    }
}

private fun ReviewQueueEntry.hasSameStableIdentityAs(other: ReviewQueueEntry): Boolean =
    sourceLabel == other.sourceLabel &&
        title.trim().equals(other.title.trim(), ignoreCase = true) &&
        amountMinor == other.amountMinor &&
        kindLabel == other.kindLabel
