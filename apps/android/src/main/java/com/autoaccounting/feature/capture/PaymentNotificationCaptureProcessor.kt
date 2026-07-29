package com.autoaccounting.feature.capture

import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.categorization.applyCategorizationSuggestion
import com.autoaccounting.feature.dedupe.DedupeEngine
import com.autoaccounting.feature.dedupe.DedupeMatchLevel
import com.autoaccounting.feature.diagnostics.DiagnosticComponent
import com.autoaccounting.feature.diagnostics.DiagnosticEvent
import com.autoaccounting.feature.diagnostics.DiagnosticEventMetadata
import com.autoaccounting.feature.diagnostics.DiagnosticLevel
import com.autoaccounting.feature.diagnostics.DiagnosticRecorder
import com.autoaccounting.feature.diagnostics.DiagnosticSensitiveField
import com.autoaccounting.feature.diagnostics.DiagnosticSensitivePayload
import com.autoaccounting.feature.diagnostics.DiagnosticSource
import com.autoaccounting.feature.diagnostics.NoOpDiagnosticRecorder
import com.autoaccounting.feature.diagnostics.newDiagnosticTraceId
import com.autoaccounting.feature.review.ReviewQueueAction
import com.autoaccounting.feature.review.ReviewQueueCaptureCoordinator
import com.autoaccounting.feature.review.ReviewQueueEntry
import com.autoaccounting.feature.review.ReviewQueuePersistence
import com.autoaccounting.feature.review.ReviewQueueState
import com.autoaccounting.feature.review.reduceReviewQueue
import kotlinx.coroutines.flow.first

data class PaymentNotificationProcessResult(
    val state: ReviewQueueState,
    val notification: BookkeepingResultNotification?
)

class PaymentNotificationCaptureProcessor(
    private val pipeline: NotificationCapturePipeline,
    private val reviewQueuePersistence: ReviewQueuePersistence,
    private val preferencesRepository: LocalPreferencesRepository,
    private val captureCoordinator: ReviewQueueCaptureCoordinator =
        ReviewQueueCaptureCoordinator.Shared,
    private val diagnosticRecorder: DiagnosticRecorder = NoOpDiagnosticRecorder,
    private val alipayTransitContextStore: AlipayTransitContextStore =
        AlipayTransitContextStore.None
) {
    suspend fun process(event: PaymentNotificationEvent): ReviewQueueState? =
        processWithResult(event)?.state

    suspend fun processWithResult(
        event: PaymentNotificationEvent,
        traceId: String = newDiagnosticTraceId()
    ): PaymentNotificationProcessResult? = captureCoordinator.serialize {
        reviewQueuePersistence.ensureSystemCategories()
        val evaluation = pipeline.evaluate(event)
        val entry = evaluation.entry
        if (entry == null) {
            diagnosticRecorder.record(
                DiagnosticEvent(
                    metadata = DiagnosticEventMetadata(
                        level = DiagnosticLevel.Info,
                        component = DiagnosticComponent.NotificationParser,
                        event = "notification_rejected",
                        traceId = traceId,
                        source = event.diagnosticSource(),
                        outcome = "rejected",
                        reason = evaluation.parsing.rejectionReason?.name ?: "unknown_rejection"
                    ),
                    sensitivePayload = if (evaluation.parsing.isPaymentRelated) {
                        DiagnosticSensitivePayload(
                            mapOf(
                                DiagnosticSensitiveField.NotificationText to event.joinedText(),
                                DiagnosticSensitiveField.CaptureEvidence to
                                    "payment_notification_preclassification"
                            )
                        )
                    } else {
                        DiagnosticSensitivePayload()
                    }
                )
            )
            return@serialize null
        }
        diagnosticRecorder.record(entry.toNotificationDiagnosticEvent(event, traceId))
        val correlatedEntry = if (
            entry.isGenericAlipayExpenseNotification() &&
            alipayTransitContextStore.consumeForNotification(event.postedAtEpochMillis)
        ) {
            entry.withAlipayMetroContext()
        } else {
            entry
        }
        val rules = preferencesRepository.categorizationRules.first()
        val categorizedEntry = correlatedEntry.applyCategorizationSuggestion(rules)
        val previousState = reviewQueuePersistence.observeState().first()
        val ledgerEntriesForDedupe = reviewQueuePersistence.ledgerEntriesForDedupe()
            .filterNot { ledgerEntry ->
                ledgerEntry.isPriorRedPacketLedgerFor(categorizedEntry)
            }
        val ledgerDedupeResult = DedupeEngine().addCandidate(
            ledgerEntriesForDedupe,
            categorizedEntry
        )
        if (ledgerDedupeResult.matchLevel == DedupeMatchLevel.HIGH_CONFIDENCE) {
            diagnosticRecorder.record(
                notificationMetadataEvent(traceId, event, "ledger_duplicate", "duplicate")
            )
            return@serialize PaymentNotificationProcessResult(
                state = previousState,
                notification = null
            )
        }
        val candidateAfterLedgerCheck = if (
            ledgerDedupeResult.matchLevel == DedupeMatchLevel.LOW_CONFIDENCE
        ) {
            ledgerDedupeResult.pendingEntries.first { it.id == categorizedEntry.id }
        } else {
            categorizedEntry
        }
        val hasPriorRedPacketNotification = previousState.pendingEntries.any { existing ->
            existing.isPriorRedPacketNotificationFor(candidateAfterLedgerCheck)
        }
        val pendingEntriesForDedupe = if (hasPriorRedPacketNotification) {
            previousState.pendingEntries.filterNot { existing ->
                existing.isPriorRedPacketNotificationFor(candidateAfterLedgerCheck)
            }
        } else {
            previousState.pendingEntries
        }
        val dedupeResult = DedupeEngine().addCandidate(
            pendingEntriesForDedupe,
            candidateAfterLedgerCheck
        )
        val entryToPersist = when (dedupeResult.matchLevel) {
            DedupeMatchLevel.HIGH_CONFIDENCE -> {
                val matchedId = dedupeResult.matchedEntry?.id ?: return@serialize null
                dedupeResult.pendingEntries.first { it.id == matchedId }
            }

            DedupeMatchLevel.NONE,
            DedupeMatchLevel.LOW_CONFIDENCE ->
                dedupeResult.pendingEntries.first { it.id == candidateAfterLedgerCheck.id }
        }
        val nextState = if (
            hasPriorRedPacketNotification &&
            entryToPersist.id == candidateAfterLedgerCheck.id
        ) {
            previousState.copy(
                pendingEntries = listOf(entryToPersist) +
                    previousState.pendingEntries.filterNot { it.id == entryToPersist.id },
                lastAction = null
            )
        } else {
            reduceReviewQueue(
                previousState,
                ReviewQueueAction.AddPending(entryToPersist)
            )
        }
        reviewQueuePersistence.persistTransition(previousState, nextState)

        val notification = if (dedupeResult.matchLevel == DedupeMatchLevel.HIGH_CONFIDENCE) {
            null
        } else {
            BookkeepingResultNotification.PendingCreated(
                key = entryToPersist.id,
                count = 1,
                category = entryToPersist.category.takeIf { it.isNotBlank() }
            )
        }
        diagnosticRecorder.record(
            DiagnosticEvent(
                metadata = DiagnosticEventMetadata(
                    level = DiagnosticLevel.Info,
                    component = DiagnosticComponent.Persistence,
                    event = "notification_pending_persisted",
                    traceId = traceId,
                    source = event.diagnosticSource(),
                    outcome = "success",
                    reason = dedupeResult.matchLevel.name,
                    count = 1
                ),
                sensitivePayload = entryToPersist.toSensitiveDiagnosticPayload()
            )
        )
        PaymentNotificationProcessResult(nextState, notification)
    }
}

private fun PaymentNotificationEvent.joinedText(): String =
    listOf(title, text).filter(String::isNotBlank).joinToString(" ")

private fun PaymentNotificationEvent.diagnosticSource(): DiagnosticSource = when (packageName) {
    "com.tencent.mm" -> DiagnosticSource.WeChat
    "com.eg.android.AlipayGphone" -> DiagnosticSource.Alipay
    else -> DiagnosticSource.Unknown
}

private fun ReviewQueueEntry.toNotificationDiagnosticEvent(
    event: PaymentNotificationEvent,
    traceId: String
): DiagnosticEvent = DiagnosticEvent(
    metadata = DiagnosticEventMetadata(
        level = DiagnosticLevel.Info,
        component = DiagnosticComponent.NotificationParser,
        event = "payment_notification_parsed",
        traceId = traceId,
        source = event.diagnosticSource(),
        outcome = "success",
        reason = "parsed"
    ),
    sensitivePayload = toSensitiveDiagnosticPayload() +
        (DiagnosticSensitiveField.NotificationText to event.joinedText())
)

private operator fun DiagnosticSensitivePayload.plus(
    pair: Pair<DiagnosticSensitiveField, String>
): DiagnosticSensitivePayload = copy(fields = fields + pair)

private fun ReviewQueueEntry.toSensitiveDiagnosticPayload(): DiagnosticSensitivePayload {
    val parsed = parsedFields.mapNotNull { field ->
        val separator = field.indexOf('=')
        if (separator <= 0) null else field.substring(0, separator) to field.substring(separator + 1)
    }.toMap()
    return DiagnosticSensitivePayload(
        buildMap {
            put(DiagnosticSensitiveField.Amount, amountMinor.toString())
            put(DiagnosticSensitiveField.Merchant, title)
            note?.let { put(DiagnosticSensitiveField.Note, it) }
            fundingAccountLabel.takeIf(String::isNotBlank)?.let {
                put(DiagnosticSensitiveField.PaymentAccount, it)
            }
            parsed["paymentMethod"]?.let { put(DiagnosticSensitiveField.PaymentMethod, it) }
            parsed["orderNumber"]?.let { put(DiagnosticSensitiveField.OrderNumber, it) }
            parsed["merchantOrderNumber"]?.let {
                put(DiagnosticSensitiveField.MerchantOrderNumber, it)
            }
            rawEvidenceText.takeIf(String::isNotBlank)?.let {
                put(DiagnosticSensitiveField.CaptureEvidence, it)
            }
        }
    )
}

private fun notificationMetadataEvent(
    traceId: String,
    event: PaymentNotificationEvent,
    reason: String,
    outcome: String
): DiagnosticEvent = DiagnosticEvent(
    metadata = DiagnosticEventMetadata(
        level = DiagnosticLevel.Info,
        component = DiagnosticComponent.NotificationProcessor,
        event = "notification_deduplicated",
        traceId = traceId,
        source = event.diagnosticSource(),
        outcome = outcome,
        reason = reason
    )
)

private fun ReviewQueueEntry.isPriorRedPacketNotificationFor(
    candidate: ReviewQueueEntry
): Boolean =
    candidate.isWechatRedPacketNotification &&
        id != candidate.id &&
        hasNotificationCaptureEvidence &&
        sourceLabel == candidate.sourceLabel &&
        title == candidate.title &&
        amountMinor == candidate.amountMinor &&
        kindLabel == candidate.kindLabel

private fun ReviewQueueEntry.isPriorRedPacketLedgerFor(
    candidate: ReviewQueueEntry
): Boolean =
    candidate.isWechatRedPacketNotification &&
        captureReasonLabel == "已入账" &&
        originPendingId != null &&
        originPendingId != candidate.id &&
        sourceLabel == candidate.sourceLabel &&
        title == candidate.title &&
        amountMinor == candidate.amountMinor &&
        kindLabel == candidate.kindLabel

private val ReviewQueueEntry.isWechatRedPacketNotification: Boolean
    get() = sourceLabel == "微信" &&
        captureReasonLabel == "通知捕获" &&
        rawEvidenceText.contains("红包")

private val ReviewQueueEntry.hasNotificationCaptureEvidence: Boolean
    get() = captureReasonLabel == "通知捕获" ||
        parsedFields.contains("证据来源=通知捕获")
