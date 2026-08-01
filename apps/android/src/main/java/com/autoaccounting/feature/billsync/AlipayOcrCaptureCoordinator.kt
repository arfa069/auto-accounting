package com.autoaccounting.feature.billsync

import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.autoaccounting.feature.capture.BookkeepingResultNotifier
import com.autoaccounting.feature.capture.toBookkeepingResultNotification
import com.autoaccounting.feature.diagnostics.DiagnosticComponent
import com.autoaccounting.feature.diagnostics.DiagnosticSource
import com.autoaccounting.feature.diagnostics.newDiagnosticTraceId
import com.autoaccounting.feature.monitoring.ContinuousMonitoringEvent
import com.autoaccounting.feature.monitoring.PaymentScreenCaptureDebouncer
import com.autoaccounting.feature.monitoring.decideContinuousMonitoringCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Payment-result and transit-exit capture share one lifecycle and cancellation boundary.
@Suppress("LongParameterList", "TooManyFunctions")
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
    private var lastTransitAttemptAtElapsedMillis: Long = 0L
    private var lastPaymentAttemptAtElapsedMillis: Long = 0L
    private var paymentFlowObservedAtElapsedMillis: Long = 0L
    private var transitSurfaceInspected: Boolean = false
    private var paymentSurfaceInspected: Boolean = false
    private var paymentSurfaceFingerprint: Int? = null

    fun observePaymentFlow(
        packageName: String,
        pageText: String,
        shouldConsiderContinuousMonitoring: Boolean
    ) {
        if (!shouldConsiderContinuousMonitoring) return
        if (packageName == BillSyncSource.Alipay.packageName) {
            if (isAlipayPaymentInitiationPage(pageText)) {
                paymentFlowObservedAtElapsedMillis = SystemClock.elapsedRealtime()
            }
        } else {
            resetPaymentState()
        }
    }

    fun handleSurface(
        packageName: String,
        pageText: String,
        shouldConsiderContinuousMonitoring: Boolean,
        activeRoot: AccessibilityNodeInfo?,
        isWindowStateChanged: Boolean
    ): Boolean {
        if (handleTransitSurface(packageName, pageText, shouldConsiderContinuousMonitoring, activeRoot)) {
            return true
        }
        return handlePaymentSurface(
            packageName,
            pageText,
            shouldConsiderContinuousMonitoring,
            activeRoot,
            isWindowStateChanged
        )
    }

    fun cancel() {
        transitJob?.cancel()
        transitJob = null
        paymentJob?.cancel()
        paymentJob = null
        paymentFlowObservedAtElapsedMillis = 0L
        transitSurfaceInspected = false
        paymentSurfaceInspected = false
        paymentSurfaceFingerprint = null
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

    private fun handlePaymentSurface(
        packageName: String,
        pageText: String,
        shouldConsiderContinuousMonitoring: Boolean,
        activeRoot: AccessibilityNodeInfo?,
        isWindowStateChanged: Boolean
    ): Boolean {
        if (packageName != BillSyncSource.Alipay.packageName) {
            resetPaymentState()
            return false
        }
        if (
            !shouldConsiderContinuousMonitoring ||
            activeRoot == null ||
            activeRoot.packageName?.toString() != packageName
        ) {
            resetPaymentState()
            return false
        }

        val windowId = activeRoot.windowId
        val isApplicationWindow = host.isApplicationWindow(windowId)
        val hasRecentPaymentFlow = hasRecentPaymentFlow()
        val shouldAttempt = shouldAttemptAlipayOcrFallback(
            AlipayOcrFallbackRequest(
                packageName = packageName,
                pageText = pageText,
                sdkInt = Build.VERSION.SDK_INT,
                isApplicationWindow = isApplicationWindow,
                isWindowStateChanged = isWindowStateChanged,
                hasRecentPaymentFlow = hasRecentPaymentFlow,
                accessibilityNeedsOcr = accessibilityNeedsOcr(pageText)
            )
        )
        if (!shouldAttempt) {
            if (!hasRecentPaymentFlow && pageText.isNotBlank()) {
                paymentSurfaceInspected = false
                paymentSurfaceFingerprint = null
            }
            return false
        }

        val surfaceFingerprint = 31 * windowId + pageText.hashCode()
        if (surfaceFingerprint != paymentSurfaceFingerprint) {
            paymentSurfaceInspected = false
            paymentSurfaceFingerprint = surfaceFingerprint
        }
        if (paymentSurfaceInspected || paymentJob?.isActive == true) return true

        paymentSurfaceInspected = true
        capturePaymentFallback(packageName)
        return true
    }

    private fun accessibilityNeedsOcr(pageText: String): Boolean {
        val parsedEntry = BillPageParser().parse(
            source = BillSyncSource.Alipay,
            pageText = pageText,
            fallbackTransactionTimeText = ALIPAY_OCR_FALLBACK_TRANSACTION_TIME
        ).singleOrNull() ?: return true
        return parsedEntry.merchantTitleFromFallback || parsedEntry.fundingAccountFromFallback
    }

    private fun hasRecentPaymentFlow(): Boolean {
        val ageMillis = SystemClock.elapsedRealtime() - paymentFlowObservedAtElapsedMillis
        return paymentFlowObservedAtElapsedMillis > 0L &&
            ageMillis in 0..ALIPAY_PAYMENT_FLOW_WINDOW_MILLIS
    }

    private fun resetPaymentState() {
        paymentFlowObservedAtElapsedMillis = 0L
        resetPaymentSurface()
        paymentJob?.cancel()
        paymentJob = null
    }

    private fun resetPaymentSurface() {
        paymentSurfaceInspected = false
        paymentSurfaceFingerprint = null
    }

    private fun capturePaymentFallback(packageName: String) {
        if (!host.isScreenReady()) {
            recordPaymentRejection("screen_off_or_locked")
            resetPaymentSurface()
            return
        }
        if (paymentJob?.isActive == true) return
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        if (nowElapsedMillis - lastPaymentAttemptAtElapsedMillis < OCR_ATTEMPT_COOLDOWN_MILLIS) {
            recordPaymentRejection("cooldown")
            resetPaymentSurface()
            return
        }
        lastPaymentAttemptAtElapsedMillis = nowElapsedMillis
        val traceId = newDiagnosticTraceId()
        diagnostics.recordMetadata(
            event = "alipay_ocr_started",
            outcome = "started",
            reason = "payment_result_accessibility_incomplete",
            traceId = traceId,
            source = DiagnosticSource.Alipay,
            component = DiagnosticComponent.Ocr
        )

        paymentJob = scope.launch {
            var processed = false
            try {
                delay(AUTOMATIC_CAPTURE_SETTLE_MILLIS)
                val initialRoot = currentPaymentRoot(packageName)
                if (initialRoot == null) {
                    recordPaymentRejection("settled_context_invalid", traceId)
                    return@launch
                }
                val windowId = initialRoot.windowId
                val screenshot = host.captureScreenBitmap(windowId)
                if (screenshot == null) {
                    recordPaymentRejection("screenshot_unavailable", traceId)
                    return@launch
                }
                try {
                    val currentRoot = currentPaymentRoot(packageName)
                    if (currentRoot == null || currentRoot.windowId != windowId) {
                        recordPaymentRejection("window_changed_before_ocr", traceId)
                        return@launch
                    }
                    processed = processPaymentResult(
                        packageName = packageName,
                        pageText = host.recognizeScreen(screenshot),
                        traceId = traceId,
                        allowRecentPaymentContext = hasRecentPaymentFlow()
                    )
                } finally {
                    screenshot.recycle()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                diagnostics.recordFailure("alipay_ocr_failed", traceId, BillSyncSource.Alipay, null, error)
            } finally {
                if (!processed) resetPaymentSurface()
                paymentJob = null
            }
        }
    }

    private fun currentPaymentRoot(packageName: String): AccessibilityNodeInfo? {
        if (!host.monitoringState.enabled || !host.currentPermissionHealth().isHealthy || !host.isScreenReady()) {
            return null
        }
        val root = host.currentRoot ?: return null
        if (root.packageName?.toString() != packageName || !host.isApplicationWindow(root.windowId)) {
            return null
        }
        val pageText = root.collectVisibleText()
        return root.takeIf {
            shouldAttemptAlipayOcrFallback(
                AlipayOcrFallbackRequest(
                    packageName = packageName,
                    pageText = pageText,
                    sdkInt = Build.VERSION.SDK_INT,
                    isApplicationWindow = true,
                    isWindowStateChanged = true,
                    hasRecentPaymentFlow = hasRecentPaymentFlow(),
                    accessibilityNeedsOcr = accessibilityNeedsOcr(pageText)
                )
            )
        }
    }

    private suspend fun processPaymentResult(
        packageName: String,
        pageText: String,
        traceId: String,
        allowRecentPaymentContext: Boolean
    ): Boolean {
        val ocrDecision = decideAlipayOcrCapture(
            pageText = pageText,
            allowRecentPaymentContext = allowRecentPaymentContext
        )
        if (!ocrDecision.shouldCapture) {
            recordPaymentRejection(ocrDecision.rejectionReason?.name ?: "unknown_rejection", traceId)
            return false
        }
        val permissionHealth = host.currentPermissionHealth()
        if (!host.monitoringState.enabled || !permissionHealth.isHealthy) {
            recordPaymentRejection("monitoring_blocked", traceId)
            return false
        }
        val decision = decideContinuousMonitoringCapture(
            state = host.monitoringState,
            event = ContinuousMonitoringEvent(packageName, pageText),
            permissionHealth = permissionHealth
        )
        if (!decision.shouldCapture) {
            recordPaymentRejection(decision.observation.name, traceId)
            return false
        }
        if (!automaticCaptureDebouncer.shouldProcess(packageName, pageText)) {
            recordPaymentRejection("debounced", traceId)
            return false
        }

        val source = BillSyncSource.Alipay
        val outcome = runCatching {
            processor().processAutomatic(
                source = source,
                pageText = pageText,
                retainRawEvidence = false,
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
        val processed = outcome.isSuccess && outcome.getOrNull()?.errorMessage == null
        if (processed) paymentFlowObservedAtElapsedMillis = 0L
        return processed
    }

    private fun recordPaymentRejection(reason: String, traceId: String? = null) {
        diagnostics.recordMetadata(
            event = "alipay_ocr_rejected",
            outcome = "rejected",
            reason = reason,
            traceId = traceId ?: newDiagnosticTraceId(),
            source = DiagnosticSource.Alipay,
            component = DiagnosticComponent.Ocr
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
        const val OCR_ATTEMPT_COOLDOWN_MILLIS = 3_000L
        const val ALIPAY_PAYMENT_FLOW_WINDOW_MILLIS = 2 * 60_000L
    }
}
