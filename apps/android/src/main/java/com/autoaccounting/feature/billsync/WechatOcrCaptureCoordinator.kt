package com.autoaccounting.feature.billsync

import android.os.Build
import android.os.SystemClock
import com.autoaccounting.feature.capture.BookkeepingResultNotificationOrigin
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

@Suppress("LongParameterList")
internal class WechatOcrCaptureCoordinator(
    private val scope: CoroutineScope,
    private val host: AccessibilityCaptureHost,
    private val processor: () -> BillSyncCaptureProcessor,
    private val resultNotifier: () -> BookkeepingResultNotifier,
    private val diagnostics: BillSyncDiagnosticRecorder,
    private val automaticCaptureDebouncer: PaymentScreenCaptureDebouncer
) {
    private var captureJob: Job? = null
    private var guardResetJob: Job? = null
    private val sessionGuard = PaymentScreenOcrSessionGuard()
    private var lastAutomaticAttemptAtElapsedMillis: Long = 0L
    private var lastManualAttemptAtElapsedMillis: Long = 0L
    private var activeWindowIdentity: WechatWindowIdentity? = null

    fun updateActiveWindowIdentity(identity: WechatWindowIdentity?) {
        activeWindowIdentity = identity
    }

    fun windowIdentityFor(windowId: Int?): WechatWindowIdentity? =
        activeWindowIdentity?.takeIf { identity -> identity.windowId == windowId }

    @Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod")
    fun captureManual(packageName: String) {
        val sessionId = BillSyncSessions.controller.state.value.sessionId
        if (!host.isScreenReady()) {
            reject("manual_ocr_rejected", "screen_off_or_locked", packageName, sessionId = sessionId)
            return
        }
        if (captureJob?.isActive == true) {
            reject("manual_ocr_rejected", "ocr_job_active", packageName, sessionId = sessionId)
            return
        }
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        if (nowElapsedMillis - lastManualAttemptAtElapsedMillis < MANUAL_OCR_ATTEMPT_COOLDOWN_MILLIS) {
            reject("manual_ocr_rejected", "cooldown", packageName, sessionId = sessionId)
            return
        }
        lastManualAttemptAtElapsedMillis = nowElapsedMillis
        val traceId = newDiagnosticTraceId()
        diagnostics.recordMetadata(
            event = "manual_ocr_started",
            outcome = "started",
            reason = "manual_session",
            traceId = traceId,
            sessionId = sessionId,
            source = packageName.accessibilityDiagnosticSource(),
            component = DiagnosticComponent.Ocr
        )

        captureJob = scope.launch {
            try {
                delay(AUTOMATIC_CAPTURE_SETTLE_MILLIS)
                val activeRoot = host.currentRoot
                if (activeRoot == null) {
                    reject("manual_ocr_rejected", "active_window_unavailable", packageName, traceId, sessionId)
                    return@launch
                }
                if (
                    activeRoot.packageName?.toString() != packageName ||
                    !BillSyncSessions.controller.acceptsManualOcr(packageName) ||
                    !host.isScreenReady()
                ) {
                    reject("manual_ocr_rejected", "settled_context_invalid", packageName, traceId, sessionId)
                    return@launch
                }

                val windowId = activeRoot.windowId
                val windowEvidence = host.currentWechatWindowEvidence(
                    windowId,
                    windowIdentityFor(windowId)
                )
                if (
                    !shouldAttemptManualWechatOcrFallback(
                        packageName = packageName,
                        pageText = activeRoot.collectVisibleText(),
                        sdkInt = Build.VERSION.SDK_INT,
                        windowEvidence = windowEvidence
                    )
                ) {
                    reject("manual_ocr_rejected", "window_verification_failed", packageName, traceId, sessionId)
                    return@launch
                }

                val screenshot = host.captureScreenBitmap(windowId)
                if (screenshot == null) {
                    reject("manual_ocr_rejected", "screenshot_unavailable", packageName, traceId, sessionId)
                    return@launch
                }
                try {
                    val currentRoot = host.currentRoot
                    val currentWindowEvidence = host.currentWechatWindowEvidence(
                        windowId,
                        windowIdentityFor(windowId)
                    )
                    if (
                        currentRoot == null ||
                        currentRoot.packageName?.toString() != packageName ||
                        currentRoot.windowId != windowId ||
                        !BillSyncSessions.controller.acceptsManualOcr(packageName) ||
                        !host.isScreenReady() ||
                        !shouldAttemptManualWechatOcrFallback(
                            packageName = packageName,
                            pageText = currentRoot.collectVisibleText(),
                            sdkInt = Build.VERSION.SDK_INT,
                            windowEvidence = currentWindowEvidence
                        )
                    ) {
                        reject("manual_ocr_rejected", "window_changed_before_ocr", packageName, traceId, sessionId)
                        return@launch
                    }

                    val preparedPageText = prepareManualWechatOcrResultText(
                        host.recognizeScreen(screenshot)
                    )
                    if (preparedPageText == null) {
                        reject("manual_ocr_rejected", "ocr_output_unusable", packageName, traceId, sessionId)
                        return@launch
                    }
                    var processedResult: BillSyncResult? = null
                    val completed = BillSyncSessions.controller.submitBillPage(
                        packageName = packageName,
                        pageText = preparedPageText,
                        process = { billSource, text ->
                            processor().processManualOcr(
                                billSource,
                                text,
                                traceId,
                                sessionId
                            ).also { processedResult = it }
                        }
                    )
                    if (completed) {
                        processedResult?.toBookkeepingResultNotification(
                            sourceLabel = BillSyncSource.WeChat.label,
                            origin = BookkeepingResultNotificationOrigin.ManualImport
                        )?.let { notification ->
                            diagnostics.recordMetadata(
                                "result_notification_requested",
                                "requested",
                                notification.javaClass.simpleName.ifBlank { "bookkeeping_result" },
                                traceId = traceId,
                                sessionId = sessionId,
                                source = DiagnosticSource.WeChat
                            )
                            resultNotifier().notify(notification)
                        }
                    }
                } finally {
                    screenshot.recycle()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                diagnostics.recordFailure("manual_ocr_failed", traceId, BillSyncSource.WeChat, sessionId, error)
            } finally {
                captureJob = null
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun captureAutomatic(packageName: String) {
        guardResetJob?.cancel()
        guardResetJob = null
        if (!host.isScreenReady()) {
            reject("automatic_ocr_rejected", "screen_off_or_locked", packageName)
            return
        }
        if (captureJob?.isActive == true) {
            reject("automatic_ocr_rejected", "ocr_job_active", packageName)
            return
        }
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        if (nowElapsedMillis - lastAutomaticAttemptAtElapsedMillis < OCR_ATTEMPT_COOLDOWN_MILLIS) {
            reject("automatic_ocr_rejected", "cooldown", packageName)
            return
        }
        lastAutomaticAttemptAtElapsedMillis = nowElapsedMillis
        val traceId = newDiagnosticTraceId()
        diagnostics.recordMetadata(
            event = "automatic_ocr_started",
            outcome = "started",
            reason = "verified_window_candidate",
            traceId = traceId,
            source = packageName.accessibilityDiagnosticSource(),
            component = DiagnosticComponent.Ocr
        )

        captureJob = scope.launch {
            try {
                delay(AUTOMATIC_CAPTURE_SETTLE_MILLIS)
                val activeRoot = host.currentRoot
                if (activeRoot?.packageName?.toString() != packageName) {
                    reject("automatic_ocr_rejected", "active_window_changed", packageName, traceId)
                    return@launch
                }
                if (!host.isScreenReady()) {
                    reject("automatic_ocr_rejected", "screen_off_or_locked_after_wait", packageName, traceId)
                    return@launch
                }

                val permissionHealth = host.currentPermissionHealth()
                if (!host.monitoringState.enabled || !permissionHealth.isHealthy) {
                    reject("automatic_ocr_rejected", "monitoring_blocked_after_wait", packageName, traceId)
                    return@launch
                }

                val windowId = activeRoot.windowId
                val windowEvidence = host.currentWechatWindowEvidence(
                    windowId,
                    windowIdentityFor(windowId)
                )
                val nodePageText = activeRoot.collectVisibleText()
                val hasRecentPaymentNotification = !windowEvidence.isApplicationWindow &&
                    processor().hasRecentWechatNotificationCaptureCandidate()
                if (
                    !shouldAttemptWechatOcrFallback(
                        packageName = packageName,
                        pageText = nodePageText,
                        sdkInt = Build.VERSION.SDK_INT,
                        windowEvidence = windowEvidence,
                        hasRecentPaymentNotification = hasRecentPaymentNotification
                    )
                ) {
                    reject("automatic_ocr_rejected", "window_verification_failed", packageName, traceId)
                    return@launch
                }
                val screenshot = host.captureScreenBitmap(windowId)
                if (screenshot == null) {
                    reject("automatic_ocr_rejected", "screenshot_unavailable", packageName, traceId)
                    return@launch
                }
                try {
                    val currentRoot = host.currentRoot
                    if (
                        currentRoot == null ||
                        currentRoot.packageName?.toString() != packageName ||
                        currentRoot.windowId != windowId
                    ) {
                        reject("automatic_ocr_rejected", "window_changed_before_ocr", packageName, traceId)
                        return@launch
                    }
                    if (!host.isScreenReady()) {
                        reject("automatic_ocr_rejected", "screen_off_or_locked_before_ocr", packageName, traceId)
                        return@launch
                    }
                    val currentWindowEvidence = host.currentWechatWindowEvidence(
                        windowId,
                        windowIdentityFor(windowId)
                    )
                    val currentHasRecentPaymentNotification =
                        !currentWindowEvidence.isApplicationWindow &&
                            processor().hasRecentWechatNotificationCaptureCandidate()
                    if (
                        !shouldAttemptWechatOcrFallback(
                            packageName = packageName,
                            pageText = currentRoot.collectVisibleText(),
                            sdkInt = Build.VERSION.SDK_INT,
                            windowEvidence = currentWindowEvidence,
                            hasRecentPaymentNotification = currentHasRecentPaymentNotification
                        )
                    ) {
                        reject("automatic_ocr_rejected", "window_reverification_failed", packageName, traceId)
                        return@launch
                    }
                    processAutomaticResult(
                        packageName,
                        host.recognizeScreen(screenshot),
                        currentWindowEvidence,
                        traceId
                    )
                } finally {
                    screenshot.recycle()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                diagnostics.recordFailure("automatic_ocr_failed", traceId, BillSyncSource.WeChat, null, error)
            } finally {
                captureJob = null
            }
        }
    }

    fun scheduleGuardReset(packageName: String) {
        if (guardResetJob?.isActive == true) return
        guardResetJob = scope.launch {
            delay(OCR_SESSION_RESET_SETTLE_MILLIS)
            val settledRoot = host.currentRoot
            if (
                settledRoot?.packageName?.toString() == packageName &&
                settledRoot.collectVisibleText().isNotBlank()
            ) {
                sessionGuard.resetCurrentFingerprint()
            }
            guardResetJob = null
        }
    }

    fun cancel() {
        captureJob?.cancel()
        captureJob = null
        guardResetJob?.cancel()
        guardResetJob = null
        sessionGuard.resetCurrentFingerprint()
        activeWindowIdentity = null
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private suspend fun processAutomaticResult(
        packageName: String,
        pageText: String,
        windowEvidence: WechatWindowEvidence,
        traceId: String
    ): Boolean {
        if (pageText.isBlank()) {
            reject("ocr_output_rejected", "blank_ocr_text", packageName, traceId)
            return false
        }
        val ocrDecision = decideWechatOcrCapture(pageText, windowEvidence)
        if (!ocrDecision.shouldCapture) {
            reject(
                "ocr_output_rejected",
                ocrDecision.rejectionReason?.name ?: "unknown_rejection",
                packageName,
                traceId
            )
            return false
        }
        val transactionFingerprint = wechatOcrPaymentFingerprint(pageText)
        if (transactionFingerprint == null) {
            reject("ocr_output_rejected", "payment_fingerprint_missing", packageName, traceId)
            return false
        }
        val hasNewMatchingNotification = transactionFingerprint.isRedPacket &&
            processor().hasUniqueUnlinkedRecentWechatNotification(transactionFingerprint)
        if (!sessionGuard.shouldProcess(transactionFingerprint, hasNewMatchingNotification)) {
            reject("ocr_output_rejected", "session_duplicate", packageName, traceId)
            return false
        }
        val permissionHealth = host.currentPermissionHealth()
        if (!host.monitoringState.enabled || !permissionHealth.isHealthy) {
            reject("ocr_output_rejected", "monitoring_blocked", packageName, traceId)
            return false
        }
        val decision = decideContinuousMonitoringCapture(
            state = host.monitoringState,
            event = ContinuousMonitoringEvent(packageName, pageText),
            permissionHealth = permissionHealth
        )
        if (!decision.shouldCapture) {
            reject("ocr_output_rejected", decision.observation.name, packageName, traceId)
            return false
        }
        if (
            !automaticCaptureDebouncer.shouldProcess(
                packageName,
                pageText,
                bypassDuplicateWindow = hasNewMatchingNotification
            )
        ) {
            reject("ocr_output_rejected", "debounced", packageName, traceId)
            return false
        }

        val source = BillSyncSource.fromPackageName(packageName) ?: return false
        val outcome = runCatching {
            processor().processAutomatic(
                source = source,
                pageText = pageText,
                retainRawEvidence = false,
                automaticCaptureVerification = if (hasNewMatchingNotification) {
                    AutomaticCaptureVerification.RequireRecentNotification
                } else {
                    ocrDecision.verification
                },
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
            diagnostics.recordFailure("automatic_ocr_processor_failed", traceId, source, null, error)
        }
        val processed = outcome.isSuccess && outcome.getOrNull()?.errorMessage == null
        if (processed) sessionGuard.markProcessed(transactionFingerprint)
        return processed
    }

    private fun reject(
        event: String,
        reason: String,
        packageName: String,
        traceId: String = newDiagnosticTraceId(),
        sessionId: Long? = null
    ) {
        diagnostics.recordMetadata(
            event = event,
            outcome = "rejected",
            reason = reason,
            traceId = traceId,
            sessionId = sessionId,
            source = packageName.accessibilityDiagnosticSource(),
            component = DiagnosticComponent.Ocr
        )
    }

    private companion object {
        const val AUTOMATIC_CAPTURE_SETTLE_MILLIS = 500L
        const val OCR_ATTEMPT_COOLDOWN_MILLIS = 3_000L
        const val MANUAL_OCR_ATTEMPT_COOLDOWN_MILLIS = 1_000L
        const val OCR_SESSION_RESET_SETTLE_MILLIS = 3_000L
    }
}
