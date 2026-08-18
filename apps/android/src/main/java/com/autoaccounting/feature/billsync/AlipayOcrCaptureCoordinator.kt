package com.autoaccounting.feature.billsync

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoaccounting.feature.capture.BookkeepingResultNotifier
import com.autoaccounting.feature.capture.PaymentNotificationCaptureTrigger
import com.autoaccounting.feature.capture.PaymentNotificationCaptureTriggers
import com.autoaccounting.feature.capture.toBookkeepingResultNotification
import com.autoaccounting.feature.diagnostics.DiagnosticComponent
import com.autoaccounting.feature.diagnostics.DiagnosticSensitiveField
import com.autoaccounting.feature.diagnostics.DiagnosticSensitivePayload
import com.autoaccounting.feature.diagnostics.DiagnosticSource
import com.autoaccounting.feature.diagnostics.newDiagnosticTraceId
import com.autoaccounting.feature.monitoring.ContinuousMonitoringEvent
import com.autoaccounting.feature.monitoring.PaymentScreenCaptureDebouncer
import com.autoaccounting.feature.monitoring.decideContinuousMonitoringCapture
import com.autoaccounting.feature.review.ACCESSIBILITY_EVIDENCE_LABEL
import com.autoaccounting.feature.review.OCR_EVIDENCE_LABEL
import com.autoaccounting.feature.review.mergeReviewEvidenceText
import com.autoaccounting.feature.review.reviewEvidenceText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class AlipayPaymentCaptureRequest(
    val packageName: String,
    val accessibilityText: PaymentTextEvidence,
    val windowId: Int,
    val notificationTrigger: PaymentNotificationCaptureTrigger?,
    val windowContext: String,
    val traceId: String,
    val generation: Long,
    val allowRecentPaymentContext: Boolean
)

private data class AlipayPaymentCaptureFrame(
    val request: AlipayPaymentCaptureRequest,
    val capturedAtEpochMillis: Long,
    val bitmap: Bitmap?
)

// Payment-result and transit-exit capture share one lifecycle and cancellation boundary.
@Suppress("LargeClass", "LongParameterList", "TooManyFunctions")
internal class AlipayOcrCaptureCoordinator(
    private val scope: CoroutineScope,
    private val host: AccessibilityCaptureHost,
    private val processor: () -> BillSyncCaptureProcessor,
    private val resultNotifier: () -> BookkeepingResultNotifier,
    private val diagnostics: BillSyncDiagnosticRecorder,
    private val automaticCaptureDebouncer: PaymentScreenCaptureDebouncer
) {
    private var transitJob: Job? = null
    private var paymentJob: Job? = null
    private var paymentCaptureJob: Job? = null
    private var pendingPaymentCaptureRequest: AlipayPaymentCaptureRequest? = null
    private var pendingPaymentCaptureFrame: AlipayPaymentCaptureFrame? = null
    private var paymentCaptureGeneration: Long = 0L
    private var lastTransitAttemptAtElapsedMillis: Long = 0L
    private var lastPaymentAttemptAtElapsedMillis: Long = 0L
    private var paymentFlowObservedAtElapsedMillis: Long = 0L
    private var transitSurfaceInspected: Boolean = false
    private var paymentSurfaceInspected: Boolean = false
    private var paymentSurfaceFingerprint: Int? = null
    private var paymentProbeStartedAtElapsedMillis: Long? = null
    private var paymentProbeWindowId: Int? = null

    fun observePaymentFlow(
        packageName: String,
        pageText: String,
        shouldConsiderContinuousMonitoring: Boolean
    ) {
        if (!shouldConsiderContinuousMonitoring) return
        val source = BillSyncSource.fromPackageName(packageName)
        if (source == BillSyncSource.Alipay) {
            if (isAlipayPaymentInitiationPage(pageText)) {
                paymentFlowObservedAtElapsedMillis = SystemClock.elapsedRealtime()
            }
        } else if (source == BillSyncSource.WeChat) {
            resetPaymentState()
        }
    }

    fun handleSurface(
        packageName: String,
        pageText: String,
        shouldConsiderContinuousMonitoring: Boolean,
        activeRoot: AccessibilityNodeInfo?,
        eventType: Int,
        eventWindowId: Int,
        notificationTrigger: PaymentNotificationCaptureTrigger? = null,
        windowContext: String = ""
    ): Boolean {
        if (handleTransitSurface(packageName, pageText, shouldConsiderContinuousMonitoring, activeRoot)) {
            return true
        }
        return handlePaymentSurface(
            packageName,
            pageText,
            shouldConsiderContinuousMonitoring,
            activeRoot,
            eventType,
            eventWindowId,
            notificationTrigger,
            windowContext
        )
    }

    fun handleNotificationTrigger(
        trigger: PaymentNotificationCaptureTrigger,
        activeRoot: AccessibilityNodeInfo,
        windowContext: String = ""
    ): Boolean = handlePaymentSurface(
        packageName = trigger.packageName,
        pageText = activeRoot.collectVisibleText(),
        shouldConsiderContinuousMonitoring = true,
        activeRoot = activeRoot,
        eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        eventWindowId = activeRoot.windowId,
        notificationTrigger = trigger,
        windowContext = windowContext
    )

    fun cancel() {
        transitJob?.cancel()
        transitJob = null
        resetPaymentState()
        transitSurfaceInspected = false
    }

    private fun handleTransitSurface(
        packageName: String,
        pageText: String,
        shouldConsiderContinuousMonitoring: Boolean,
        activeRoot: AccessibilityNodeInfo?
    ): Boolean {
        if (packageName != BillSyncSource.Alipay.packageName) {
            transitSurfaceInspected = false
            return false
        }
        val shouldConsiderTransit = shouldConsiderContinuousMonitoring &&
            activeRoot != null &&
            host.isApplicationWindow(activeRoot.windowId)
        if (!shouldConsiderTransit) {
            transitSurfaceInspected = false
            return false
        }
        if (isCompletedAlipayMetroExit(pageText)) {
            if (!transitSurfaceInspected) {
                transitSurfaceInspected = true
                recordMetroExitContext("accessible_transit_signature")
            }
            return true
        }
        val shouldAttemptOcr = shouldAttemptAlipayTransitOcrFallback(
            packageName = packageName,
            pageText = pageText,
            sdkInt = Build.VERSION.SDK_INT,
            isApplicationWindow = true
        )
        if (shouldAttemptOcr) {
            if (!transitSurfaceInspected) {
                transitSurfaceInspected = true
                captureTransitFallback(packageName)
            }
            return true
        }
        transitSurfaceInspected = false
        return false
    }

    @Suppress("CyclomaticComplexMethod")
    private fun handlePaymentSurface(
        packageName: String,
        pageText: String,
        shouldConsiderContinuousMonitoring: Boolean,
        activeRoot: AccessibilityNodeInfo?,
        eventType: Int,
        eventWindowId: Int,
        notificationTrigger: PaymentNotificationCaptureTrigger?,
        windowContext: String
    ): Boolean {
        if (packageName != BillSyncSource.Alipay.packageName) {
            resetPaymentState()
            return false
        }
        if (
            !shouldConsiderContinuousMonitoring ||
            (activeRoot != null && activeRoot.packageName?.toString() != packageName)
        ) {
            resetPaymentState()
            return false
        }

        val windowId = activeRoot?.windowId ?: eventWindowId
        val isApplicationWindow = activeRoot != null && host.isApplicationWindow(windowId)
        val hasRecentPaymentFlow = hasRecentPaymentFlow()
        val hasActiveResultProbe = hasActiveResultProbe(windowId)
        val shouldAttempt = shouldAttemptAlipayOcrFallback(
            AlipayOcrFallbackRequest(
                packageName = packageName,
                pageText = pageText,
                sdkInt = Build.VERSION.SDK_INT,
                isApplicationWindow = isApplicationWindow,
                hasRecentPaymentFlow = hasRecentPaymentFlow,
                eventType = eventType,
                windowId = windowId,
                hasNotificationTrigger = notificationTrigger != null,
                hasActiveResultProbe = hasActiveResultProbe
            )
        )
        if (!shouldAttempt) {
            if (pageText.isNotBlank()) {
                resetPaymentSurface()
                clearPaymentProbe()
            }
            return false
        }

        if (!hasActiveResultProbe) startPaymentProbe(windowId)

        val surfaceFingerprint = listOf(
            windowId,
            eventType,
            pageText.hashCode(),
            notificationTrigger?.captureId
        ).hashCode()
        if (surfaceFingerprint != paymentSurfaceFingerprint) {
            paymentSurfaceInspected = false
            paymentSurfaceFingerprint = surfaceFingerprint
        }
        if (paymentSurfaceInspected) return true

        paymentSurfaceInspected = true
        val accessibilityEvidence = activeRoot?.collectVisibleTextEvidence()
            ?.takeIf { it.text.isNotBlank() }
            ?: PaymentTextEvidence(pageText)
        capturePaymentFallback(
            packageName = packageName,
            accessibilityText = accessibilityEvidence,
            windowId = windowId,
            notificationTrigger = notificationTrigger,
            windowContext = windowContext,
            allowRecentPaymentContext = hasRecentPaymentFlow ||
                notificationTrigger != null ||
                hasActiveResultProbe(windowId)
        )
        return true
    }

    private fun hasRecentPaymentFlow(): Boolean {
        val ageMillis = SystemClock.elapsedRealtime() - paymentFlowObservedAtElapsedMillis
        return paymentFlowObservedAtElapsedMillis > 0L &&
            ageMillis in 0..ALIPAY_PAYMENT_FLOW_WINDOW_MILLIS
    }

    private fun hasActiveResultProbe(windowId: Int): Boolean {
        val startedAt = paymentProbeStartedAtElapsedMillis ?: return false
        val ageMillis = SystemClock.elapsedRealtime() - startedAt
        return paymentProbeWindowId == windowId && ageMillis in 0..ALIPAY_RESULT_PROBE_WINDOW_MILLIS
    }

    private fun startPaymentProbe(windowId: Int) {
        paymentProbeStartedAtElapsedMillis = SystemClock.elapsedRealtime()
        paymentProbeWindowId = windowId
    }

    private fun clearPaymentProbe() {
        paymentProbeStartedAtElapsedMillis = null
        paymentProbeWindowId = null
    }

    private fun resetPaymentState() {
        paymentFlowObservedAtElapsedMillis = 0L
        clearPaymentProbe()
        resetPaymentSurface()
        paymentCaptureGeneration += 1L
        pendingPaymentCaptureRequest = null
        pendingPaymentCaptureFrame?.bitmap?.recycle()
        pendingPaymentCaptureFrame = null
        paymentCaptureJob?.cancel()
        paymentCaptureJob = null
        paymentJob?.cancel()
        paymentJob = null
    }

    private fun resetPaymentSurface() {
        paymentSurfaceInspected = false
        paymentSurfaceFingerprint = null
    }

    private fun capturePaymentFallback(
        packageName: String,
        accessibilityText: PaymentTextEvidence,
        windowId: Int,
        notificationTrigger: PaymentNotificationCaptureTrigger?,
        windowContext: String,
        allowRecentPaymentContext: Boolean
    ) {
        if (!host.isScreenReady()) {
            recordPaymentRejection(
                reason = "screen_off_or_locked",
                windowContext = windowContext
            )
            resetPaymentSurface()
            return
        }
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        if (
            lastPaymentAttemptAtElapsedMillis > 0L &&
            nowElapsedMillis - lastPaymentAttemptAtElapsedMillis < OCR_ATTEMPT_COOLDOWN_MILLIS
        ) {
            recordPaymentRejection(reason = "cooldown", windowContext = windowContext)
            resetPaymentSurface()
            return
        }
        val traceId = newDiagnosticTraceId()
        diagnostics.recordMetadata(
            event = "alipay_ocr_started",
            outcome = "started",
            reason = "payment_result_evidence_fusion",
            traceId = traceId,
            source = DiagnosticSource.Alipay,
            component = DiagnosticComponent.Ocr,
            sensitivePayload = windowContext.toWindowContextPayload()
        )

        enqueuePaymentCapture(
            AlipayPaymentCaptureRequest(
                packageName = packageName,
                accessibilityText = accessibilityText,
                windowId = windowId,
                notificationTrigger = notificationTrigger,
                windowContext = windowContext,
                traceId = traceId,
                generation = paymentCaptureGeneration,
                allowRecentPaymentContext = allowRecentPaymentContext
            )
        )
    }

    private fun enqueuePaymentCapture(request: AlipayPaymentCaptureRequest) {
        pendingPaymentCaptureRequest = request
        if (paymentCaptureJob?.isActive == true) return
        paymentCaptureJob = scope.launch {
            try {
                while (true) {
                    val pendingRequest = pendingPaymentCaptureRequest ?: break
                    pendingPaymentCaptureRequest = null
                    if (pendingRequest.generation != paymentCaptureGeneration) continue

                    var screenshot: Bitmap? = null
                    try {
                        delay(AUTOMATIC_CAPTURE_SETTLE_MILLIS)
                        screenshot = host.captureCurrentDisplayBitmap(pendingRequest.traceId)
                        if (screenshot == null && pendingRequest.windowId >= 0) {
                            delay(AUTOMATIC_CAPTURE_SETTLE_MILLIS)
                            screenshot = host.captureScreenBitmap(
                                pendingRequest.windowId,
                                pendingRequest.traceId
                            )
                        }
                        if (pendingRequest.generation != paymentCaptureGeneration) continue
                        enqueuePaymentFrame(
                            AlipayPaymentCaptureFrame(
                                request = pendingRequest,
                                capturedAtEpochMillis = System.currentTimeMillis(),
                                bitmap = screenshot
                            )
                        )
                        screenshot = null
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        diagnostics.recordFailure(
                            "alipay_screenshot_failed",
                            pendingRequest.traceId,
                            BillSyncSource.Alipay,
                            null,
                            error
                        )
                    } finally {
                        screenshot?.recycle()
                    }
                }
            } finally {
                paymentCaptureJob = null
            }
        }
    }

    private fun enqueuePaymentFrame(frame: AlipayPaymentCaptureFrame) {
        pendingPaymentCaptureFrame?.bitmap?.recycle()
        pendingPaymentCaptureFrame = frame
        if (paymentJob?.isActive == true) return
        paymentJob = scope.launch {
            try {
                while (true) {
                    val pendingFrame = pendingPaymentCaptureFrame ?: break
                    pendingPaymentCaptureFrame = null
                    if (pendingFrame.request.generation != paymentCaptureGeneration) {
                        pendingFrame.bitmap?.recycle()
                        continue
                    }
                    if (processPaymentFrame(pendingFrame)) {
                        completePaymentCapture()
                        break
                    }
                    val windowId = pendingFrame.request.windowId
                    val traceId = pendingFrame.request.traceId
                    resetPaymentSurface()
                    if (hasActiveResultProbe(windowId)) {
                        scope.launch {
                            delay(RETRY_SETTLE_MILLIS)
                            if (
                                pendingPaymentCaptureRequest == null &&
                                hasActiveResultProbe(windowId) &&
                                paymentCaptureGeneration == pendingFrame.request.generation
                            ) {
                                diagnostics.recordMetadata(
                                    event = "alipay_ocr_retry_enqueued",
                                    outcome = "started",
                                    reason = "probe_active",
                                    traceId = traceId,
                                    source = DiagnosticSource.Alipay,
                                    component = DiagnosticComponent.Ocr
                                )
                                enqueuePaymentCapture(
                                    pendingFrame.request.copy(
                                        generation = paymentCaptureGeneration,
                                        traceId = newDiagnosticTraceId()
                                    )
                                )
                            }
                        }
                    }
                }
            } finally {
                paymentJob = null
            }
        }
    }

    private suspend fun processPaymentFrame(frame: AlipayPaymentCaptureFrame): Boolean {
        val request = frame.request
        val ocrText = frame.bitmap?.let { screenshot ->
            try {
                host.recognizeScreenEvidence(screenshot)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                diagnostics.recordFailure(
                    "alipay_ocr_failed",
                    request.traceId,
                    BillSyncSource.Alipay,
                    null,
                    error
                )
                null
            } finally {
                screenshot.recycle()
            }
        }
        val accessibilityEvidence = request.accessibilityText.toAccessibilityReviewEvidence()
        val ocrEvidence = ocrText.toOcrReviewEvidence()
        val evidenceText = mergeReviewEvidenceText(accessibilityEvidence, ocrEvidence)
        val notificationTrigger = request.notificationTrigger
            ?: PaymentNotificationCaptureTriggers.awaitPendingFor(request.packageName)
        if (
            notificationTrigger != null &&
            !PaymentNotificationCaptureTriggers.tryClaimFusion(notificationTrigger.captureId)
        ) {
            return true
        }
        val notificationEvidence = notificationTrigger?.toPaymentTextEvidence()
        val validationPageText = fusePaymentEvidenceText(
            source = BillSyncSource.Alipay,
            accessibilityEvidence = request.accessibilityText,
            ocrEvidence = ocrText
        )
        val processed = processPaymentResult(
            packageName = request.packageName,
            pageText = fusePaymentEvidenceText(
                source = BillSyncSource.Alipay,
                accessibilityEvidence = request.accessibilityText,
                ocrEvidence = ocrText,
                notificationEvidence = notificationEvidence
            ),
            validationPageText = validationPageText,
            rawEvidenceText = mergeReviewEvidenceText(
                notificationTrigger?.rawNotificationEvidence.orEmpty(),
                evidenceText
            ),
            traceId = request.traceId,
            allowRecentPaymentContext = request.allowRecentPaymentContext,
            capturedAtEpochMillis = frame.capturedAtEpochMillis,
            ocrDiagnosticText = ocrText?.text,
            windowContext = request.windowContext
        )
        if (notificationTrigger != null) {
            if (processed) {
                PaymentNotificationCaptureTriggers.complete(notificationTrigger.captureId)
            } else {
                PaymentNotificationCaptureTriggers.releaseFusion(notificationTrigger.captureId)
            }
        }
        if (!processed && frame.bitmap == null) {
            recordPaymentRejection(
                reason = "screenshot_unavailable",
                traceId = request.traceId,
                windowContext = request.windowContext
            )
        }
        return processed
    }

    private fun completePaymentCapture() {
        lastPaymentAttemptAtElapsedMillis = SystemClock.elapsedRealtime()
        paymentFlowObservedAtElapsedMillis = 0L
        clearPaymentProbe()
        resetPaymentSurface()
        paymentCaptureGeneration += 1L
        pendingPaymentCaptureRequest = null
        pendingPaymentCaptureFrame?.bitmap?.recycle()
        pendingPaymentCaptureFrame = null
    }

    private suspend fun processPaymentResult(
        packageName: String,
        pageText: String,
        validationPageText: String,
        rawEvidenceText: String,
        traceId: String,
        allowRecentPaymentContext: Boolean,
        capturedAtEpochMillis: Long,
        ocrDiagnosticText: String?,
        windowContext: String
    ): Boolean {
        val ocrDecision = decideAlipayOcrCapture(
            pageText = validationPageText,
            allowRecentPaymentContext = allowRecentPaymentContext
        )
        if (!ocrDecision.shouldCapture) {
            recordPaymentRejection(
                reason = ocrDecision.rejectionReason?.name ?: "unknown_rejection",
                traceId = traceId,
                ocrDiagnosticText = ocrDiagnosticText,
                windowContext = windowContext
            )
            return false
        }
        val acceptedPageText = pageText.withTrustedAlipayPaymentContext(allowRecentPaymentContext)
        val permissionHealth = host.currentPermissionHealth()
        if (!host.monitoringState.enabled || !permissionHealth.isHealthy) {
            recordPaymentRejection("monitoring_blocked", traceId, ocrDiagnosticText, windowContext)
            return false
        }
        val decision = decideContinuousMonitoringCapture(
            state = host.monitoringState,
            event = ContinuousMonitoringEvent(packageName, acceptedPageText),
            permissionHealth = permissionHealth
        )
        if (!decision.shouldCapture) {
            recordPaymentRejection(decision.observation.name, traceId, ocrDiagnosticText, windowContext)
            return false
        }
        if (!automaticCaptureDebouncer.shouldProcess(packageName, acceptedPageText)) {
            recordPaymentRejection("debounced", traceId, ocrDiagnosticText, windowContext)
            return false
        }

        val source = BillSyncSource.Alipay
        val outcome = runCatching {
            processor().processAutomatic(
                source = source,
                pageText = acceptedPageText,
                retainRawEvidence = false,
                rawEvidenceText = rawEvidenceText,
                capturedAtEpochMillis = capturedAtEpochMillis,
                traceId = traceId
            )
        }.onSuccess { result ->
            result.toBookkeepingResultNotification(source.label)?.let { notification ->
                diagnostics.recordMetadata(
                    "result_notification_requested",
                    "requested",
                    notification.javaClass.simpleName.ifBlank { "bookkeeping_result" },
                    traceId = traceId,
                    source = source.accessibilityDiagnosticSource()
                )
                resultNotifier().notify(notification)
            }
        }.onFailure { error ->
            diagnostics.recordFailure("alipay_ocr_processor_failed", traceId, source, null, error)
        }
        return outcome.isSuccess && outcome.getOrNull()?.errorMessage == null
    }

    private fun recordPaymentRejection(
        reason: String,
        traceId: String? = null,
        ocrDiagnosticText: String? = null,
        windowContext: String? = null
    ) {
        diagnostics.recordMetadata(
            event = "alipay_ocr_rejected",
            outcome = "rejected",
            reason = reason,
            traceId = traceId ?: newDiagnosticTraceId(),
            source = DiagnosticSource.Alipay,
            component = DiagnosticComponent.Ocr,
            sensitivePayload = DiagnosticSensitivePayload(
                buildMap {
                    ocrDiagnosticText?.takeIf(String::isNotBlank)?.let {
                        put(DiagnosticSensitiveField.OcrText, it)
                    }
                    windowContext?.takeIf(String::isNotBlank)?.let {
                        put(DiagnosticSensitiveField.WindowContext, it)
                    }
                }
            )
        )
    }

    private fun captureTransitFallback(packageName: String) {
        if (!host.isScreenReady()) {
            diagnostics.recordMetadata(
                "alipay_transit_ocr_rejected",
                "rejected",
                "screen_off_or_locked",
                source = DiagnosticSource.Alipay,
                component = DiagnosticComponent.Ocr
            )
            return
        }
        if (transitJob?.isActive == true) return
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        if (nowElapsedMillis - lastTransitAttemptAtElapsedMillis < OCR_ATTEMPT_COOLDOWN_MILLIS) return
        lastTransitAttemptAtElapsedMillis = nowElapsedMillis
        val traceId = newDiagnosticTraceId()
        diagnostics.recordMetadata(
            event = "alipay_transit_ocr_started",
            outcome = "started",
            reason = "transit_accessibility_cue",
            traceId = traceId,
            source = DiagnosticSource.Alipay,
            component = DiagnosticComponent.Ocr
        )

        transitJob = scope.launch {
            try {
                delay(AUTOMATIC_CAPTURE_SETTLE_MILLIS)
                val initialRoot = currentTransitRoot(packageName)
                if (initialRoot == null) {
                    recordTransitRejection("settled_context_invalid", traceId)
                    return@launch
                }
                val windowId = initialRoot.windowId
                val screenshot = host.captureScreenBitmap(windowId)
                if (screenshot == null) {
                    recordTransitRejection("screenshot_unavailable", traceId)
                    return@launch
                }
                try {
                    val currentRoot = currentTransitRoot(packageName)
                    if (currentRoot == null || currentRoot.windowId != windowId) {
                        recordTransitRejection("window_changed_before_ocr", traceId)
                        return@launch
                    }
                    if (!isCompletedAlipayMetroExit(host.recognizeScreen(screenshot))) {
                        recordTransitRejection("completion_signature_missing", traceId)
                        return@launch
                    }
                    recordMetroExitContext("ocr_transit_signature", traceId)
                } finally {
                    screenshot.recycle()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                diagnostics.recordFailure(
                    "alipay_transit_ocr_failed",
                    traceId,
                    BillSyncSource.Alipay,
                    null,
                    error
                )
            } finally {
                transitJob = null
            }
        }
    }

    private fun currentTransitRoot(packageName: String): AccessibilityNodeInfo? {
        if (!host.monitoringState.enabled || !host.currentPermissionHealth().isHealthy || !host.isScreenReady()) {
            return null
        }
        val root = host.currentRoot ?: return null
        if (root.packageName?.toString() != packageName || !host.isApplicationWindow(root.windowId)) {
            return null
        }
        return root.takeIf {
            shouldAttemptAlipayTransitOcrFallback(
                packageName = packageName,
                pageText = root.collectVisibleText(),
                sdkInt = Build.VERSION.SDK_INT,
                isApplicationWindow = true
            )
        }
    }

    private fun recordMetroExitContext(
        reason: String,
        traceId: String = newDiagnosticTraceId()
    ) {
        scope.launch {
            runCatching { processor().recordAlipayMetroExitContext() }
                .onSuccess { enrichedExistingNotification ->
                    diagnostics.recordMetadata(
                        event = "alipay_transit_context_recorded",
                        outcome = "success",
                        reason = if (enrichedExistingNotification) "recent_notification_enriched" else reason,
                        traceId = traceId,
                        source = DiagnosticSource.Alipay,
                        component = DiagnosticComponent.Ocr
                    )
                }
                .onFailure { error ->
                    diagnostics.recordFailure(
                        "alipay_transit_context_failed",
                        traceId,
                        BillSyncSource.Alipay,
                        null,
                        error
                    )
                }
        }
    }

    private fun recordTransitRejection(reason: String, traceId: String) {
        diagnostics.recordMetadata(
            "alipay_transit_ocr_rejected",
            "rejected",
            reason,
            traceId,
            source = DiagnosticSource.Alipay,
            component = DiagnosticComponent.Ocr
        )
    }

    private companion object {
        const val AUTOMATIC_CAPTURE_SETTLE_MILLIS = 500L
        const val RETRY_SETTLE_MILLIS = 1_500L
        const val OCR_ATTEMPT_COOLDOWN_MILLIS = 3_000L
        const val ALIPAY_PAYMENT_FLOW_WINDOW_MILLIS = 2 * 60_000L
        const val ALIPAY_RESULT_PROBE_WINDOW_MILLIS = 10_000L
    }
}

private fun String.toWindowContextPayload(): DiagnosticSensitivePayload =
    takeIf(String::isNotBlank)
        ?.let {
            DiagnosticSensitivePayload(mapOf(DiagnosticSensitiveField.WindowContext to it))
        }
        ?: DiagnosticSensitivePayload()

private fun PaymentTextEvidence.toAccessibilityReviewEvidence(): String =
    reviewEvidenceText(ACCESSIBILITY_EVIDENCE_LABEL, text)

private fun PaymentTextEvidence?.toOcrReviewEvidence(): String = this?.text
    ?.takeIf(String::isNotBlank)
    ?.let { reviewEvidenceText(OCR_EVIDENCE_LABEL, it) }
    .orEmpty()
