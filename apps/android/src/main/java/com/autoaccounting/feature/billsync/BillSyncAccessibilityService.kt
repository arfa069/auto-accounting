package com.autoaccounting.feature.billsync

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.autoaccounting.data.local.AutoAccountingDatabaseProvider
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.diagnostics.DiagnosticComponent
import com.autoaccounting.feature.diagnostics.DiagnosticLogs
import com.autoaccounting.feature.diagnostics.DiagnosticSensitiveField
import com.autoaccounting.feature.diagnostics.DiagnosticSensitivePayload
import com.autoaccounting.feature.diagnostics.DiagnosticSource
import com.autoaccounting.feature.diagnostics.newDiagnosticTraceId
import com.autoaccounting.feature.review.ReviewQueuePersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class BillSyncAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val database by lazy { AutoAccountingDatabaseProvider.get(this) }
    private val preferencesRepository by lazy { LocalPreferencesRepository(database) }
    private val processor by lazy {
        BillSyncCaptureProcessor(
            pipeline = BillSyncPipeline(),
            reviewQueuePersistence = ReviewQueuePersistence(LocalLedgerRepository(database)),
            preferencesRepository = preferencesRepository,
            diagnosticRecorder = diagnostics
        )
    }
    private val diagnostics by lazy { DiagnosticLogs.get(this) }
    private val diagnosticRecorder by lazy { BillSyncDiagnosticRecorder(diagnostics) }
    private val ocrRecognizerDelegate = lazy { PaymentScreenOcrRecognizer() }
    private val ocrRecognizer by ocrRecognizerDelegate
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val captureHost by lazy {
        object : AccessibilityCaptureHost {
            override val currentRoot: AccessibilityNodeInfo?
                get() = rootInActiveWindow

            override fun isScreenReady(): Boolean =
                isScreenReadyForWechatOcr(powerManager.isInteractive, keyguardManager.isKeyguardLocked)

            override fun currentWechatWindowEvidence(
                windowId: Int,
                windowIdentity: WechatWindowIdentity?
            ): WechatWindowEvidence =
                this@BillSyncAccessibilityService.currentWechatWindowEvidence(windowId, windowIdentity)

            override suspend fun captureScreenBitmap(windowId: Int, traceId: String?): Bitmap? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    this@BillSyncAccessibilityService.captureScreenBitmapApi30(windowId, traceId)
                } else {
                    null
                }

            override suspend fun recognizeScreen(bitmap: Bitmap): String =
                ocrRecognizer.recognize(bitmap)
        }
    }
    private val wechatOcrCoordinator by lazy {
        WechatOcrCaptureCoordinator(
            scope = serviceScope,
            host = captureHost,
            processor = { processor },
            diagnostics = diagnosticRecorder
        )
    }
    private val captureRouter by lazy {
        AccessibilityCaptureRouter(onManualWechatOcr = wechatOcrCoordinator::captureManual)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        BillSyncServiceHealth.markServiceConnected(this, true)
        diagnosticRecorder.recordMetadata("service_connected", "connected", "service_connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (captureRouter.captureRoute(packageName) != AccessibilityCaptureRoute.ManualBillSync) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            packageName == BillSyncSource.WeChat.packageName
        ) {
            val activityClassName = event.className?.toString() ?: return
            wechatOcrCoordinator.updateActiveWindowIdentity(
                WechatWindowIdentity(event.windowId, activityClassName)
            )
        }

        val activeWindowRoot = rootInActiveWindow
        val eventSourceRoot = event.source
        val activeRoot = activeWindowRoot
            ?.takeIf { it.packageName?.toString() == packageName }
            ?: eventSourceRoot?.takeIf { it.packageName?.toString() == packageName }
        val pageText = activeRoot?.collectVisibleText().orEmpty()
        val windowEvidence = activeRoot
            ?.takeIf { packageName == BillSyncSource.WeChat.packageName }
            ?.let { root ->
                wechatOcrCoordinator.windowIdentityFor(root.windowId)?.let { identity ->
                    currentWechatWindowEvidence(root.windowId, identity)
                } ?: currentWechatWindowEvidence(root.windowId, null)
            }

        if (
            captureRouter.handleWechatCaptureRoute(
                packageName = packageName,
                pageText = pageText,
                windowEvidence = windowEvidence
            )
        ) return

        if (pageText.isBlank()) {
            diagnosticRecorder.recordMetadata(
                event = "manual_page_rejected",
                outcome = "rejected",
                reason = "blank_visible_text",
                source = packageName.accessibilityDiagnosticSource(),
                sensitivePayload = DiagnosticSensitivePayload(
                    mapOf(
                        DiagnosticSensitiveField.WindowContext to accessibilityWindowContext(
                            event = event,
                            activeWindowRoot = activeWindowRoot,
                            eventSourceRoot = eventSourceRoot,
                            selectedRootSource = if (activeRoot == activeWindowRoot) {
                                "rootInActiveWindow"
                            } else if (activeRoot == eventSourceRoot) {
                                "event.source"
                            } else {
                                "none"
                            }
                        )
                    )
                )
            )
            return
        }

        captureManualBillSync(packageName, pageText)
    }

    private fun captureManualBillSync(
        packageName: String,
        pageText: String
    ) {
        val source = BillSyncSource.fromPackageName(packageName) ?: return
        val traceId = newDiagnosticTraceId()
        val sessionId = BillSyncSessions.controller.state.value.sessionId
        serviceScope.launch {
            val observation = withContext(Dispatchers.Default) {
                observeBillSyncPage(source, pageText)
            }
            if (observation == BillSyncPageObservation.Ignored) {
                diagnosticRecorder.recordMetadata(
                    event = "manual_page_rejected",
                    outcome = "rejected",
                    reason = "unrelated_page",
                    source = source.accessibilityDiagnosticSource()
                )
                return@launch
            }
            runCatching {
                BillSyncSessions.controller.submitBillPage(
                    packageName = packageName,
                    pageText = pageText,
                    process = { billSource, text ->
                        withContext(Dispatchers.IO) {
                            processor.process(
                                source = billSource,
                                pageText = text,
                                traceId = traceId,
                                sessionId = sessionId
                            )
                        }
                    }
                )
            }.onFailure { error ->
                BillSyncSessions.controller.fail(error.message ?: "补录失败")
                diagnosticRecorder.recordFailure("manual_capture_failed", traceId, source, sessionId, error)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreenBitmapApi30(windowId: Int, traceId: String?): Bitmap? =
        suspendCoroutine { continuation ->
            val callback = object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val softwareBitmap = try {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                            hardwareBuffer,
                            screenshot.colorSpace
                        )
                        try {
                            hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            hardwareBitmap?.recycle()
                        }
                    } finally {
                        hardwareBuffer.close()
                    }
                    continuation.resume(softwareBitmap)
                }

                override fun onFailure(errorCode: Int) {
                    diagnosticRecorder.recordMetadata(
                        event = "screenshot_failed",
                        outcome = "rejected",
                        reason = "window:errorCode=$errorCode",
                        traceId = traceId ?: newDiagnosticTraceId(),
                        component = DiagnosticComponent.Ocr
                    )
                    continuation.resume(null)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                takeScreenshotOfWindow(windowId, mainExecutor, callback)
            } else {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
            }
        }

    private fun currentWechatWindowEvidence(
        windowId: Int,
        windowIdentity: WechatWindowIdentity?
    ): WechatWindowEvidence = WechatWindowEvidence(
        activityClassName = windowIdentity?.activityClassName,
        isApplicationWindow = windows
            .firstOrNull { window -> window.id == windowId }
            ?.type == AccessibilityWindowInfo.TYPE_APPLICATION
    )

    private fun accessibilityWindowContext(
        event: AccessibilityEvent,
        activeWindowRoot: AccessibilityNodeInfo?,
        eventSourceRoot: AccessibilityNodeInfo?,
        selectedRootSource: String
    ): String = buildString {
        append("eventType=")
        append(AccessibilityEvent.eventTypeToString(event.eventType))
        append(" eventClass=")
        append(event.className ?: "none")
        append(" eventPackage=")
        append(event.packageName ?: "none")
        append(" eventWindowId=")
        append(event.windowId)
        append(" activeRootPackage=")
        append(activeWindowRoot?.packageName ?: "none")
        append(" activeRootWindowId=")
        append(activeWindowRoot?.windowId ?: UNKNOWN_WINDOW_ID)
        append(" eventSourcePackage=")
        append(eventSourceRoot?.packageName ?: "none")
        append(" eventSourceWindowId=")
        append(eventSourceRoot?.windowId ?: UNKNOWN_WINDOW_ID)
        append(" selectedRoot=")
        append(selectedRootSource)
    }

    override fun onInterrupt() {
        diagnosticRecorder.recordMetadata("service_interrupted", "failed", "accessibility_interrupted")
        BillSyncSessions.controller.fail("无障碍服务已中断")
    }

    override fun onDestroy() {
        diagnosticRecorder.recordMetadata("service_destroyed", "stopped", "service_destroyed")
        BillSyncServiceHealth.markServiceConnected(this, false)
        wechatOcrCoordinator.cancel()
        serviceScope.cancel()
        if (ocrRecognizerDelegate.isInitialized()) ocrRecognizer.close()
        super.onDestroy()
    }

    private companion object {
        const val UNKNOWN_WINDOW_ID = -1
    }
}

internal fun String.accessibilityDiagnosticSource(): DiagnosticSource = when (this) {
    BillSyncSource.WeChat.packageName -> DiagnosticSource.WeChat
    BillSyncSource.Alipay.packageName -> DiagnosticSource.Alipay
    else -> DiagnosticSource.Unknown
}

internal fun BillSyncSource.accessibilityDiagnosticSource(): DiagnosticSource = when (this) {
    BillSyncSource.WeChat -> DiagnosticSource.WeChat
    BillSyncSource.Alipay -> DiagnosticSource.Alipay
}
