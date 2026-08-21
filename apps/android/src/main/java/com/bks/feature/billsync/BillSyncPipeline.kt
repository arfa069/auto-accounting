package com.bks.feature.billsync

import com.bks.data.local.ConfidenceState
import com.bks.feature.review.ReviewQueueEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BillSyncResult(
    val recognized: Boolean,
    val createdEntries: List<ReviewQueueEntry>,
    val mergedEntries: List<ReviewQueueEntry> = emptyList(),
    val duplicateSkippedCount: Int = 0
)

class BillSyncPipeline(
    private val parser: BillPageParser = BillPageParser(),
    private val captureTimeFormatter: (Long) -> String = ::formatCaptureTime
) {
    fun sync(
        pageText: String,
        existingPendingEntries: List<ReviewQueueEntry>,
        existingLedgerEntries: List<ReviewQueueEntry> = emptyList(),
        existingIgnoredEntries: List<ReviewQueueEntry> = emptyList(),
        capturedAtEpochMillis: Long
    ): BillSyncResult {
        val parsedEntries = parser.parse(pageText, captureTimeFormatter(capturedAtEpochMillis))
        if (parsedEntries.isEmpty()) {
            return BillSyncResult(recognized = false, createdEntries = emptyList())
        }

        val deduplication = BillSyncDeduplication(
            existingPendingEntries = existingPendingEntries,
            existingLedgerEntries = existingLedgerEntries,
            existingIgnoredEntries = existingIgnoredEntries
        )
        parsedEntries.map { it.toPendingEntry(capturedAtEpochMillis) }
            .forEach(deduplication::addCandidate)
        val result = deduplication.result()
        return BillSyncResult(
            recognized = true,
            createdEntries = result.createdEntries,
            mergedEntries = result.mergedEntries,
            duplicateSkippedCount = result.duplicateSkippedCount
        )
    }

    private fun ParsedBillEntry.toPendingEntry(capturedAtEpochMillis: Long): ReviewQueueEntry =
        ReviewQueueEntry(
            id = stableId(),
            title = merchantTitle,
            amountMinor = amountMinor,
            transactionTimeText = transactionTimeText,
            category = "",
            fundingAccountLabel = "",
            sourceLabel = GENERIC_PAYMENT_SOURCE_LABEL,
            kindLabel = transactionKindLabel,
            captureReasonLabel = ACCESSIBILITY_AUTO_CAPTURE_REASON_LABEL,
            confidence = ConfidenceState.NEEDS_REVIEW,
            capturedAtEpochMillis = capturedAtEpochMillis,
            captureTimeText = captureTimeFormatter(capturedAtEpochMillis),
            note = "商户未识别，请人工确认".takeIf { merchantTitleFromFallback },
            rawEvidenceText = "",
            parsedFields = parsedFields
        )

    private fun ParsedBillEntry.stableId(): String =
        "bill-${transactionTimeText}-${amountMinor}-${transactionKindLabel}-${merchantTitle}".hashCode()
            .let { "bill-$it" }
}

private fun formatCaptureTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(epochMillis))
