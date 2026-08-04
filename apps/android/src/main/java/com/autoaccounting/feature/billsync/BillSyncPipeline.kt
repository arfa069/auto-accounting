package com.autoaccounting.feature.billsync

import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.feature.monitoring.hasWechatMerchantPaymentSuccessSignature
import com.autoaccounting.feature.monitoring.hasWechatReceivedRedPacketSuccessSignature
import com.autoaccounting.feature.monitoring.hasWechatSentRedPacketSuccessSignature
import com.autoaccounting.feature.review.ReviewQueueEntry
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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

enum class AutomaticCaptureVerification {
    Standard,
    RequireRecentNotification
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
    val automaticCaptureVerification: AutomaticCaptureVerification
) {
    val isWechatRedPacketAutomaticCapture: Boolean
        get() = source == BillSyncSource.WeChat &&
            captureReasonLabel == "支付结果自动捕获" &&
            (
                hasWechatSentRedPacketSuccessSignature(pageText) ||
                    hasWechatReceivedRedPacketSuccessSignature(pageText)
                )

    val isNotificationVerifiedRedPacket: Boolean
        get() = isWechatRedPacketAutomaticCapture &&
            automaticCaptureVerification ==
            AutomaticCaptureVerification.RequireRecentNotification
}

class BillSyncPipeline(
    private val parser: BillPageParser = BillPageParser(),
    private val captureTimeFormatter: (Long) -> String = ::formatCaptureTime
) {
    fun sync(
        source: BillSyncSource,
        pageText: String,
        existingPendingEntries: List<ReviewQueueEntry>,
        existingLedgerEntries: List<ReviewQueueEntry> = emptyList(),
        existingIgnoredEntries: List<ReviewQueueEntry> = emptyList(),
        capturedAtEpochMillis: Long,
        captureReasonLabel: String = "补录账单",
        retainRawEvidence: Boolean = true,
        automaticCaptureVerification: AutomaticCaptureVerification =
            AutomaticCaptureVerification.Standard
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
            automaticCaptureVerification = automaticCaptureVerification
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
        if (parsedEntries.isEmpty()) {
            return invocation.failureResult()
        }
        val isWechatRedPacketAutomaticCapture =
            invocation.isWechatRedPacketAutomaticCapture
        val isNotificationVerifiedRedPacket = invocation.isNotificationVerifiedRedPacket
        val deduplication = BillSyncDeduplication(
            existingPendingEntries = invocation.existingPendingEntries,
            existingLedgerEntries = invocation.existingLedgerEntries,
            existingIgnoredEntries = invocation.existingIgnoredEntries,
            isWechatRedPacketAutomaticCapture = isWechatRedPacketAutomaticCapture,
            isNotificationVerifiedRedPacket = isNotificationVerifiedRedPacket
        )
        invocation.prepareCandidates(parsedEntries)
            .forEach(deduplication::addCandidate)
        val deduplicationResult = deduplication.result()

        return BillSyncResult(
            steps = successSteps,
            createdEntries = deduplicationResult.createdEntries,
            mergedEntries = deduplicationResult.mergedEntries,
            duplicateSkippedCount = deduplicationResult.duplicateSkippedCount,
            summary =
                "已创建 ${deduplicationResult.createdEntries.size} 条，已去重 " +
                    "${deduplicationResult.duplicateSkippedCount} 条"
        )
    }

    private fun BillSyncInvocation.prepareCandidates(
        parsedEntries: List<ParsedBillEntry>
    ): List<ReviewQueueEntry> {
        val requiresExplicitWechatMerchant =
            source == BillSyncSource.WeChat &&
                captureReasonLabel == AUTOMATIC_CAPTURE_REASON &&
                hasWechatMerchantPaymentSuccessSignature(pageText)
        val verificationRequired =
            automaticCaptureVerification ==
                AutomaticCaptureVerification.RequireRecentNotification

        return parsedEntries.mapNotNull { parsed ->
            if (requiresExplicitWechatMerchant && parsed.merchantTitleFromFallback) {
                return@mapNotNull null
            }
            val candidate = parsed.toPendingEntry(
                capturedAtEpochMillis = capturedAtEpochMillis,
                captureReasonLabel = captureReasonLabel,
                retainRawEvidence = retainRawEvidence
            )
            val matchingNotifications = candidate.matchingRecentNotifications(
                existingPendingEntries = existingPendingEntries,
                verification = automaticCaptureVerification
            )
            if (verificationRequired && matchingNotifications.size != 1) {
                return@mapNotNull null
            }
            candidate.correlateWithUniqueRecentNotification(
                transactionTimeFromFallback = parsed.transactionTimeFromFallback,
                captureReasonLabel = captureReasonLabel,
                matchingNotifications = matchingNotifications
            )
        }
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
        retainRawEvidence: Boolean
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
            rawEvidenceText = rawLine.takeIf { retainRawEvidence }.orEmpty(),
            parsedFields = parsedFields
        )

    private fun ParsedBillEntry.stableId(): String =
        "bill-${sourceLabel}-${transactionTimeText}-${amountMinor}-${rawLine.hashCode()}"

    private fun ReviewQueueEntry.correlateWithUniqueRecentNotification(
        transactionTimeFromFallback: Boolean,
        captureReasonLabel: String,
        matchingNotifications: List<ReviewQueueEntry>
    ): ReviewQueueEntry {
        if (!transactionTimeFromFallback || captureReasonLabel != AUTOMATIC_CAPTURE_REASON) {
            return this
        }

        if (matchingNotifications.size > 1) {
            return copy(
                confidence = ConfidenceState.DUPLICATE_SUSPECT,
                note = "存在多条近期相同通知，请人工确认",
                parsedFields = (parsedFields + "关联结果=近期通知候选不唯一").distinct()
            )
        }
        val uniqueNotification = matchingNotifications.singleOrNull() ?: return this
        return copy(
            transactionTimeText = uniqueNotification.transactionTimeText,
            parsedFields = (parsedFields + "交易时间=关联近期唯一通知").distinct()
        )
    }

    private fun ReviewQueueEntry.matchingRecentNotifications(
        existingPendingEntries: List<ReviewQueueEntry>,
        verification: AutomaticCaptureVerification
    ): List<ReviewQueueEntry> = existingPendingEntries.filter { existing ->
        existing.hasNotificationCaptureEvidence &&
            existing.sourceLabel == sourceLabel &&
            existing.amountMinor == amountMinor &&
            existing.kindLabel == kindLabel &&
            when (verification) {
                AutomaticCaptureVerification.Standard ->
                    transactionTimeText.minutesFrom(existing.transactionTimeText)
                        ?.let { it <= RECENT_NOTIFICATION_WINDOW_MINUTES } == true
                AutomaticCaptureVerification.RequireRecentNotification ->
                    !existing.hasAutomaticOcrCaptureEvidence &&
                        existing.title.trim().equals(title.trim(), ignoreCase = true) &&
                        existing.wasCapturedWithinWechatNotificationWindow(capturedAtEpochMillis)
            }
    }

    private fun String.minutesFrom(other: String): Long? {
        val first = parseTransactionTime(this) ?: return null
        val second = parseTransactionTime(other) ?: return null
        return kotlin.math.abs(ChronoUnit.MINUTES.between(first, second))
    }

    private fun parseTransactionTime(text: String): LocalDateTime? = runCatching {
        LocalDateTime.parse(text.trim(), TRANSACTION_TIME_FORMATTER)
    }.getOrNull()

    private companion object {
        const val AUTOMATIC_CAPTURE_REASON = "支付结果自动捕获"
        const val RECENT_NOTIFICATION_WINDOW_MINUTES = 60L
        val TRANSACTION_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}

private fun formatCaptureTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(epochMillis))
