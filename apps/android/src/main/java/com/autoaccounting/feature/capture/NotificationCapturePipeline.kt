package com.autoaccounting.feature.capture

import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.feature.review.ReviewQueueEntry
import com.autoaccounting.feature.review.NOTIFICATION_EVIDENCE_LABEL
import com.autoaccounting.feature.review.reviewEvidenceText
import java.security.MessageDigest

class NotificationCapturePipeline(
    private val parser: PaymentNotificationParser = PaymentNotificationParser(),
    private val captureTimeFormatter: (Long) -> String = { "" }
) {
    fun capture(event: PaymentNotificationEvent): ReviewQueueEntry? = evaluate(event).entry

    fun evaluate(event: PaymentNotificationEvent): NotificationCaptureEvaluation {
        val parsing = parser.parseDetailed(event)
        val parsed = parsing.parsed ?: return NotificationCaptureEvaluation(parsing = parsing)
        val entry = ReviewQueueEntry(
            id = "notification-${parsed.sourceLabel}-${event.stableIdentityDigest()}"
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
            rawEvidenceText = reviewEvidenceText(
                NOTIFICATION_EVIDENCE_LABEL,
                parsed.rawEvidenceText
            ),
            parsedFields = parsed.parsedFields
        )
        return NotificationCaptureEvaluation(entry = entry, parsing = parsing)
    }
}

private fun PaymentNotificationEvent.stableIdentityDigest(): String {
    val identity = listOf(packageName, title, text, postedAtEpochMillis.toString()).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { byte -> "%02x".format(byte) }
}

data class NotificationCaptureEvaluation(
    val entry: ReviewQueueEntry? = null,
    val parsing: PaymentNotificationParseResult
)
