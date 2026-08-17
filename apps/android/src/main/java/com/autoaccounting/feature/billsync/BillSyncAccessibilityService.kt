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
import com.autoaccounting.feature.capture.BookkeepingResultNotifier
import com.autoaccounting.feature.capture.PaymentNotificationCaptureTriggers
import com.autoaccounting.feature.capture.SharedPreferencesAlipayTransitContextStore
import com.autoaccounting.feature.diagnostics.DiagnosticComponent
import com.autoaccounting.feature.diagnostics.DiagnosticLogs
import com.autoaccounting.feature.diagnostics.DiagnosticSensitiveField
import com.autoaccounting.feature.diagnostics.DiagnosticSensitivePayload
import com.autoaccounting.feature.diagnostics.DiagnosticSource
import com.autoaccounting.feature.diagnostics.newDiagnosticTraceId
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.monitoring.ContinuousMonitoringServiceHealth
import com.autoaccounting.feature.monitoring.PaymentScreenCaptureDebouncer
import com.autoaccounting.feature.monitoring.SERVICE_HEARTBEAT_INTERVAL_MILLIS
import com.autoaccounting.feature.review.ReviewQueuePersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Suppress("TooManyFunctions")
class BillSyncAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val database by lazy {
        AutoAccountingDatabaseProvider.get(this)
    }

    private val preferencesRepository by lazy {
        LocalPreferencesRepository(database)
    }
    private val alipayTransitContextStore by lazy {
        SharedPreferencesAlipayTransitContextStore(this)
    }

    private val processor by lazy {
        BillSyncCaptureProcessor(
            pipeline = BillSyncPipeline(),
            reviewQueuePersistence = ReviewQueuePersistence(
                LocalLedgerRepository(database)
            ),
            preferencesRepository = preferencesRepository,
            diagnosticRecorder = diagnostics,
            alipayTransitContextStore = alipayTransitContextStore
        )
    }

    private val resultNotifier by lazy { BookkeepingResultNotifier(this) }
    private val diagnostics by lazy { DiagnosticLogs.get(this) }
    private val diagnosticRecorder by lazy { BillSyncDiagnosticRecorder(diagnostics) }
    private val continuousCaptureCoordinator by lazy {
        ContinuousCaptureCoordinator(
            ContinuousCaptureDependencies(
                scope = serviceScope,
                processor = { processor },
                resultNotifier = { resultNotifier },
                diagnostics = diagnosticRecorder,
                state = { continuousMonitoringState },
                permissionHealth = ::currentContinuousMonitoringPermissionHealth,
                settledPageText = { packageName, fallback ->
                    rootInActiveWindow
                        ?.takeIf { it.packageName?.toString() == packageName }
                        ?.collectVisibleText()
                        ?.takeIf { it.isNotBlank() }
                        ?: fallback
                }
            )
        )
    }
    private val ocrRecognizerDelegate = lazy { PaymentScreenOcrRecognizer() }
    private val ocrRecognizer by ocrRecognizerDelegate
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val captureHost by lazy {
        object : AccessibilityCaptureHost {
            override val currentRoot: AccessibilityNodeInfo?
                get() = rootInActiveWindow
            override val monitoringState: ContinuousMonitoringState
                get() = continuousMonitoringState

            override fun currentPermissionHealth(): ContinuousMonitoringPermissionHealth =
                currentContinuousMonitoringPermissionHealth()

            override fun isScreenReady(): Boolean =
                isScreenReadyForWechatOcr(powerManager.isInteractive, keyguardManager.isKeyguardLocked)

            override fun isApplicationWindow(windowId: Int): Boolean =
                this@BillSyncAccessibilityService.isApplicationWindow(windowId)

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

            override suspend fun captureCurrentDisplayBitmap(traceId: String?): Bitmap? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    this@BillSyncAccessibilityService.captureCurrentDisplayBitmapApi30(traceId)
                } else {
                    null
                }

            override suspend fun recognizeScreen(bitmap: Bitmap): String =
                ocrRecognizer.recognize(bitmap)

            override suspend fun recognizeScreenEvidence(bitmap: Bitmap): PaymentTextEvidence =
                ocrRecognizer.recognizeEvidence(bitmap)
        }
    }

    @Volatile
    private var continuousMonitoringState = ContinuousMonitoringState()
    @Volatile
    private var continuousMonitoringPermissionHealth = ContinuousMonitoringPermissionHealth()
    private var healthHeartbeatJob: Job? = null
    private val automaticCaptureDebouncer = PaymentScreenCaptureDebouncer()
    private val continuousMonitoringEventGate = AccessibilityEventAdmissionGate()
    private val wechatOcrCoordinator by lazy {
        WechatOcrCaptureCoordinator(
            scope = serviceScope,
            host = captureHost,
            processor = { processor },
            resultNotifier = { resultNotifier },
            diagnostics = diagnosticRecorder,
            automaticCaptureDebouncer = automaticCaptureDebouncer
        )
    }
    private val alipayOcrCoordinator by lazy {
        AlipayOcrCaptureCoordinator(
            scope = serviceScope,
            host = captureHost,
            processor = { processor },
            resultNotifier = { resultNotifier },
            diagnostics = diagnosticRecorder,
            automaticCaptureDebouncer = automaticCaptureDebouncer
        )
    }
    private val captureRouter by lazy {
        AccessibilityCaptureRouter(
            onManualWechatOcr = wechatOcrCoordinator::captureManual,
            onAutomaticWechatOcr = wechatOcrCoordinator::captureAutomatic
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        diagnosticRecorder.recordMetadata("service_connected", "connected", "service_connected")
        continuousMonitoringPermissionHealth = ContinuousMonitoringPermissionHealth(
            billSyncAccessibilityGranted = true,
            billSyncAccessibilityServiceConnected = true
        )
        ContinuousMonitoringServiceHealth.markServiceConnected(this, true)
        healthHeartbeatJob?.cancel()
        healthHeartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(SERVICE_HEARTBEAT_INTERVAL_MILLIS)
                ContinuousMonitoringServiceHealth.markServiceConnected(
                    this@BillSyncAccessibilityService,
                    true
                )
            }
        }
        serviceScope.launch {
            preferencesRepository.userPreferences.collect { preferences ->
                continuousMonitoringState = preferences.continuousMonitoringState
                if (!continuousMonitoringState.enabled) {
                    alipayTransitContextStore.clear()
                }
            }
        }
        serviceScope.launch {
            PaymentNotificationCaptureTriggers.events.collect { trigger ->
                if (
                    PaymentNotificationCaptureTriggers.pendingFor(trigger.packageName)?.captureId !=
                    trigger.captureId
                ) return@collect
                val currentRoot = rootInActiveWindow
                    ?.takeIf { it.packageName?.toString() == trigger.packageName }
                    ?: return@collect
                when (BillSyncSource.fromPackageName(trigger.packageName)) {
                    BillSyncSource.Alipay -> alipayOcrCoordinator.handleNotificationTrigger(
                        trigger = trigger,
                        activeRoot = currentRoot,
                        windowContext = notificationWindowContext(currentRoot)
                    )
                    BillSyncSource.WeChat -> wechatOcrCoordinator.captureAutomatic(trigger.packageName)
                    null -> Unit
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        val captureRoute = captureRouter.captureRoute(packageName, continuousMonitoringState.enabled)
        if (captureRoute == AccessibilityCaptureRoute.Reject) return
        val manualBillSyncAcceptsPackage = captureRoute == AccessibilityCaptureRoute.ManualBillSync
        val shouldConsiderContinuousMonitoring = captureRoute == AccessibilityCaptureRoute.ContinuousMonitoring

        if (shouldConsiderContinuousMonitoring) {
            if (!isContinuousMonitoringEventRelevant(event.eventType)) return
            if (
                !continuousMonitoringEventGate.shouldInspect(
                    packageName = packageName,
                    eventType = event.eventType,
                    windowId = event.windowId
                )
            ) {
                return
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val windowIdentity = event.className?.toString()
                ?.takeIf { packageName == BillSyncSource.WeChat.packageName }
                ?.let { activityClassName ->
                    WechatWindowIdentity(
                        windowId = event.windowId,
                        activityClassName = activityClassName
                    )
            }
            wechatOcrCoordinator.updateActiveWindowIdentity(windowIdentity)
        }
        val monitoringPermissionHealth = if (manualBillSyncAcceptsPackage) {
            null
        } else {
            currentContinuousMonitoringPermissionHealth()
        }
        if (!manualBillSyncAcceptsPackage && !requireNotNull(monitoringPermissionHealth).isHealthy) return

        val activeWindowRoot = rootInActiveWindow
        val eventSourceRoot = event.source
        val activeRoot = activeWindowRoot
            ?.takeIf { it.packageName?.toString() == packageName }
            ?: eventSourceRoot?.takeIf { it.packageName?.toString() == packageName }
        val selectedRootSource = when {
            activeRoot == null -> "none"
            activeRoot == activeWindowRoot -> "rootInActiveWindow"
            activeRoot == eventSourceRoot -> "event.source"
            else -> "none"
        }
        val windowContext = accessibilityWindowContext(
            event = event,
            activeWindowRoot = activeWindowRoot,
            eventSourceRoot = eventSourceRoot,
            selectedRootSource = selectedRootSource
        )
        val pageText = activeRoot?.collectVisibleText().orEmpty()
        alipayOcrCoordinator.observePaymentFlow(
            packageName,
            pageText,
            shouldConsiderContinuousMonitoring
        )
        val windowIdentity = wechatOcrCoordinator.windowIdentityFor(activeRoot?.windowId)
        val windowEvidence = activeRoot
            ?.takeIf { packageName == BillSyncSource.WeChat.packageName }
            ?.let { root -> currentWechatWindowEvidence(root.windowId, windowIdentity) }
        if (
            alipayOcrCoordinator.handleSurface(
                packageName = packageName,
                pageText = pageText,
                shouldConsiderContinuousMonitoring = shouldConsiderContinuousMonitoring,
                activeRoot = activeRoot,
                eventType = event.eventType,
                eventWindowId = event.windowId,
                notificationTrigger = PaymentNotificationCaptureTriggers.pendingFor(packageName),
                windowContext = windowContext
            )
        ) return
        if (
            captureRouter.handleWechatCaptureRoute(
                packageName = packageName,
                pageText = pageText,
                manualBillSyncAcceptsPackage = manualBillSyncAcceptsPackage,
                shouldConsiderContinuousMonitoring = shouldConsiderContinuousMonitoring,
                windowEvidence = windowEvidence
            )
        ) return
        if (pageText.isBlank()) {
            diagnosticRecorder.recordMetadata(
                event = "accessibility_event_rejected",
                outcome = "rejected",
                reason = "blank_visible_text",
                source = packageName.accessibilityDiagnosticSource(),
                sensitivePayload = DiagnosticSensitivePayload(
                    mapOf(DiagnosticSensitiveField.WindowContext to windowContext)
                )
            )
            return
        }

        if (packageName == BillSyncSource.WeChat.packageName) {
            wechatOcrCoordinator.scheduleGuardReset(packageName)
        }

        if (manualBillSyncAcceptsPackage) {
            captureManualBillSync(packageName, pageText)
            return
        }
        if (!continuousCaptureCoordinator.isCapturing) continuousCaptureCoordinator.capture(
            packageName = packageName,
            pageText = pageText,
            currentPermissionHealth = requireNotNull(monitoringPermissionHealth)
        )
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
                            processor.process(billSource, text, traceId, sessionId)
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
        captureScreenBitmapApi30(traceId, "window:$windowId") { callback ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                takeScreenshotOfWindow(windowId, mainExecutor, callback)
            } else {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
            }
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureCurrentDisplayBitmapApi30(traceId: String?): Bitmap? =
        captureScreenBitmapApi30(traceId, "display") { callback ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreenBitmapApi30(
        traceId: String?,
        captureTarget: String,
        request: (TakeScreenshotCallback) -> Unit
    ): Bitmap? =
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
                        reason = "$captureTarget:errorCode=$errorCode",
                        traceId = traceId ?: newDiagnosticTraceId(),
                        component = DiagnosticComponent.Ocr
                    )
                    continuation.resume(null)
                }
            }
            request(callback)
        }



    private fun currentWechatWindowEvidence(
        windowId: Int,
        windowIdentity: WechatWindowIdentity?
    ): WechatWindowEvidence =
        WechatWindowEvidence(
            activityClassName = windowIdentity?.activityClassName,
            isApplicationWindow = windows
                .firstOrNull { window -> window.id == windowId }
                ?.type == AccessibilityWindowInfo.TYPE_APPLICATION
        )

    private fun isApplicationWindow(windowId: Int): Boolean =
        windows.firstOrNull { window -> window.id == windowId }
            ?.type == AccessibilityWindowInfo.TYPE_APPLICATION

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
        append(activeWindowRoot?.windowId ?: UNDEFINED_ACCESSIBILITY_WINDOW_ID)
        append(" activeRootWindowType=")
        append(windowTypeName(activeWindowRoot?.windowId))
        append(" eventSourcePackage=")
        append(eventSourceRoot?.packageName ?: "none")
        append(" eventSourceWindowId=")
        append(eventSourceRoot?.windowId ?: UNDEFINED_ACCESSIBILITY_WINDOW_ID)
        append(" eventSourceWindowType=")
        append(windowTypeName(eventSourceRoot?.windowId))
        append(" selectedRoot=")
        append(selectedRootSource)
    }

    private fun notificationWindowContext(root: AccessibilityNodeInfo): String = buildString {
        append("eventType=notification_trigger rootPackage=")
        append(root.packageName ?: "none")
        append(" rootWindowId=")
        append(root.windowId)
        append(" rootWindowType=")
        append(windowTypeName(root.windowId))
        append(" selectedRoot=rootInActiveWindow")
    }

    private fun windowTypeName(windowId: Int?): String {
        val type = windowId?.let { id -> windows.firstOrNull { it.id == id }?.type }
        return when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
            null -> "unknown"
            else -> "type_$type"
        }
    }

    private fun currentContinuousMonitoringPermissionHealth(): ContinuousMonitoringPermissionHealth =
        continuousMonitoringPermissionHealth

    override fun onInterrupt() {
        diagnosticRecorder.recordMetadata("service_interrupted", "failed", "accessibility_interrupted")
        BillSyncSessions.controller.fail("无障碍服务已中断")
    }

    override fun onDestroy() {
        diagnosticRecorder.recordMetadata("service_destroyed", "stopped", "service_destroyed")
        healthHeartbeatJob?.cancel()
        healthHeartbeatJob = null
        continuousMonitoringPermissionHealth = ContinuousMonitoringPermissionHealth()
        ContinuousMonitoringServiceHealth.markServiceConnected(this, false)
        continuousCaptureCoordinator.cancel()
        wechatOcrCoordinator.cancel()
        alipayOcrCoordinator.cancel()
        serviceScope.cancel()
        if (ocrRecognizerDelegate.isInitialized()) {
            ocrRecognizer.close()
        }
        super.onDestroy()
    }

    private companion object {
        const val AUTOMATIC_CAPTURE_SETTLE_MILLIS = 500L
        const val OCR_ATTEMPT_COOLDOWN_MILLIS = 3_000L
        const val ALIPAY_PAYMENT_FLOW_WINDOW_MILLIS = 2 * 60_000L
        const val MANUAL_OCR_ATTEMPT_COOLDOWN_MILLIS = 1_000L
        const val OCR_SESSION_RESET_SETTLE_MILLIS = 3_000L
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
