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
        existingLedgerEntries: List<ReviewQueueEntry> = emptyList(),
        capturedAtEpochMillis: Long,
        captureReasonLabel: String = "账单同步"
    ): BillSyncResult {
        val successSteps = listOf(
            BillSyncStep.OpenSource,
            BillSyncStep.ReadBills,
            BillSyncStep.Parse,
            BillSyncStep.Deduplicate,
            BillSyncStep.CreatePendingEntries,
            BillSyncStep.Completed
        )
        val parsedEntries = parser.parse(
            source = source,
            pageText = pageText,
            fallbackTransactionTimeText = captureTimeFormatter(capturedAtEpochMillis)
        )
        if (parsedEntries.isEmpty()) {
            val errorMessage = when (observeBillSyncPage(source, pageText)) {
                BillSyncPageObservation.PaymentResult ->
                    "识别到支付结果页，但缺少明确金额或交易类型，未创建待确认记录"
                BillSyncPageObservation.BlockedPaymentInitiation ->
                    "当前页面像是付款或转账发起页，出于安全保护未采集；请打开账单、交易详情或支付信息页面"
                BillSyncPageObservation.PaymentRecord ->
                    "识别到支付记录页面，但缺少金额、时间、类型或对象，请打开完整交易详情页"
                BillSyncPageObservation.Ignored ->
                    "未识别到账单记录，请确认已打开对应账单页面"
            }
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
                errorMessage = errorMessage
            )
        }
        val createdEntries = mutableListOf<ReviewQueueEntry>()
        val mergedEntries = mutableListOf<ReviewQueueEntry>()
        var pendingEntries = existingPendingEntries
        var ledgerDuplicateCount = 0

        parsedEntries
            .map { parsed ->
                parsed.toPendingEntry(
                    capturedAtEpochMillis = capturedAtEpochMillis,
                    captureReasonLabel = captureReasonLabel
                )
            }
            .forEach { candidate ->
                val ledgerDedupeResult = DedupeEngine().addCandidate(
                    existingLedgerEntries,
                    candidate
                )
                if (ledgerDedupeResult.matchLevel == DedupeMatchLevel.HIGH_CONFIDENCE) {
                    ledgerDuplicateCount += 1
                    return@forEach
                }
                val candidateAfterLedgerCheck = if (
                    ledgerDedupeResult.matchLevel == DedupeMatchLevel.LOW_CONFIDENCE
                ) {
                    ledgerDedupeResult.pendingEntries.first { it.id == candidate.id }
                } else {
                    candidate
                }
                val dedupeResult = DedupeEngine().addCandidate(
                    pendingEntries,
                    candidateAfterLedgerCheck
                )
                pendingEntries = dedupeResult.pendingEntries
                when (dedupeResult.matchLevel) {
                    DedupeMatchLevel.NONE,
                    DedupeMatchLevel.LOW_CONFIDENCE -> {
                        createdEntries += dedupeResult.pendingEntries.first {
                            it.id == candidateAfterLedgerCheck.id
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
        val duplicateSkippedCount = mergedEntries.size + ledgerDuplicateCount

        return BillSyncResult(
            steps = successSteps,
            createdEntries = createdEntries,
            mergedEntries = mergedEntries,
            duplicateSkippedCount = duplicateSkippedCount,
            summary = "已创建 ${createdEntries.size} 条，已去重 $duplicateSkippedCount 条"
        )
    }

    private fun ParsedBillEntry.toPendingEntry(
        capturedAtEpochMillis: Long,
        captureReasonLabel: String
    ): ReviewQueueEntry =
        ReviewQueueEntry(
            id = stableId(),
            title = merchantTitle,
            amountMinor = amountMinor,
            transactionTimeText = transactionTimeText,
            category = "",
            fundingAccountLabel = fundingAccountLabel,
            sourceLabel = sourceLabel,
            kindLabel = transactionKindLabel,
            captureReasonLabel = captureReasonLabel,
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
