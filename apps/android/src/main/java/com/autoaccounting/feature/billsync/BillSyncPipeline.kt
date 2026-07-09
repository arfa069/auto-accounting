package com.autoaccounting.feature.billsync

import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.feature.dedupe.DedupeEngine
import com.autoaccounting.feature.dedupe.DedupeMatchLevel
import com.autoaccounting.feature.review.ReviewQueueEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class BillSyncStep(val label: String) {
    OpenSource("打开来源"),
    ReadBills("读取账单"),
    Parse("解析账单"),
    Deduplicate("去重"),
    CreatePendingEntries("创建待确认"),
    Completed("完成"),
    Failed("同步失败"),
    Cancelled("已取消")
}

data class BillSyncResult(
    val steps: List<BillSyncStep>,
    val createdEntries: List<ReviewQueueEntry>,
    val mergedEntries: List<ReviewQueueEntry> = emptyList(),
    val duplicateSkippedCount: Int,
    val summary: String,
    val errorMessage: String? = null
)

class BillSyncPipeline(
    private val parser: BillPageParser = BillPageParser(),
    private val captureTimeFormatter: (Long) -> String = ::formatCaptureTime
) {
    fun sync(
        source: BillSyncSource,
        pageText: String,
        existingPendingEntries: List<ReviewQueueEntry>,
        capturedAtEpochMillis: Long
    ): BillSyncResult {
        val successSteps = listOf(
            BillSyncStep.OpenSource,
            BillSyncStep.ReadBills,
            BillSyncStep.Parse,
            BillSyncStep.Deduplicate,
            BillSyncStep.CreatePendingEntries,
            BillSyncStep.Completed
        )
        val parsedEntries = parser.parse(source, pageText)
        if (parsedEntries.isEmpty()) {
            return BillSyncResult(
                steps = listOf(
                    BillSyncStep.OpenSource,
                    BillSyncStep.ReadBills,
                    BillSyncStep.Parse,
                    BillSyncStep.Failed
                ),
                createdEntries = emptyList(),
                duplicateSkippedCount = 0,
                summary = "未创建待确认记录",
                errorMessage = "未识别到账单记录，请确认已打开对应账单页面"
            )
        }
        val createdEntries = mutableListOf<ReviewQueueEntry>()
        val mergedEntries = mutableListOf<ReviewQueueEntry>()
        var pendingEntries = existingPendingEntries

        parsedEntries
            .map { parsed -> parsed.toPendingEntry(capturedAtEpochMillis) }
            .forEach { candidate ->
                val dedupeResult = DedupeEngine().addCandidate(pendingEntries, candidate)
                pendingEntries = dedupeResult.pendingEntries
                when (dedupeResult.matchLevel) {
                    DedupeMatchLevel.NONE,
                    DedupeMatchLevel.LOW_CONFIDENCE -> {
                        createdEntries += dedupeResult.pendingEntries.first { it.id == candidate.id }
                    }

                    DedupeMatchLevel.HIGH_CONFIDENCE -> {
                        val matchedId = dedupeResult.matchedEntry?.id
                        dedupeResult.pendingEntries.firstOrNull { it.id == matchedId }?.let { merged ->
                            mergedEntries += merged
                        }
                    }
                }
            }
        val duplicateSkippedCount = mergedEntries.size

        return BillSyncResult(
            steps = successSteps,
            createdEntries = createdEntries,
            mergedEntries = mergedEntries,
            duplicateSkippedCount = duplicateSkippedCount,
            summary = "已创建 ${createdEntries.size} 条，已去重 $duplicateSkippedCount 条"
        )
    }

    private fun ParsedBillEntry.toPendingEntry(capturedAtEpochMillis: Long): ReviewQueueEntry =
        ReviewQueueEntry(
            id = stableId(),
            title = merchantTitle,
            amountMinor = amountMinor,
            transactionTimeText = transactionTimeText,
            category = "",
            fundingAccountLabel = fundingAccountLabel,
            sourceLabel = sourceLabel,
            kindLabel = transactionKindLabel,
            captureReasonLabel = "账单同步",
            confidence = ConfidenceState.HIGH,
            capturedAtEpochMillis = capturedAtEpochMillis,
            captureTimeText = captureTimeFormatter(capturedAtEpochMillis),
            rawEvidenceText = rawLine,
            parsedFields = parsedFields
        )

    private fun ParsedBillEntry.stableId(): String =
        "bill-${sourceLabel}-${transactionTimeText}-${amountMinor}-${rawLine.hashCode()}"
}

private fun formatCaptureTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(epochMillis))
