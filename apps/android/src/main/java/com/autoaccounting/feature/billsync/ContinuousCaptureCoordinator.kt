package com.autoaccounting.feature.billsync

import com.autoaccounting.feature.capture.BookkeepingResultNotifier
import com.autoaccounting.feature.capture.PaymentNotificationCaptureTriggers
import com.autoaccounting.feature.capture.toBookkeepingResultNotification
import com.autoaccounting.feature.diagnostics.newDiagnosticTraceId
import com.autoaccounting.feature.monitoring.ContinuousMonitoringEvent
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.monitoring.PaymentScreenCaptureDebouncer
import com.autoaccounting.feature.monitoring.decideContinuousMonitoringCapture
import com.autoaccounting.feature.review.ACCESSIBILITY_EVIDENCE_LABEL
import com.autoaccounting.feature.review.mergeReviewEvidenceText
import com.autoaccounting.feature.review.reviewEvidenceText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ContinuousCaptureDependencies(
    val scope: CoroutineScope,
    val processor: () -> BillSyncCaptureProcessor,
    val resultNotifier: () -> BookkeepingResultNotifier,
    val diagnostics: BillSyncDiagnosticRecorder,
    val state: () -> ContinuousMonitoringState,
    val permissionHealth: () -> ContinuousMonitoringPermissionHealth,
    val settledPageText: (String, String) -> String
)

internal class ContinuousCaptureCoordinator(
    private val dependencies: ContinuousCaptureDependencies
) {
    private val debouncer = PaymentScreenCaptureDebouncer()
    private var captureJob: Job? = null

    val isCapturing: Boolean
        get() = captureJob?.isActive == true

    fun capture(
        packageName: String,
        pageText: String,
        currentPermissionHealth: ContinuousMonitoringPermissionHealth
    ) {
        if (isCapturing) return
        val traceId = newDiagnosticTraceId()
        captureJob = dependencies.scope.launch {
            val decision = withContext(Dispatchers.Default) {
                decideContinuousMonitoringCapture(
                    state = dependencies.state(),
                    event = ContinuousMonitoringEvent(packageName, pageText),
                    permissionHealth = currentPermissionHealth
                )
            }
            if (!decision.shouldCapture) {
                recordRejectedPage(packageName, pageText, traceId, decision.observation.name)
                captureJob = null
                return@launch
            }
            dependencies.diagnostics.recordMetadata(
                "accessibility_stability_wait_started",
                "started",
                "payment_related",
                traceId = traceId,
                source = packageName.accessibilityDiagnosticSource()
            )
            delay(AUTOMATIC_CAPTURE_SETTLE_MILLIS)
            captureJob = null
            processSettledPage(packageName, pageText, traceId)
        }
    }

    fun cancel() {
        captureJob?.cancel()
        captureJob = null
    }

    private suspend fun processSettledPage(
        packageName: String,
        fallbackPageText: String,
        traceId: String
    ) {
        val pageText = runCatching {
            dependencies.settledPageText(packageName, fallbackPageText)
        }.getOrDefault(fallbackPageText)
        val decision = withContext(Dispatchers.Default) {
            decideContinuousMonitoringCapture(
                state = dependencies.state(),
                event = ContinuousMonitoringEvent(packageName, pageText),
                permissionHealth = dependencies.permissionHealth()
            )
        }
        if (!decision.shouldCapture) {
            dependencies.diagnostics.recordPageDecision(
                event = "accessibility_window_rejected",
                traceId = traceId,
                packageName = packageName,
                reason = decision.observation.name,
                pageText = pageText.takeIf { isRecognizedPage(packageName, it) }
            )
            return
        }
        if (!debouncer.shouldProcess(packageName, pageText)) {
            dependencies.diagnostics.recordMetadata(
                "accessibility_page_rejected",
                "rejected",
                "debounced",
                traceId = traceId,
                source = packageName.accessibilityDiagnosticSource()
            )
            return
        }
        val source = BillSyncSource.fromPackageName(packageName) ?: return
        val notificationTrigger = PaymentNotificationCaptureTriggers.awaitPendingFor(packageName)
        if (
            notificationTrigger != null &&
            !PaymentNotificationCaptureTriggers.tryClaimFusion(notificationTrigger.captureId)
        ) {
            return
        }
        val fusedPageText = fusePaymentEvidenceText(
            source = source,
            accessibilityEvidence = PaymentTextEvidence(pageText),
            ocrEvidence = null,
            notificationEvidence = notificationTrigger?.toPaymentTextEvidence()
        )
        runCatching {
            withContext(Dispatchers.IO) {
                dependencies.processor().processAutomatic(
                    source = source,
                    pageText = fusedPageText,
                    rawEvidenceText = mergeReviewEvidenceText(
                        notificationTrigger?.rawNotificationEvidence.orEmpty(),
                        reviewEvidenceText(ACCESSIBILITY_EVIDENCE_LABEL, pageText)
                    ),
                    traceId = traceId
                )
            }
        }.onSuccess { result ->
            if (notificationTrigger != null) {
                if (result.errorMessage == null) {
                    PaymentNotificationCaptureTriggers.complete(notificationTrigger.captureId)
                } else {
                    PaymentNotificationCaptureTriggers.releaseFusion(notificationTrigger.captureId)
                }
            }
            result.toBookkeepingResultNotification(source.label)?.let { notification ->
                dependencies.diagnostics.recordMetadata(
                    "result_notification_requested",
                    "requested",
                    notification.javaClass.simpleName.ifBlank { "bookkeeping_result" },
                    traceId = traceId,
                    source = source.accessibilityDiagnosticSource()
                )
                dependencies.resultNotifier().notify(notification)
            }
        }.onFailure { error ->
            notificationTrigger?.let {
                PaymentNotificationCaptureTriggers.releaseFusion(it.captureId)
            }
            dependencies.diagnostics.recordFailure("automatic_page_capture_failed", traceId, source, null, error)
        }
    }

    private fun recordRejectedPage(
        packageName: String,
        pageText: String,
        traceId: String,
        fallbackReason: String
    ) {
        val source = BillSyncSource.fromPackageName(packageName)
        val observation = source?.let { observeBillSyncPage(it, pageText) }
            ?: BillSyncPageObservation.Ignored
        dependencies.diagnostics.recordPageDecision(
            event = "accessibility_page_rejected",
            traceId = traceId,
            packageName = packageName,
            reason = observation.takeUnless { it == BillSyncPageObservation.Ignored }?.name
                ?: fallbackReason,
            pageText = pageText.takeIf { observation != BillSyncPageObservation.Ignored }
        )
    }

    private fun isRecognizedPage(packageName: String, pageText: String): Boolean =
        BillSyncSource.fromPackageName(packageName)?.let { source ->
            observeBillSyncPage(source, pageText) != BillSyncPageObservation.Ignored
        } == true

    private companion object {
        const val AUTOMATIC_CAPTURE_SETTLE_MILLIS = 500L
    }
}
