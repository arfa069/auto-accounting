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

    private fun ReviewQueueEntry.isHighConfidenceDuplicateOf(other: ReviewQueueEntry): Boolean {
        if (
            sourceLabel != other.sourceLabel ||
            amountMinor != other.amountMinor ||
            kindLabel != other.kindLabel
        ) {
            return false
        }

        val exactTitleMatch = normalizedTitle == other.normalizedTitle
        if (exactTitleMatch && transactionTimeText == other.transactionTimeText) {
            return true
        }

        val isCrossCaptureMatch = captureReasonLabel != other.captureReasonLabel &&
            transactionTimeText.minutesFrom(other.transactionTimeText)
                ?.let { it <= CROSS_CAPTURE_WINDOW_MINUTES } == true
        return isCrossCaptureMatch &&
            (exactTitleMatch || hasGenericTitle || other.hasGenericTitle)
    }

    private fun ReviewQueueEntry.isLowConfidenceDuplicateOf(other: ReviewQueueEntry): Boolean =
        sourceLabel == other.sourceLabel &&
            amountMinor == other.amountMinor &&
            kindLabel == other.kindLabel &&
            transactionTimeText.minutesFrom(other.transactionTimeText)?.let { it <= LOW_CONFIDENCE_WINDOW_MINUTES } == true

    private fun ReviewQueueEntry.mergeWith(candidate: ReviewQueueEntry): ReviewQueueEntry {
        val preferCandidateDetails = hasGenericTitle && !candidate.hasGenericTitle
        return copy(
            title = candidate.title.takeIf { preferCandidateDetails } ?: title,
            confidence = ConfidenceState.HIGH,
            captureReasonLabel = "重复合并",
            categoryId = categoryId ?: candidate.categoryId,
            category = if (preferCandidateDetails) {
                candidate.category.ifBlank { category }
            } else {
                category.ifBlank { candidate.category }
            },
            fundingAccountLabel = fundingAccountLabel.ifBlank { candidate.fundingAccountLabel },
            note = note ?: candidate.note,
            rawEvidenceText = listOf(rawEvidenceText, candidate.rawEvidenceText)
                .filter { it.isNotBlank() }
                .joinToString(separator = "\n---\n"),
            parsedFields = (
                parsedFields + candidate.parsedFields +
                    listOf(
                        "证据来源=$captureReasonLabel",
                        "证据来源=${candidate.captureReasonLabel}"
                    ) +
                    if (normalizedTitle == candidate.normalizedTitle) {
                        "匹配原因=来源、金额、时间、类型、标题一致"
                    } else {
                        "匹配原因=来源、金额、时间、类型一致且一方标题为通用占位"
                    }
                )
                .distinct()
        )
    }

    private fun ReviewQueueEntry.markDuplicateSuspect(match: ReviewQueueEntry): ReviewQueueEntry =
        copy(
            confidence = ConfidenceState.DUPLICATE_SUSPECT,
            parsedFields = (parsedFields + "疑似重复=${match.title}").distinct()
        )

    private val ReviewQueueEntry.normalizedTitle: String
        get() = title.trim().lowercase()

    private val ReviewQueueEntry.hasGenericTitle: Boolean
        get() = normalizedTitle in GENERIC_TITLES

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
        const val CROSS_CAPTURE_WINDOW_MINUTES = 2
        val GENERIC_TITLES = setOf("未知来源", "微信支付", "支付宝支付")
        val transactionTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
