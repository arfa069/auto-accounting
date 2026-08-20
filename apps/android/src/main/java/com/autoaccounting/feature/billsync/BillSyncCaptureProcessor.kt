package com.autoaccounting.feature.billsync

import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.categorization.applyCategorizationSuggestion
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
import com.autoaccounting.feature.review.ReviewQueuePersistence
import com.autoaccounting.feature.review.reduceReviewQueue
import kotlinx.coroutines.flow.first

@Suppress("LongParameterList")
class BillSyncCaptureProcessor(
    private val pipeline: BillSyncPipeline,
    private val reviewQueuePersistence: ReviewQueuePersistence,
    private val preferencesRepository: LocalPreferencesRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val captureCoordinator: ReviewQueueCaptureCoordinator =
        ReviewQueueCaptureCoordinator.Shared,
    private val diagnosticRecorder: DiagnosticRecorder = NoOpDiagnosticRecorder
) {
    suspend fun process(
        source: BillSyncSource,
        pageText: String,
        traceId: String = newDiagnosticTraceId(),
        sessionId: Long? = null
    ): BillSyncResult = processWithReason(
        source = source,
        pageText = pageText,
        captureReasonLabel = "补录账单",
        traceId = traceId,
        sessionId = sessionId
    )

    suspend fun processManualOcr(
        source: BillSyncSource,
        pageText: String,
        traceId: String = newDiagnosticTraceId(),
        sessionId: Long? = null
    ): BillSyncResult = processWithReason(
        source = source,
        pageText = pageText,
        captureReasonLabel = MANUAL_OCR_CAPTURE_REASON,
        retainRawEvidence = false,
        traceId = traceId,
        sessionId = sessionId,
        isOcr = true
    )

    private suspend fun processWithReason(
        source: BillSyncSource,
        pageText: String,
        captureReasonLabel: String,
        retainRawEvidence: Boolean = true,
        rawEvidenceText: String? = null,
        capturedAtEpochMillis: Long = clock(),
        traceId: String,
        sessionId: Long? = null,
        isOcr: Boolean = false
    ): BillSyncResult = captureCoordinator.serialize {
        diagnosticRecorder.record(
            DiagnosticEvent(
                metadata = DiagnosticEventMetadata(
                    level = DiagnosticLevel.Info,
                    component = if (isOcr) DiagnosticComponent.Ocr else DiagnosticComponent.BillSyncParser,
                    event = if (isOcr) "ocr_text_accepted" else "payment_page_accepted",
                    traceId = traceId,
                    sessionId = sessionId?.toString(),
                    source = source.diagnosticSource(),
                    outcome = "started",
                    reason = captureReasonLabel.diagnosticReason()
                ),
                sensitivePayload = DiagnosticSensitivePayload(
                    mapOf(
                        (if (isOcr) DiagnosticSensitiveField.OcrText else DiagnosticSensitiveField.PageText) to pageText,
                        DiagnosticSensitiveField.CaptureEvidence to captureReasonLabel
                    )
                )
            )
        )
        reviewQueuePersistence.ensureSystemCategories()
        val previousState = reviewQueuePersistence.observeState().first()
        val existingLedgerEntries = reviewQueuePersistence.ledgerEntriesForDedupe()
        val result = pipeline.sync(
            source = source,
            pageText = pageText,
            existingPendingEntries = previousState.pendingEntries,
            existingLedgerEntries = existingLedgerEntries,
            existingIgnoredEntries = previousState.ignoredEntries.map { it.entry },
            capturedAtEpochMillis = capturedAtEpochMillis,
            captureReasonLabel = captureReasonLabel,
            retainRawEvidence = retainRawEvidence,
            rawEvidenceText = rawEvidenceText,
        )
        if (result.errorMessage != null) {
            diagnosticRecorder.record(
                DiagnosticEvent(
                    metadata = DiagnosticEventMetadata(
                        level = DiagnosticLevel.Warning,
                        component = DiagnosticComponent.BillSyncParser,
                        event = "bill_sync_rejected",
                        traceId = traceId,
                        sessionId = sessionId?.toString(),
                        source = source.diagnosticSource(),
                        outcome = "rejected",
                        reason = result.failureReason?.name ?: "unknown_failure"
                    )
                )
            )
            return@serialize result
        }

        val rules = preferencesRepository.categorizationRules.first()
        val createdEntries = result.createdEntries.map {
            it.applyCategorizationSuggestion(rules)
        }
        val mergedEntries = result.mergedEntries.map {
            it.applyCategorizationSuggestion(rules)
        }
        val nextState = (mergedEntries + createdEntries).fold(previousState) { state, entry ->
            reduceReviewQueue(state, ReviewQueueAction.AddPending(entry))
        }
        reviewQueuePersistence.persistTransition(previousState, nextState)
        diagnosticRecorder.record(
            DiagnosticEvent(
                metadata = DiagnosticEventMetadata(
                    level = DiagnosticLevel.Info,
                    component = DiagnosticComponent.Persistence,
                    event = "bill_sync_persisted",
                    traceId = traceId,
                    sessionId = sessionId?.toString(),
                    source = source.diagnosticSource(),
                    outcome = "success",
                    reason = "pending_entries_persisted",
                    count = createdEntries.size
                ),
                sensitivePayload = billSyncPayload(createdEntries + mergedEntries, pageText)
            )
        )
        result.copy(
            createdEntries = createdEntries,
            mergedEntries = mergedEntries
        )
    }
}

private fun BillSyncSource.diagnosticSource(): DiagnosticSource = when (this) {
    BillSyncSource.WeChat -> DiagnosticSource.WeChat
    BillSyncSource.Alipay -> DiagnosticSource.Alipay
}

private fun String.diagnosticReason(): String = when (this) {
    MANUAL_OCR_CAPTURE_REASON -> "manual_ocr_import"
    "补录账单" -> "manual_bill_import"
    else -> "bill_sync_capture"
}

private fun billSyncPayload(
    entries: List<com.autoaccounting.feature.review.ReviewQueueEntry>,
    pageText: String
): DiagnosticSensitivePayload {
    val parsed = entries.flatMap { it.parsedFields }.mapNotNull { field ->
        val separator = field.indexOf('=')
        if (separator <= 0) null else field.substring(0, separator) to field.substring(separator + 1)
    }.toMap()
    return DiagnosticSensitivePayload(
        buildMap {
            put(DiagnosticSensitiveField.PageText, pageText)
            entries.joinToString { it.amountMinor.toString() }.takeIf(String::isNotBlank)?.let {
                put(DiagnosticSensitiveField.Amount, it)
            }
            entries.joinToString { it.title }.takeIf(String::isNotBlank)?.let {
                put(DiagnosticSensitiveField.Merchant, it)
            }
            entries.mapNotNull { it.note }.joinToString().takeIf(String::isNotBlank)?.let {
                put(DiagnosticSensitiveField.Note, it)
            }
            if (DiagnosticSensitiveField.Note !in this) {
                parsed["商品"]?.let { put(DiagnosticSensitiveField.Note, it) }
            }
            entries.joinToString { it.fundingAccountLabel }.takeIf(String::isNotBlank)?.let {
                put(DiagnosticSensitiveField.PaymentAccount, it)
            }
            parsed["支付方式"]?.let { put(DiagnosticSensitiveField.PaymentMethod, it) }
            parsed["交易单号"]?.let { put(DiagnosticSensitiveField.OrderNumber, it) }
            parsed["商户单号"]?.let { put(DiagnosticSensitiveField.MerchantOrderNumber, it) }
            entries.flatMap { it.parsedFields }.joinToString("\n").takeIf(String::isNotBlank)?.let {
                put(DiagnosticSensitiveField.CaptureEvidence, it)
            }
        }
    )
}
