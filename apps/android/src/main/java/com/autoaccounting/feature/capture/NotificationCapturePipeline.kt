package com.autoaccounting.feature.capture

import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.feature.review.ReviewQueueEntry

class NotificationCapturePipeline(
    private val parser: PaymentNotificationParser = PaymentNotificationParser(),
    private val captureTimeFormatter: (Long) -> String = { "" }
) {
    fun capture(event: PaymentNotificationEvent): ReviewQueueEntry? = evaluate(event).entry

    fun evaluate(event: PaymentNotificationEvent): NotificationCaptureEvaluation {
        val parsing = parser.parseDetailed(event)
        val parsed = parsing.parsed ?: return NotificationCaptureEvaluation(parsing = parsing)
        val entry = ReviewQueueEntry(
            id = "notification-${parsed.sourceLabel}-${event.postedAtEpochMillis}-${parsed.amountMinor}"
                .replace(Regex("\\s+"), "-"),
            title = parsed.merchantTitle,
            amountMinor = parsed.amountMinor,
            transactionTimeText = captureTimeFormatter(event.postedAtEpochMillis),
            category = "",
            fundingAccountLabel = parsed.fundingAccountLabel,
            sourceLabel = parsed.sourceLabel,
            kindLabel = parsed.transactionKindLabel,
            captureReasonLabel = "通知捕获",
            confidence = ConfidenceState.NEEDS_REVIEW,
            capturedAtEpochMillis = event.postedAtEpochMillis,
            captureTimeText = captureTimeFormatter(event.postedAtEpochMillis),
            note = parsed.note,
            rawEvidenceText = parsed.rawEvidenceText,
            parsedFields = parsed.parsedFields
        )
        return NotificationCaptureEvaluation(entry = entry, parsing = parsing)
    }
}

data class NotificationCaptureEvaluation(
    val entry: ReviewQueueEntry? = null,
    val parsing: PaymentNotificationParseResult
)
