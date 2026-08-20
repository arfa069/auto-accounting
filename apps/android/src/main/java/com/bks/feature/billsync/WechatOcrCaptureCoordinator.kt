package com.bks.feature.billsync

import android.os.Build
import android.os.SystemClock
import com.bks.feature.diagnostics.DiagnosticComponent
import com.bks.feature.diagnostics.DiagnosticSource
import com.bks.feature.diagnostics.newDiagnosticTraceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class WechatOcrCaptureCoordinator(
    private val scope: CoroutineScope,
    private val host: AccessibilityCaptureHost,
    private val processor: () -> BillSyncCaptureProcessor,
    private val diagnostics: BillSyncDiagnosticRecorder
) {
    private var captureJob: Job? = null
    private var lastAttemptAtElapsedMillis: Long = 0L
    private var activeWindowIdentity: WechatWindowIdentity? = null

    fun updateActiveWindowIdentity(identity: WechatWindowIdentity?) {
        activeWindowIdentity = identity
    }

    fun windowIdentityFor(windowId: Int?): WechatWindowIdentity? =
        activeWindowIdentity?.takeIf { identity -> identity.windowId == windowId }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
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
        if (nowElapsedMillis - lastAttemptAtElapsedMillis < MANUAL_OCR_ATTEMPT_COOLDOWN_MILLIS) {
            reject("manual_ocr_rejected", "cooldown", packageName, sessionId = sessionId)
            return
        }
        lastAttemptAtElapsedMillis = nowElapsedMillis
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
                delay(MANUAL_OCR_SETTLE_MILLIS)
                val initialRoot = host.currentRoot ?: run {
                    reject("manual_ocr_rejected", "settled_context_invalid", packageName, traceId, sessionId)
                    return@launch
                }
                if (!isValidManualContext(initialRoot, packageName)) {
                    reject("manual_ocr_rejected", "settled_context_invalid", packageName, traceId, sessionId)
                    return@launch
                }
                val windowId = initialRoot.windowId
                val initialEvidence = host.currentWechatWindowEvidence(
                    windowId,
                    windowIdentityFor(windowId)
                )
                if (!shouldAttemptManualWechatOcrFallback(
                        packageName = packageName,
                        pageText = initialRoot.collectVisibleText(),
                        sdkInt = Build.VERSION.SDK_INT,
                        windowEvidence = initialEvidence
                    )
                ) {
                    reject("manual_ocr_rejected", "window_verification_failed", packageName, traceId, sessionId)
                    return@launch
                }

                val screenshot = host.captureScreenBitmap(windowId, traceId)
                if (screenshot == null) {
                    reject("manual_ocr_rejected", "screenshot_unavailable", packageName, traceId, sessionId)
                    return@launch
                }
                try {
                    val currentRoot = host.currentRoot ?: run {
                        reject("manual_ocr_rejected", "window_changed_before_ocr", packageName, traceId, sessionId)
                        return@launch
                    }
                    if (!isValidManualContext(currentRoot, packageName) || currentRoot.windowId != windowId) {
                        reject("manual_ocr_rejected", "window_changed_before_ocr", packageName, traceId, sessionId)
                        return@launch
                    }
                    val currentEvidence = host.currentWechatWindowEvidence(
                        windowId,
                        windowIdentityFor(windowId)
                    )
                    if (!shouldAttemptManualWechatOcrFallback(
                            packageName = packageName,
                            pageText = currentRoot.collectVisibleText(),
                            sdkInt = Build.VERSION.SDK_INT,
                            windowEvidence = currentEvidence
                        )
                    ) {
                        reject("manual_ocr_rejected", "window_reverification_failed", packageName, traceId, sessionId)
                        return@launch
                    }

                    val preparedPageText = prepareManualWechatOcrResultText(
                        host.recognizeScreen(screenshot)
                    )
                    if (preparedPageText == null) {
                        reject("manual_ocr_rejected", "ocr_output_unusable", packageName, traceId, sessionId)
                        return@launch
                    }
                    BillSyncSessions.controller.submitBillPage(
                        packageName = packageName,
                        pageText = preparedPageText,
                        process = { billSource, text ->
                            processor().processManualOcr(
                                source = billSource,
                                pageText = text,
                                traceId = traceId,
                                sessionId = sessionId
                            )
                        }
                    )
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

    fun cancel() {
        captureJob?.cancel()
        captureJob = null
        activeWindowIdentity = null
    }

    private fun isValidManualContext(
        root: android.view.accessibility.AccessibilityNodeInfo?,
        packageName: String
    ): Boolean = root?.packageName?.toString() == packageName &&
        BillSyncSessions.controller.acceptsManualOcr(packageName) &&
        host.isScreenReady()

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
        const val MANUAL_OCR_SETTLE_MILLIS = 500L
        const val MANUAL_OCR_ATTEMPT_COOLDOWN_MILLIS = 1_000L
    }
}
