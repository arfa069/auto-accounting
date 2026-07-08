package com.autoaccounting.feature.dedupe

import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.feature.review.ReviewQueueEntry
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class DedupeMatchLevel {
    NONE,
    LOW_CONFIDENCE,
    HIGH_CONFIDENCE
}

data class DedupeResult(
    val pendingEntries: List<ReviewQueueEntry>,
    val matchLevel: DedupeMatchLevel,
    val matchedEntry: ReviewQueueEntry? = null
)

class DedupeEngine {
    fun addCandidate(
        existingPendingEntries: List<ReviewQueueEntry>,
        candidate: ReviewQueueEntry
    ): DedupeResult {
        val highConfidenceMatch = existingPendingEntries.firstOrNull {
            it.isHighConfidenceDuplicateOf(candidate)
        }
        if (highConfidenceMatch != null) {
            val merged = highConfidenceMatch.mergeWith(candidate)
            return DedupeResult(
                pendingEntries = existingPendingEntries.map { entry ->
                    if (entry.id == highConfidenceMatch.id) merged else entry
                },
                matchLevel = DedupeMatchLevel.HIGH_CONFIDENCE,
                matchedEntry = highConfidenceMatch
            )
        }

        val lowConfidenceMatch = existingPendingEntries.firstOrNull {
            it.isLowConfidenceDuplicateOf(candidate)
        }
        if (lowConfidenceMatch != null) {
            return DedupeResult(
                pendingEntries = listOf(candidate.markDuplicateSuspect(lowConfidenceMatch)) +
                    existingPendingEntries,
                matchLevel = DedupeMatchLevel.LOW_CONFIDENCE,
                matchedEntry = lowConfidenceMatch
            )
        }

        return DedupeResult(
            pendingEntries = listOf(candidate) + existingPendingEntries,
            matchLevel = DedupeMatchLevel.NONE
        )
    }

    private fun ReviewQueueEntry.isHighConfidenceDuplicateOf(other: ReviewQueueEntry): Boolean =
        sourceLabel == other.sourceLabel &&
            amountMinor == other.amountMinor &&
            kindLabel == other.kindLabel &&
            transactionTimeText == other.transactionTimeText &&
            normalizedTitle == other.normalizedTitle

    private fun ReviewQueueEntry.isLowConfidenceDuplicateOf(other: ReviewQueueEntry): Boolean =
        sourceLabel == other.sourceLabel &&
            amountMinor == other.amountMinor &&
            kindLabel == other.kindLabel &&
            transactionTimeText.minutesFrom(other.transactionTimeText)?.let { it <= LOW_CONFIDENCE_WINDOW_MINUTES } == true

    private fun ReviewQueueEntry.mergeWith(candidate: ReviewQueueEntry): ReviewQueueEntry =
        copy(
            confidence = ConfidenceState.HIGH,
            captureReasonLabel = "重复合并",
            category = category.ifBlank { candidate.category },
            fundingAccountLabel = fundingAccountLabel.ifBlank { candidate.fundingAccountLabel },
            note = "已合并${captureReasonLabel}和${candidate.captureReasonLabel}证据",
            rawEvidenceText = listOf(rawEvidenceText, candidate.rawEvidenceText)
                .filter { it.isNotBlank() }
                .joinToString(separator = "\n---\n"),
            parsedFields = (parsedFields + candidate.parsedFields + "匹配原因=来源、金额、时间、类型、标题一致")
                .distinct()
        )

    private fun ReviewQueueEntry.markDuplicateSuspect(match: ReviewQueueEntry): ReviewQueueEntry =
        copy(
            confidence = ConfidenceState.DUPLICATE_SUSPECT,
            note = "可能与 ${match.title} 重复，请确认后再入账",
            parsedFields = (parsedFields + "疑似重复=${match.title}").distinct()
        )

    private val ReviewQueueEntry.normalizedTitle: String
        get() = title.trim().lowercase()

    private fun String.minutesFrom(other: String): Long? {
        val first = parseTransactionTime(this) ?: return null
        val second = parseTransactionTime(other) ?: return null
        return kotlin.math.abs(ChronoUnit.MINUTES.between(first, second))
    }

    private fun parseTransactionTime(text: String): LocalDateTime? = runCatching {
        LocalDateTime.parse(text.trim(), transactionTimeFormatter)
    }.getOrNull()

    private companion object {
        const val LOW_CONFIDENCE_WINDOW_MINUTES = 10
        val transactionTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
