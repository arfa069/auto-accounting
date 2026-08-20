package com.autoaccounting.feature.billsync

import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.feature.review.ReviewQueueEntry
import com.autoaccounting.feature.review.mergeReviewEvidenceText
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
    val errorMessage: String? = null,
    val failureReason: BillSyncFailureReason? = null
)

enum class BillSyncFailureReason {
    PaymentResultMissingRequiredFields,
    PaymentInitiationBlocked,
    PaymentRecordMissingRequiredFields,
    UnsupportedOrUnrelatedPage
}

internal const val MANUAL_OCR_CAPTURE_REASON = "本机 OCR 补录"

private data class BillSyncInvocation(
    val source: BillSyncSource,
    val pageText: String,
    val existingPendingEntries: List<ReviewQueueEntry>,
    val existingLedgerEntries: List<ReviewQueueEntry>,
    val existingIgnoredEntries: List<ReviewQueueEntry>,
    val capturedAtEpochMillis: Long,
    val captureReasonLabel: String,
    val retainRawEvidence: Boolean,
    val rawEvidenceText: String?
)

class BillSyncPipeline(
    private val parser: BillPageParser = BillPageParser(),
    private val captureTimeFormatter: (Long) -> String = ::formatCaptureTime
) {
    @Suppress("LongParameterList")
    fun sync(
        source: BillSyncSource,
        pageText: String,
        existingPendingEntries: List<ReviewQueueEntry>,
        existingLedgerEntries: List<ReviewQueueEntry> = emptyList(),
        existingIgnoredEntries: List<ReviewQueueEntry> = emptyList(),
        capturedAtEpochMillis: Long,
        captureReasonLabel: String = "补录账单",
        retainRawEvidence: Boolean = true,
        rawEvidenceText: String? = null
    ): BillSyncResult = sync(
        BillSyncInvocation(
            source = source,
            pageText = pageText,
            existingPendingEntries = existingPendingEntries,
            existingLedgerEntries = existingLedgerEntries,
            existingIgnoredEntries = existingIgnoredEntries,
            capturedAtEpochMillis = capturedAtEpochMillis,
            captureReasonLabel = captureReasonLabel,
            retainRawEvidence = retainRawEvidence,
            rawEvidenceText = rawEvidenceText
        )
    )

    private fun sync(invocation: BillSyncInvocation): BillSyncResult {
        val successSteps = listOf(
            BillSyncStep.OpenSource,
            BillSyncStep.ReadBills,
            BillSyncStep.Parse,
            BillSyncStep.Deduplicate,
            BillSyncStep.CreatePendingEntries,
            BillSyncStep.Completed
        )
        val parsedEntries = parser.parse(
            source = invocation.source,
            pageText = invocation.pageText,
            fallbackTransactionTimeText =
                captureTimeFormatter(invocation.capturedAtEpochMillis)
        )
        if (parsedEntries.isEmpty()) return invocation.failureResult()

        val deduplication = BillSyncDeduplication(
            existingPendingEntries = invocation.existingPendingEntries,
            existingLedgerEntries = invocation.existingLedgerEntries,
            existingIgnoredEntries = invocation.existingIgnoredEntries
        )
        parsedEntries
            .map { parsed ->
                parsed.toPendingEntry(
                    capturedAtEpochMillis = invocation.capturedAtEpochMillis,
                    captureReasonLabel = invocation.captureReasonLabel,
                    retainRawEvidence = invocation.retainRawEvidence,
                    rawEvidenceText = invocation.rawEvidenceText
                )
            }
            .forEach(deduplication::addCandidate)
        val result = deduplication.result()

        return BillSyncResult(
            steps = successSteps,
            createdEntries = result.createdEntries,
            mergedEntries = result.mergedEntries,
            duplicateSkippedCount = result.duplicateSkippedCount,
            summary = "已创建 ${result.createdEntries.size} 条，已去重 ${result.duplicateSkippedCount} 条"
        )
    }

    private fun BillSyncInvocation.failureResult(): BillSyncResult {
        val (failureReason, errorMessage) = when (observeBillSyncPage(source, pageText)) {
            BillSyncPageObservation.PaymentResult ->
                BillSyncFailureReason.PaymentResultMissingRequiredFields to
                    "识别到支付结果页，但缺少明确金额或交易类型，未创建待确认记录"
            BillSyncPageObservation.BlockedPaymentInitiation ->
                BillSyncFailureReason.PaymentInitiationBlocked to
                    "当前页面像是付款或转账发起页，出于安全保护未采集；请打开账单、交易详情或支付信息页面"
            BillSyncPageObservation.PaymentRecord ->
                BillSyncFailureReason.PaymentRecordMissingRequiredFields to
                    "识别到支付记录页面，但缺少金额、时间、类型或对象，请打开完整交易详情页"
            BillSyncPageObservation.Ignored ->
                BillSyncFailureReason.UnsupportedOrUnrelatedPage to
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
            errorMessage = errorMessage,
            failureReason = failureReason
        )
    }

    private fun ParsedBillEntry.toPendingEntry(
        capturedAtEpochMillis: Long,
        captureReasonLabel: String,
        retainRawEvidence: Boolean,
        rawEvidenceText: String?
    ): ReviewQueueEntry = ReviewQueueEntry(
        id = stableId(),
        title = merchantTitle,
        amountMinor = amountMinor,
        transactionTimeText = transactionTimeText,
        category = "",
        fundingAccountLabel = fundingAccountLabel,
        sourceLabel = sourceLabel,
        kindLabel = transactionKindLabel,
        captureReasonLabel = captureReasonLabel,
        confidence = if (
            captureReasonLabel == MANUAL_OCR_CAPTURE_REASON && merchantTitleFromFallback
        ) {
            ConfidenceState.NEEDS_REVIEW
        } else {
            ConfidenceState.HIGH
        },
        capturedAtEpochMillis = capturedAtEpochMillis,
        captureTimeText = captureTimeFormatter(capturedAtEpochMillis),
        note = "商户未识别，请人工确认".takeIf {
            captureReasonLabel == MANUAL_OCR_CAPTURE_REASON && merchantTitleFromFallback
        },
        rawEvidenceText = mergeReviewEvidenceText(
            rawEvidenceText.orEmpty(),
            rawLine.takeIf { retainRawEvidence && rawEvidenceText.isNullOrBlank() }.orEmpty()
        ),
        parsedFields = parsedFields
    )

    private fun ParsedBillEntry.stableId(): String =
        "bill-${sourceLabel}-${transactionTimeText}-${amountMinor}-${rawLine.hashCode()}"
}

private fun formatCaptureTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(epochMillis))
