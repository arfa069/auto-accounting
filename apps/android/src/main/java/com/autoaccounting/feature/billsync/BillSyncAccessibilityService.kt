package com.autoaccounting.feature.billsync

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.autoaccounting.data.local.AutoAccountingDatabaseProvider
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.capture.BookkeepingResultNotifier
import com.autoaccounting.feature.capture.BookkeepingResultNotificationOrigin
import com.autoaccounting.feature.capture.SharedPreferencesAlipayTransitContextStore
import com.autoaccounting.feature.capture.toBookkeepingResultNotification
import com.autoaccounting.feature.diagnostics.DiagnosticComponent
import com.autoaccounting.feature.diagnostics.DiagnosticEvent
import com.autoaccounting.feature.diagnostics.DiagnosticEventMetadata
import com.autoaccounting.feature.diagnostics.DiagnosticLevel
import com.autoaccounting.feature.diagnostics.DiagnosticLogs
import com.autoaccounting.feature.diagnostics.DiagnosticSensitiveField
import com.autoaccounting.feature.diagnostics.DiagnosticSensitivePayload
import com.autoaccounting.feature.diagnostics.DiagnosticSource
import com.autoaccounting.feature.diagnostics.newDiagnosticTraceId
import com.autoaccounting.feature.diagnostics.toDiagnosticExceptionDetails
import com.autoaccounting.feature.monitoring.ContinuousMonitoringEvent
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.monitoring.ContinuousMonitoringServiceHealth
import com.autoaccounting.feature.monitoring.PaymentScreenCaptureDebouncer
import com.autoaccounting.feature.monitoring.SERVICE_HEARTBEAT_INTERVAL_MILLIS
import com.autoaccounting.feature.monitoring.decideContinuousMonitoringCapture
import com.autoaccounting.feature.monitoring.hasWechatMerchantPaymentSuccessSignature
import com.autoaccounting.feature.monitoring.hasOnlyGenericWechatAccessibilityText
import com.autoaccounting.feature.monitoring.hasWechatTransferCompletionContext
import com.autoaccounting.feature.monitoring.isContinuousMonitoringPackageAllowed
import com.autoaccounting.feature.review.ReviewQueuePersistence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class BillSyncAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    private val ocrRecognizerDelegate = lazy { PaymentScreenOcrRecognizer() }
    private val ocrRecognizer by ocrRecognizerDelegate
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }

    @Volatile
    private var continuousMonitoringState = ContinuousMonitoringState()
    @Volatile
    private var continuousMonitoringPermissionHealth = ContinuousMonitoringPermissionHealth()
    private var automaticCaptureJob: Job? = null
    private var healthHeartbeatJob: Job? = null
    private var wechatOcrCaptureJob: Job? = null
    private var alipayTransitOcrCaptureJob: Job? = null
    private var alipayOcrCaptureJob: Job? = null
    private var wechatOcrGuardResetJob: Job? = null
    private val automaticCaptureDebouncer = PaymentScreenCaptureDebouncer()
    private val continuousMonitoringEventGate = AccessibilityEventAdmissionGate()
    private val ocrSessionGuard = PaymentScreenOcrSessionGuard()
    private var lastWechatOcrAttemptAtElapsedMillis = 0L
    private var lastManualWechatOcrAttemptAtElapsedMillis = 0L
    private var lastAlipayTransitOcrAttemptAtElapsedMillis = 0L
    private var lastAlipayOcrAttemptAtElapsedMillis = 0L
    private var alipayPaymentFlowObservedAtElapsedMillis = 0L
    private var alipayTransitSurfaceInspected = false
    private var alipayOcrSurfaceInspected = false
    private var alipayOcrSurfaceFingerprint: Int? = null
    @Volatile
    private var activeWechatWindowIdentity: WechatWindowIdentity? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        recordMetadata("service_connected", "connected", "service_connected")
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        val captureRoute = captureRoute(packageName)
        if (captureRoute == AccessibilityCaptureRoute.Reject) return
        val manualBillSyncAcceptsPackage = captureRoute == AccessibilityCaptureRoute.ManualBillSync
        val shouldConsiderContinuousMonitoring = captureRoute == AccessibilityCaptureRoute.ContinuousMonitoring

        if (shouldConsiderContinuousMonitoring) {
            if (!isContinuousMonitoringEventRelevant(event.eventType)) return
            if (automaticCaptureJob?.isActive == true) return
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
            activeWechatWindowIdentity = windowIdentity
        }
        val monitoringPermissionHealth = if (manualBillSyncAcceptsPackage) {
            null
        } else {
            currentContinuousMonitoringPermissionHealth()
        }
        if (!manualBillSyncAcceptsPackage && !requireNotNull(monitoringPermissionHealth).isHealthy) return

        val activeRoot = rootInActiveWindow ?: event.source
        val pageText = activeRoot?.collectVisibleText().orEmpty()
        observeAlipayPaymentFlow(packageName, pageText, shouldConsiderContinuousMonitoring)
        val windowIdentity = activeWechatWindowIdentity
            ?.takeIf { identity -> identity.windowId == activeRoot?.windowId }
        val windowEvidence = activeRoot
            ?.takeIf { packageName == BillSyncSource.WeChat.packageName }
            ?.let { root -> currentWechatWindowEvidence(root.windowId, windowIdentity) }
        if (
            handleAlipaySurface(
                packageName = packageName,
                pageText = pageText,
                shouldConsiderContinuousMonitoring = shouldConsiderContinuousMonitoring,
                activeRoot = activeRoot,
                isWindowStateChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            )
        ) return
        if (
            handleWechatCaptureRoute(
                packageName = packageName,
                pageText = pageText,
                manualBillSyncAcceptsPackage = manualBillSyncAcceptsPackage,
                shouldConsiderContinuousMonitoring = shouldConsiderContinuousMonitoring,
                windowEvidence = windowEvidence
            )
        ) return
        if (pageText.isBlank()) {
            recordMetadata(
                event = "accessibility_event_rejected",
                outcome = "rejected",
                reason = "blank_visible_text",
                source = packageName.diagnosticSource()
            )
            return
        }

        if (packageName == BillSyncSource.WeChat.packageName) {
            scheduleWechatOcrGuardReset(packageName)
        }

        if (manualBillSyncAcceptsPackage) {
            captureManualBillSync(packageName, pageText)
            return
        }
        captureContinuousMonitoring(
            packageName = packageName,
            pageText = pageText,
            permissionHealth = requireNotNull(monitoringPermissionHealth)
        )
    }

    private fun handleWechatCaptureRoute(
        packageName: String,
        pageText: String,
        manualBillSyncAcceptsPackage: Boolean,
        shouldConsiderContinuousMonitoring: Boolean,
        windowEvidence: WechatWindowEvidence?
    ): Boolean {
        val isManualWechatPackage = manualBillSyncAcceptsPackage &&
            packageName == BillSyncSource.WeChat.packageName
        val isManualWechatOcrSession = isManualWechatPackage &&
            BillSyncSessions.controller.acceptsManualOcr(packageName)
        val shouldEvaluateManualOcr = isManualWechatOcrSession &&
            windowEvidence != null &&
            shouldAttemptManualWechatOcrFallback(
                packageName = packageName,
                pageText = pageText,
                sdkInt = Build.VERSION.SDK_INT,
                windowEvidence = windowEvidence
            )
        if (shouldEvaluateManualOcr) {
            captureManualWechatOcrFallback(packageName)
            return true
        }
        if (isManualWechatPackage) return true
        val shouldEvaluateOcr = shouldConsiderContinuousMonitoring &&
            windowEvidence != null &&
            isWechatOcrFallbackCandidate(
                packageName = packageName,
                pageText = pageText,
                sdkInt = Build.VERSION.SDK_INT,
                windowEvidence = windowEvidence
            )
        if (!shouldEvaluateOcr) return false
        wechatOcrGuardResetJob?.cancel()
        wechatOcrGuardResetJob = null
        captureWechatOcrFallback(packageName)
        return true
    }

    private fun captureRoute(packageName: String): AccessibilityCaptureRoute =
        resolveAccessibilityCaptureRoute(
            manualBillSyncAcceptsPackage = BillSyncSessions.controller.acceptsPackage(packageName),
            continuousMonitoringEnabled = continuousMonitoringState.enabled,
            continuousMonitoringPackageAllowed = isContinuousMonitoringPackageAllowed(packageName)
        )

    private fun captureManualBillSync(
        packageName: String,
        pageText: String
    ) {
        val source = BillSyncSource.fromPackageName(packageName) ?: return
        val observation = observeBillSyncPage(source, pageText)
        if (observation == BillSyncPageObservation.Ignored) {
            recordMetadata(
                event = "manual_page_rejected",
                outcome = "rejected",
                reason = "unrelated_page",
                source = source.diagnosticSource()
            )
            return
        }
        val traceId = newDiagnosticTraceId()
        val sessionId = BillSyncSessions.controller.state.value.sessionId

        serviceScope.launch {
            runCatching {
                BillSyncSessions.controller.submitBillPage(
                    packageName = packageName,
                    pageText = pageText,
                    process = { billSource, text ->
                        processor.process(billSource, text, traceId, sessionId)
                    }
                )
            }.onFailure { error ->
                BillSyncSessions.controller.fail(error.message ?: "补录失败")
                recordFailure("manual_capture_failed", traceId, source, sessionId, error)
            }
        }
    }

    private fun captureManualWechatOcrFallback(packageName: String) {
        if (!isScreenReadyForWechatOcr(powerManager.isInteractive, keyguardManager.isKeyguardLocked)) {
            recordMetadata(
                "manual_ocr_rejected",
                "rejected",
                "screen_off_or_locked",
                sessionId = BillSyncSessions.controller.state.value.sessionId,
                source = packageName.diagnosticSource(),
                component = DiagnosticComponent.Ocr
            )
            return
        }
        if (wechatOcrCaptureJob?.isActive == true) {
            recordMetadata(
                "manual_ocr_rejected",
                "rejected",
                "ocr_job_active",
                sessionId = BillSyncSessions.controller.state.value.sessionId,
                source = packageName.diagnosticSource(),
                component = DiagnosticComponent.Ocr
            )
            return
        }
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        if (
            nowElapsedMillis - lastManualWechatOcrAttemptAtElapsedMillis <
            MANUAL_OCR_ATTEMPT_COOLDOWN_MILLIS
        ) {
            recordMetadata(
                "manual_ocr_rejected",
                "rejected",
                "cooldown",
                sessionId = BillSyncSessions.controller.state.value.sessionId,
                source = packageName.diagnosticSource(),
                component = DiagnosticComponent.Ocr
            )
            return
        }
        lastManualWechatOcrAttemptAtElapsedMillis = nowElapsedMillis
        val traceId = newDiagnosticTraceId()
        val sessionId = BillSyncSessions.controller.state.value.sessionId
        recordMetadata(
            event = "manual_ocr_started",
            outcome = "started",
            reason = "manual_session",
            traceId = traceId,
            sessionId = sessionId,
            source = packageName.diagnosticSource(),
            component = DiagnosticComponent.Ocr
        )

        wechatOcrCaptureJob = serviceScope.launch {
            try {
                delay(AUTOMATIC_CAPTURE_SETTLE_MILLIS)
                val activeRoot = rootInActiveWindow
                if (activeRoot == null) {
                    recordMetadata(
                        "manual_ocr_rejected",
                        "rejected",
                        "active_window_unavailable",
                        traceId,
                        sessionId,
                        packageName.diagnosticSource(),
                        DiagnosticComponent.Ocr
                    )
                    return@launch
                }
                if (
                    activeRoot.packageName?.toString() != packageName ||
                    !BillSyncSessions.controller.acceptsManualOcr(packageName) ||
                    !isScreenReadyForWechatOcr(
                        powerManager.isInteractive,
                        keyguardManager.isKeyguardLocked
                    )
                ) {
                    recordMetadata(
                        "manual_ocr_rejected",
                        "rejected",
                        "settled_context_invalid",
                        traceId,
                        sessionId,
                        packageName.diagnosticSource(),
                        DiagnosticComponent.Ocr
                    )
                    return@launch
                }

                val windowId = activeRoot.windowId
                val windowIdentity = activeWechatWindowIdentity
                    ?.takeIf { identity -> identity.windowId == windowId }
                val windowEvidence = currentWechatWindowEvidence(windowId, windowIdentity)
                if (
                    !shouldAttemptManualWechatOcrFallback(
                        packageName = packageName,
                        pageText = activeRoot.collectVisibleText(),
                        sdkInt = Build.VERSION.SDK_INT,
                        windowEvidence = windowEvidence
                    )
                ) {
                    recordMetadata(
                        "manual_ocr_rejected",
                        "rejected",
                        "window_verification_failed",
                        traceId,
                        sessionId,
                        packageName.diagnosticSource(),
                        DiagnosticComponent.Ocr
                    )
                    return@launch
                }

                val screenshot = captureScreenBitmap(windowId)
                if (screenshot == null) {
                    recordMetadata(
                        "manual_ocr_rejected",
                        "rejected",
                        "screenshot_unavailable",
                        traceId,
                        sessionId,
                        packageName.diagnosticSource(),
                        DiagnosticComponent.Ocr
                    )
                    return@launch
                }
                try {
                    val currentRoot = rootInActiveWindow
                    val currentWindowIdentity = activeWechatWindowIdentity
                        ?.takeIf { identity -> identity.windowId == windowId }
                    val currentWindowEvidence = currentWechatWindowEvidence(
                        windowId = windowId,
                        windowIdentity = currentWindowIdentity
                    )
                    if (
                        currentRoot == null ||
                        currentRoot.packageName?.toString() != packageName ||
                        currentRoot.windowId != windowId ||
                        !BillSyncSessions.controller.acceptsManualOcr(packageName) ||
                        !isScreenReadyForWechatOcr(
                            powerManager.isInteractive,
                            keyguardManager.isKeyguardLocked
                        ) ||
                        !shouldAttemptManualWechatOcrFallback(
                            packageName = packageName,
                            pageText = currentRoot.collectVisibleText(),
                            sdkInt = Build.VERSION.SDK_INT,
                            windowEvidence = currentWindowEvidence
                        )
                    ) {
                        recordMetadata(
                            "manual_ocr_rejected",
                            "rejected",
                            "window_changed_before_ocr",
                            traceId,
                            sessionId,
                            packageName.diagnosticSource(),
                            DiagnosticComponent.Ocr
                        )
                        return@launch
                    }

                    val preparedPageText = prepareManualWechatOcrResultText(
                        ocrRecognizer.recognize(screenshot)
                    )
                    if (preparedPageText == null) {
                        recordMetadata(
                            "manual_ocr_rejected",
                            "rejected",
                            "ocr_output_unusable",
                            traceId,
                            sessionId,
                            packageName.diagnosticSource(),
                            DiagnosticComponent.Ocr
                        )
                        return@launch
                    }
                    var processedResult: BillSyncResult? = null
                    val completed = BillSyncSessions.controller.submitBillPage(
                        packageName = packageName,
                        pageText = preparedPageText,
                        process = { billSource, text ->
                            processor.processManualOcr(
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
                            recordMetadata(
                                "result_notification_requested",
                                "requested",
                                notification.javaClass.simpleName.ifBlank {
                                    "bookkeeping_result"
                                },
                                traceId = traceId,
                                sessionId = sessionId,
                                source = DiagnosticSource.WeChat
                            )
                            resultNotifier.notify(notification)
                        }
                    }
                } finally {
                    screenshot.recycle()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                recordFailure(
                    "manual_ocr_failed",
                    traceId,
                    BillSyncSource.WeChat,
                    sessionId,
                    error
                )
            } finally {
                wechatOcrCaptureJob = null
            }
        }
    }

    private fun recordAlipayMetroExitContext(
        reason: String,
        traceId: String = newDiagnosticTraceId()
    ) {
        serviceScope.launch {
            runCatching {
                processor.recordAlipayMetroExitContext()
            }.onSuccess { enrichedExistingNotification ->
                recordMetadata(
                    event = "alipay_transit_context_recorded",
                    outcome = "success",
                    reason = if (enrichedExistingNotification) {
                        "recent_notification_enriched"
                    } else {
                        reason
                    },
                    traceId = traceId,
                    source = DiagnosticSource.Alipay,
                    component = DiagnosticComponent.Ocr
                )
            }.onFailure { error ->
                recordFailure(
                    event = "alipay_transit_context_failed",
                    traceId = traceId,
                    source = BillSyncSource.Alipay,
                    sessionId = null,
                    error = error
                )
            }
        }
    }

    private fun handleAlipayTransitSurface(
        packageName: String,
        pageText: String,
        shouldConsiderContinuousMonitoring: Boolean,
        activeRoot: AccessibilityNodeInfo?
    ): Boolean {
        if (packageName != BillSyncSource.Alipay.packageName) {
            alipayTransitSurfaceInspected = false
            return false
        }
        val shouldConsiderTransit = shouldConsiderContinuousMonitoring &&
            activeRoot != null &&
            isApplicationWindow(activeRoot.windowId)
        if (!shouldConsiderTransit) {
            alipayTransitSurfaceInspected = false
            return false
        }
        if (isCompletedAlipayMetroExit(pageText)) {
            if (!alipayTransitSurfaceInspected) {
                alipayTransitSurfaceInspected = true
                recordAlipayMetroExitContext(reason = "accessible_transit_signature")
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
            if (!alipayTransitSurfaceInspected) {
                alipayTransitSurfaceInspected = true
                captureAlipayTransitOcrFallback(packageName)
            }
            return true
        }
        alipayTransitSurfaceInspected = false
        return false
    }

    private fun rememberAlipayPaymentFlow(pageText: String) {
        if (isAlipayPaymentInitiationPage(pageText)) {
            alipayPaymentFlowObservedAtElapsedMillis = SystemClock.elapsedRealtime()
        }
    }

    private fun observeAlipayPaymentFlow(
        packageName: String,
        pageText: String,
        shouldConsiderContinuousMonitoring: Boolean
    ) {
        if (!shouldConsiderContinuousMonitoring) return
        if (packageName == BillSyncSource.Alipay.packageName) {
            rememberAlipayPaymentFlow(pageText)
        } else {
            resetAlipayOcrState()
        }
    }

    private fun handleAlipaySurface(
        packageName: String,
        pageText: String,
        shouldConsiderContinuousMonitoring: Boolean,
        activeRoot: AccessibilityNodeInfo?,
        isWindowStateChanged: Boolean
    ): Boolean {
        if (handleAlipayTransitSurface(packageName, pageText, shouldConsiderContinuousMonitoring, activeRoot)) {
            return true
        }
        return handleAlipayOcrSurface(
            packageName = packageName,
            pageText = pageText,
            shouldConsiderContinuousMonitoring = shouldConsiderContinuousMonitoring,
            activeRoot = activeRoot,
            isWindowStateChanged = isWindowStateChanged
        )
    }

    private fun handleAlipayOcrSurface(
        packageName: String,
        pageText: String,
        shouldConsiderContinuousMonitoring: Boolean,
        activeRoot: AccessibilityNodeInfo?,
        isWindowStateChanged: Boolean
    ): Boolean {
        if (packageName != BillSyncSource.Alipay.packageName) {
            resetAlipayOcrState()
            return false
        }
        if (
            !shouldConsiderContinuousMonitoring ||
            activeRoot == null ||
            activeRoot.packageName?.toString() != packageName
        ) {
            resetAlipayOcrState()
            return false
        }

        val windowId = activeRoot.windowId
        val isApplicationWindow = isApplicationWindow(windowId)
        val hasRecentPaymentFlow = hasRecentAlipayPaymentFlow()
        val shouldAttempt = shouldAttemptAlipayOcrFallback(
            AlipayOcrFallbackRequest(
                packageName = packageName,
                pageText = pageText,
                sdkInt = Build.VERSION.SDK_INT,
                isApplicationWindow = isApplicationWindow,
                isWindowStateChanged = isWindowStateChanged,
                hasRecentPaymentFlow = hasRecentPaymentFlow,
                accessibilityNeedsOcr = alipayAccessibilityNeedsOcr(pageText)
            )
        )
        if (!shouldAttempt) {
            if (!hasRecentPaymentFlow && pageText.isNotBlank()) {
                alipayOcrSurfaceInspected = false
                alipayOcrSurfaceFingerprint = null
            }
            return false
        }

        val surfaceFingerprint = 31 * windowId + pageText.hashCode()
        if (surfaceFingerprint != alipayOcrSurfaceFingerprint) {
            alipayOcrSurfaceInspected = false
            alipayOcrSurfaceFingerprint = surfaceFingerprint
        }
        if (alipayOcrSurfaceInspected || alipayOcrCaptureJob?.isActive == true) return true

        alipayOcrSurfaceInspected = true
        captureAlipayOcrFallback(packageName)
        return true
    }

    private fun alipayAccessibilityNeedsOcr(pageText: String): Boolean {
        val parsedEntry = BillPageParser().parse(
            source = BillSyncSource.Alipay,
            pageText = pageText,
            fallbackTransactionTimeText = ALIPAY_OCR_FALLBACK_TRANSACTION_TIME
        ).singleOrNull() ?: return true
        return parsedEntry.merchantTitleFromFallback ||
            parsedEntry.fundingAccountFromFallback
    }

    private fun hasRecentAlipayPaymentFlow(): Boolean {
        val ageMillis = SystemClock.elapsedRealtime() - alipayPaymentFlowObservedAtElapsedMillis
        return alipayPaymentFlowObservedAtElapsedMillis > 0L &&
            ageMillis in 0..ALIPAY_PAYMENT_FLOW_WINDOW_MILLIS
    }

    private fun resetAlipayOcrState() {
        alipayPaymentFlowObservedAtElapsedMillis = 0L
        alipayOcrSurfaceInspected = false
        alipayOcrSurfaceFingerprint = null
        alipayOcrCaptureJob?.cancel()
        alipayOcrCaptureJob = null
    }

    private fun captureAlipayOcrFallback(packageName: String) {
        if (!isScreenReadyForWechatOcr(powerManager.isInteractive, keyguardManager.isKeyguardLocked)) {
            recordAlipayOcrRejection("screen_off_or_locked")
            return
        }
        if (alipayOcrCaptureJob?.isActive == true) return
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        if (nowElapsedMillis - lastAlipayOcrAttemptAtElapsedMillis < OCR_ATTEMPT_COOLDOWN_MILLIS) {
            recordAlipayOcrRejection("cooldown")
            return
        }
        lastAlipayOcrAttemptAtElapsedMillis = nowElapsedMillis
        val traceId = newDiagnosticTraceId()
        recordMetadata(
            event = "alipay_ocr_started",
            outcome = "started",
            reason = "payment_result_accessibility_incomplete",
            traceId = traceId,
            source = DiagnosticSource.Alipay,
            component = DiagnosticComponent.Ocr
        )

        alipayOcrCaptureJob = serviceScope.launch {
            try {
                delay(AUTOMATIC_CAPTURE_SETTLE_MILLIS)
                val initialRoot = currentAlipayOcrRoot(packageName)
                if (initialRoot == null) {
                    recordAlipayOcrRejection("settled_context_invalid", traceId)
                    return@launch
                }
                val windowId = initialRoot.windowId
                val screenshot = captureScreenBitmap(windowId)
                if (screenshot == null) {
                    recordAlipayOcrRejection("screenshot_unavailable", traceId)
                    return@launch
                }
                try {
                    val currentRoot = currentAlipayOcrRoot(packageName)
                    if (currentRoot == null || currentRoot.windowId != windowId) {
                        recordAlipayOcrRejection("window_changed_before_ocr", traceId)
                        return@launch
                    }
                    val ocrText = ocrRecognizer.recognize(screenshot)
                    captureAlipayOcrPaymentResult(
                        packageName = packageName,
                        pageText = ocrText,
                        traceId = traceId
                    )
                } finally {
                    screenshot.recycle()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                recordFailure(
                    "alipay_ocr_failed",
                    traceId,
                    BillSyncSource.Alipay,
                    null,
                    error
                )
            } finally {
                alipayOcrCaptureJob = null
            }
        }
    }

    private fun currentAlipayOcrRoot(packageName: String): AccessibilityNodeInfo? {
        if (
            !continuousMonitoringState.enabled ||
            !currentContinuousMonitoringPermissionHealth().isHealthy ||
            !isScreenReadyForWechatOcr(
                powerManager.isInteractive,
                keyguardManager.isKeyguardLocked
            )
        ) {
            return null
        }
        val root = rootInActiveWindow ?: return null
        if (
            root.packageName?.toString() != packageName ||
            !isApplicationWindow(root.windowId)
        ) {
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
                    hasRecentPaymentFlow = hasRecentAlipayPaymentFlow(),
                    accessibilityNeedsOcr = alipayAccessibilityNeedsOcr(pageText)
                )
            )
        }
    }

    private suspend fun captureAlipayOcrPaymentResult(
        packageName: String,
        pageText: String,
        traceId: String
    ): Boolean {
        val ocrDecision = decideAlipayOcrCapture(pageText)
        if (!ocrDecision.shouldCapture) {
            recordAlipayOcrRejection(
                ocrDecision.rejectionReason?.name ?: "unknown_rejection",
                traceId
            )
            return false
        }
        val permissionHealth = currentContinuousMonitoringPermissionHealth()
        if (!continuousMonitoringState.enabled || !permissionHealth.isHealthy) {
            recordAlipayOcrRejection("monitoring_blocked", traceId)
            return false
        }
        val decision = decideContinuousMonitoringCapture(
            state = continuousMonitoringState,
            event = ContinuousMonitoringEvent(
                packageName = packageName,
                screenText = pageText
            ),
            permissionHealth = permissionHealth
        )
        if (!decision.shouldCapture) {
            recordAlipayOcrRejection(decision.observation.name, traceId)
            return false
        }
        if (!automaticCaptureDebouncer.shouldProcess(packageName, pageText)) {
            recordAlipayOcrRejection("debounced", traceId)
            return false
        }

        val source = BillSyncSource.Alipay
        val outcome = runCatching {
            processor.processAutomatic(
                source = source,
                pageText = pageText,
                retainRawEvidence = false,
                traceId = traceId
            )
        }.onSuccess { result ->
            result.toBookkeepingResultNotification(source.label)?.let { notification ->
                recordMetadata(
                    "result_notification_requested",
                    "requested",
                    notification.javaClass.simpleName.ifBlank { "bookkeeping_result" },
                    traceId = traceId,
                    source = source.diagnosticSource()
                )
                resultNotifier.notify(notification)
            }
        }.onFailure { error ->
            recordFailure("alipay_ocr_processor_failed", traceId, source, null, error)
        }
        val processed = outcome.isSuccess && outcome.getOrNull()?.errorMessage == null
        if (processed) {
            alipayPaymentFlowObservedAtElapsedMillis = 0L
        }
        return processed
    }

    private fun recordAlipayOcrRejection(reason: String, traceId: String? = null) {
        recordMetadata(
            event = "alipay_ocr_rejected",
            outcome = "rejected",
            reason = reason,
            traceId = traceId ?: newDiagnosticTraceId(),
            source = DiagnosticSource.Alipay,
            component = DiagnosticComponent.Ocr
        )
    }

    private fun captureAlipayTransitOcrFallback(packageName: String) {
        if (!isScreenReadyForWechatOcr(powerManager.isInteractive, keyguardManager.isKeyguardLocked)) {
            recordMetadata(
                "alipay_transit_ocr_rejected",
                "rejected",
                "screen_off_or_locked",
                source = DiagnosticSource.Alipay,
                component = DiagnosticComponent.Ocr
            )
            return
        }
        if (alipayTransitOcrCaptureJob?.isActive == true) return
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        if (
            nowElapsedMillis - lastAlipayTransitOcrAttemptAtElapsedMillis <
            OCR_ATTEMPT_COOLDOWN_MILLIS
        ) {
            return
        }
        lastAlipayTransitOcrAttemptAtElapsedMillis = nowElapsedMillis
        val traceId = newDiagnosticTraceId()
        recordMetadata(
            event = "alipay_transit_ocr_started",
            outcome = "started",
            reason = "transit_accessibility_cue",
            traceId = traceId,
            source = DiagnosticSource.Alipay,
            component = DiagnosticComponent.Ocr
        )

        alipayTransitOcrCaptureJob = serviceScope.launch {
            try {
                delay(AUTOMATIC_CAPTURE_SETTLE_MILLIS)
                val initialRoot = currentAlipayTransitOcrRoot(packageName)
                if (initialRoot == null) {
                    recordAlipayTransitOcrRejection("settled_context_invalid", traceId)
                    return@launch
                }
                val windowId = initialRoot.windowId
                val screenshot = captureScreenBitmap(windowId)
                if (screenshot == null) {
                    recordAlipayTransitOcrRejection("screenshot_unavailable", traceId)
                    return@launch
                }
                try {
                    val currentRoot = currentAlipayTransitOcrRoot(packageName)
                    if (currentRoot == null || currentRoot.windowId != windowId) {
                        recordAlipayTransitOcrRejection("window_changed_before_ocr", traceId)
                        return@launch
                    }
                    val ocrText = ocrRecognizer.recognize(screenshot)
                    if (!isCompletedAlipayMetroExit(ocrText)) {
                        recordAlipayTransitOcrRejection("completion_signature_missing", traceId)
                        return@launch
                    }
                    recordAlipayMetroExitContext(
                        reason = "ocr_transit_signature",
                        traceId = traceId
                    )
                } finally {
                    screenshot.recycle()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                recordFailure(
                    "alipay_transit_ocr_failed",
                    traceId,
                    BillSyncSource.Alipay,
                    null,
                    error
                )
            } finally {
                alipayTransitOcrCaptureJob = null
            }
        }
    }

    private fun recordAlipayTransitOcrRejection(reason: String, traceId: String) {
        recordMetadata(
            "alipay_transit_ocr_rejected",
            "rejected",
            reason,
            traceId,
            source = DiagnosticSource.Alipay,
            component = DiagnosticComponent.Ocr
        )
    }

    private fun currentAlipayTransitOcrRoot(packageName: String): AccessibilityNodeInfo? {
        if (
            !continuousMonitoringState.enabled ||
            !currentContinuousMonitoringPermissionHealth().isHealthy ||
            !isScreenReadyForWechatOcr(
                powerManager.isInteractive,
                keyguardManager.isKeyguardLocked
            )
        ) {
            return null
        }
        val root = rootInActiveWindow ?: return null
        if (
            root.packageName?.toString() != packageName ||
            !isApplicationWindow(root.windowId)
        ) {
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

    private fun captureWechatOcrFallback(packageName: String) {
        if (!isScreenReadyForWechatOcr(powerManager.isInteractive, keyguardManager.isKeyguardLocked)) {
            recordMetadata(
                "automatic_ocr_rejected",
                "rejected",
                "screen_off_or_locked",
                source = packageName.diagnosticSource(),
                component = DiagnosticComponent.Ocr
            )
            return
        }
        if (wechatOcrCaptureJob?.isActive == true) {
            recordMetadata(
                "automatic_ocr_rejected",
                "rejected",
                "ocr_job_active",
                source = packageName.diagnosticSource(),
                component = DiagnosticComponent.Ocr
            )
            return
        }
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        if (nowElapsedMillis - lastWechatOcrAttemptAtElapsedMillis < OCR_ATTEMPT_COOLDOWN_MILLIS) {
            recordMetadata(
                "automatic_ocr_rejected",
                "rejected",
                "cooldown",
                source = packageName.diagnosticSource(),
                component = DiagnosticComponent.Ocr
            )
            return
        }
        lastWechatOcrAttemptAtElapsedMillis = nowElapsedMillis
        val traceId = newDiagnosticTraceId()
        recordMetadata(
            event = "automatic_ocr_started",
            outcome = "started",
            reason = "verified_window_candidate",
            traceId = traceId,
            source = packageName.diagnosticSource(),
            component = DiagnosticComponent.Ocr
        )

        wechatOcrCaptureJob = serviceScope.launch {
            try {
                delay(AUTOMATIC_CAPTURE_SETTLE_MILLIS)
                val activeRoot = rootInActiveWindow
                val activePackageName = activeRoot?.packageName?.toString()
                if (activePackageName != packageName) {
                    recordMetadata(
                        "automatic_ocr_rejected",
                        "rejected",
                        "active_window_changed",
                        traceId = traceId,
                        source = packageName.diagnosticSource(),
                        component = DiagnosticComponent.Ocr
                    )
                    return@launch
                }
                if (
                    !isScreenReadyForWechatOcr(
                        powerManager.isInteractive,
                        keyguardManager.isKeyguardLocked
                    )
                ) {
                    recordMetadata(
                        "automatic_ocr_rejected",
                        "rejected",
                        "screen_off_or_locked_after_wait",
                        traceId = traceId,
                        source = packageName.diagnosticSource(),
                        component = DiagnosticComponent.Ocr
                    )
                    return@launch
                }

                val permissionHealth = currentContinuousMonitoringPermissionHealth()
                if (!continuousMonitoringState.enabled || !permissionHealth.isHealthy) {
                    recordMetadata(
                        "automatic_ocr_rejected",
                        "rejected",
                        "monitoring_blocked_after_wait",
                        traceId = traceId,
                        source = packageName.diagnosticSource(),
                        component = DiagnosticComponent.Ocr
                    )
                    return@launch
                }

                val windowId = requireNotNull(activeRoot).windowId
                val windowIdentity = activeWechatWindowIdentity
                    ?.takeIf { identity -> identity.windowId == windowId }
                val windowEvidence = currentWechatWindowEvidence(windowId, windowIdentity)
                val nodePageText = activeRoot.collectVisibleText()
                val hasRecentPaymentNotification = !windowEvidence.isApplicationWindow &&
                    processor.hasRecentWechatNotificationCaptureCandidate()
                if (
                    !shouldAttemptWechatOcrFallback(
                        packageName = packageName,
                        pageText = nodePageText,
                        sdkInt = Build.VERSION.SDK_INT,
                        windowEvidence = windowEvidence,
                        hasRecentPaymentNotification = hasRecentPaymentNotification
                    )
                ) {
                    recordMetadata(
                        "automatic_ocr_rejected",
                        "rejected",
                        "window_verification_failed",
                        traceId = traceId,
                        source = packageName.diagnosticSource(),
                        component = DiagnosticComponent.Ocr
                    )
                    return@launch
                }
                val screenshot = captureScreenBitmap(windowId)
                if (screenshot == null) {
                    recordMetadata(
                        "automatic_ocr_rejected",
                        "rejected",
                        "screenshot_unavailable",
                        traceId = traceId,
                        source = packageName.diagnosticSource(),
                        component = DiagnosticComponent.Ocr
                    )
                    return@launch
                }
                try {
                    val currentRoot = rootInActiveWindow
                    if (
                        currentRoot == null ||
                        currentRoot.packageName?.toString() != packageName ||
                        currentRoot.windowId != windowId
                    ) {
                        recordMetadata(
                            "automatic_ocr_rejected",
                            "rejected",
                            "window_changed_before_ocr",
                            traceId = traceId,
                            source = packageName.diagnosticSource(),
                            component = DiagnosticComponent.Ocr
                        )
                        return@launch
                    }
                    if (
                        !isScreenReadyForWechatOcr(
                            powerManager.isInteractive,
                            keyguardManager.isKeyguardLocked
                        )
                    ) {
                        recordMetadata(
                            "automatic_ocr_rejected",
                            "rejected",
                            "screen_off_or_locked_before_ocr",
                            traceId = traceId,
                            source = packageName.diagnosticSource(),
                            component = DiagnosticComponent.Ocr
                        )
                        return@launch
                    }
                    val currentWindowIdentity = activeWechatWindowIdentity
                        ?.takeIf { identity -> identity.windowId == windowId }
                    val currentWindowEvidence = currentWechatWindowEvidence(
                        windowId = windowId,
                        windowIdentity = currentWindowIdentity
                    )
                    val currentHasRecentPaymentNotification =
                        !currentWindowEvidence.isApplicationWindow &&
                            processor.hasRecentWechatNotificationCaptureCandidate()
                    if (
                        !shouldAttemptWechatOcrFallback(
                            packageName = packageName,
                            pageText = currentRoot.collectVisibleText(),
                            sdkInt = Build.VERSION.SDK_INT,
                            windowEvidence = currentWindowEvidence,
                            hasRecentPaymentNotification = currentHasRecentPaymentNotification
                        )
                    ) {
                        recordMetadata(
                            "automatic_ocr_rejected",
                            "rejected",
                            "window_reverification_failed",
                            traceId = traceId,
                            source = packageName.diagnosticSource(),
                            component = DiagnosticComponent.Ocr
                        )
                        return@launch
                    }
                    val pageText = ocrRecognizer.recognize(screenshot)
                    captureOcrPaymentResult(
                        packageName = packageName,
                        pageText = pageText,
                        windowEvidence = currentWindowEvidence,
                        traceId = traceId
                    )
                } finally {
                    screenshot.recycle()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                recordFailure("automatic_ocr_failed", traceId, BillSyncSource.WeChat, null, error)
            } finally {
                wechatOcrCaptureJob = null
            }
        }
    }

    private fun scheduleWechatOcrGuardReset(packageName: String) {
        if (wechatOcrGuardResetJob?.isActive == true) return
        wechatOcrGuardResetJob = serviceScope.launch {
            delay(OCR_SESSION_RESET_SETTLE_MILLIS)
            val settledRoot = rootInActiveWindow
            if (
                settledRoot?.packageName?.toString() == packageName &&
                settledRoot.collectVisibleText().isNotBlank()
            ) {
                ocrSessionGuard.resetCurrentFingerprint()
            }
            wechatOcrGuardResetJob = null
        }
    }

    private suspend fun captureOcrPaymentResult(
        packageName: String,
        pageText: String,
        windowEvidence: WechatWindowEvidence,
        traceId: String
    ): Boolean {
        if (pageText.isBlank()) {
            recordMetadata(
                "ocr_output_rejected",
                "rejected",
                "blank_ocr_text",
                traceId = traceId,
                source = packageName.diagnosticSource(),
                component = DiagnosticComponent.Ocr
            )
            return false
        }
        val ocrDecision = decideWechatOcrCapture(
            pageText = pageText,
            windowEvidence = windowEvidence
        )
        if (!ocrDecision.shouldCapture) {
            recordMetadata(
                event = "ocr_output_rejected",
                outcome = "rejected",
                reason = ocrDecision.rejectionReason?.name ?: "unknown_rejection",
                traceId = traceId,
                source = packageName.diagnosticSource(),
                component = DiagnosticComponent.Ocr
            )
            return false
        }
        val transactionFingerprint = wechatOcrPaymentFingerprint(pageText)
        if (transactionFingerprint == null) {
            recordMetadata(
                "ocr_output_rejected",
                "rejected",
                "payment_fingerprint_missing",
                traceId = traceId,
                source = packageName.diagnosticSource(),
                component = DiagnosticComponent.Ocr
            )
            return false
        }
        val hasNewMatchingNotification = transactionFingerprint.isRedPacket &&
            processor.hasUniqueUnlinkedRecentWechatNotification(transactionFingerprint)
        if (
            !ocrSessionGuard.shouldProcess(
                fingerprint = transactionFingerprint,
                hasNewMatchingNotification = hasNewMatchingNotification
            )
        ) {
            recordMetadata(
                "ocr_output_rejected",
                "rejected",
                "session_duplicate",
                traceId = traceId,
                source = packageName.diagnosticSource(),
                component = DiagnosticComponent.Ocr
            )
            return false
        }
        val permissionHealth = currentContinuousMonitoringPermissionHealth()
        if (!continuousMonitoringState.enabled || !permissionHealth.isHealthy) {
            recordMetadata(
                "ocr_output_rejected",
                "rejected",
                "monitoring_blocked",
                traceId = traceId,
                source = packageName.diagnosticSource(),
                component = DiagnosticComponent.Ocr
            )
            return false
        }
        val decision = decideContinuousMonitoringCapture(
            state = continuousMonitoringState,
            event = ContinuousMonitoringEvent(
                packageName = packageName,
                screenText = pageText
            ),
            permissionHealth = permissionHealth
        )
        if (!decision.shouldCapture) {
            recordMetadata(
                "ocr_output_rejected",
                "rejected",
                decision.observation.name,
                traceId = traceId,
                source = packageName.diagnosticSource(),
                component = DiagnosticComponent.Ocr
            )
            return false
        }
        if (
            !automaticCaptureDebouncer.shouldProcess(
                packageName = packageName,
                screenText = pageText,
                bypassDuplicateWindow = hasNewMatchingNotification
            )
        ) {
            recordMetadata(
                "ocr_output_rejected",
                "rejected",
                "debounced",
                traceId = traceId,
                source = packageName.diagnosticSource(),
                component = DiagnosticComponent.Ocr
            )
            return false
        }

        val source = BillSyncSource.fromPackageName(packageName) ?: return false
        val outcome = runCatching {
            processor.processAutomatic(
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
                recordMetadata(
                    "result_notification_requested",
                    "requested",
                    notification.javaClass.simpleName.ifBlank { "bookkeeping_result" },
                    traceId = traceId,
                    source = source.diagnosticSource()
                )
                resultNotifier.notify(notification)
            }
        }.onFailure { error ->
            recordFailure("automatic_ocr_processor_failed", traceId, source, null, error)
        }
        val processed = outcome.isSuccess && outcome.getOrNull()?.errorMessage == null
        if (processed) {
            ocrSessionGuard.markProcessed(transactionFingerprint)
        }
        return processed
    }

    private suspend fun captureScreenBitmap(windowId: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        return captureScreenBitmapApi30(windowId)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreenBitmapApi30(windowId: Int): Bitmap? =
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
                    continuation.resume(null)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                takeScreenshotOfWindow(windowId, mainExecutor, callback)
            } else {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
            }
        }

    private fun captureContinuousMonitoring(
        packageName: String,
        pageText: String,
        permissionHealth: ContinuousMonitoringPermissionHealth
    ) {
        val traceId = newDiagnosticTraceId()
        val decision = decideContinuousMonitoringCapture(
            state = continuousMonitoringState,
            event = ContinuousMonitoringEvent(
                packageName = packageName,
                screenText = pageText
            ),
            permissionHealth = permissionHealth
        )
        if (!decision.shouldCapture) {
            val source = BillSyncSource.fromPackageName(packageName)
            val observation = source?.let { observeBillSyncPage(it, pageText) }
                ?: BillSyncPageObservation.Ignored
            recordPageDecision(
                event = "accessibility_page_rejected",
                traceId = traceId,
                packageName = packageName,
                reason = if (observation == BillSyncPageObservation.Ignored) {
                    decision.observation.name
                } else {
                    observation.name
                },
                pageText = pageText.takeIf { observation != BillSyncPageObservation.Ignored }
            )
            return
        }

        if (automaticCaptureJob?.isActive == true) {
            recordMetadata(
                "accessibility_stability_wait_rejected",
                "rejected",
                "stability_job_active",
                traceId = traceId,
                source = packageName.diagnosticSource()
            )
            return
        }
        recordMetadata(
            "accessibility_stability_wait_started",
            "started",
            "payment_related",
            traceId = traceId,
            source = packageName.diagnosticSource()
        )
        automaticCaptureJob = serviceScope.launch {
            delay(AUTOMATIC_CAPTURE_SETTLE_MILLIS)
            automaticCaptureJob = null
            val settledPageText = rootInActiveWindow
                ?.takeIf { it.packageName?.toString() == packageName }
                ?.collectVisibleText()
                ?.takeIf { it.isNotBlank() }
                ?: pageText
            val refreshedPermissionHealth = currentContinuousMonitoringPermissionHealth()
            val refreshedDecision = decideContinuousMonitoringCapture(
                state = continuousMonitoringState,
                event = ContinuousMonitoringEvent(
                    packageName = packageName,
                    screenText = settledPageText
                ),
                permissionHealth = refreshedPermissionHealth
            )
            if (!refreshedDecision.shouldCapture) {
                recordPageDecision(
                    event = "accessibility_window_rejected",
                    traceId = traceId,
                    packageName = packageName,
                    reason = refreshedDecision.observation.name,
                    pageText = settledPageText.takeIf {
                        BillSyncSource.fromPackageName(packageName)?.let { source ->
                            observeBillSyncPage(source, settledPageText) != BillSyncPageObservation.Ignored
                        } == true
                    }
                )
                return@launch
            }
            if (!automaticCaptureDebouncer.shouldProcess(packageName, settledPageText)) {
                recordMetadata(
                    "accessibility_page_rejected",
                    "rejected",
                    "debounced",
                    traceId = traceId,
                    source = packageName.diagnosticSource()
                )
                return@launch
            }

            val source = BillSyncSource.fromPackageName(packageName) ?: return@launch
            runCatching {
                processor.processAutomatic(
                    source = source,
                    pageText = settledPageText,
                    traceId = traceId
                )
            }.onSuccess { result ->
                result.toBookkeepingResultNotification(source.label)?.let { notification ->
                    recordMetadata(
                        "result_notification_requested",
                        "requested",
                        notification.javaClass.simpleName.ifBlank { "bookkeeping_result" },
                        traceId = traceId,
                        source = source.diagnosticSource()
                    )
                    resultNotifier.notify(notification)
                }
            }.onFailure { error ->
                recordFailure("automatic_page_capture_failed", traceId, source, null, error)
            }
        }
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

    private fun currentContinuousMonitoringPermissionHealth(): ContinuousMonitoringPermissionHealth =
        continuousMonitoringPermissionHealth

    override fun onInterrupt() {
        recordMetadata("service_interrupted", "failed", "accessibility_interrupted")
        BillSyncSessions.controller.fail("无障碍服务已中断")
    }

    override fun onDestroy() {
        recordMetadata("service_destroyed", "stopped", "service_destroyed")
        healthHeartbeatJob?.cancel()
        healthHeartbeatJob = null
        continuousMonitoringPermissionHealth = ContinuousMonitoringPermissionHealth()
        ContinuousMonitoringServiceHealth.markServiceConnected(this, false)
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

    private fun recordMetadata(
        event: String,
        outcome: String,
        reason: String,
        traceId: String = newDiagnosticTraceId(),
        sessionId: Long? = null,
        source: DiagnosticSource = DiagnosticSource.System,
        component: DiagnosticComponent = DiagnosticComponent.AccessibilityService
    ) {
        diagnostics.record(
            DiagnosticEvent(
                metadata = DiagnosticEventMetadata(
                    level = DiagnosticLevel.Info,
                    component = component,
                    event = event,
                    traceId = traceId,
                    sessionId = sessionId?.toString(),
                    source = source,
                    outcome = outcome,
                    reason = reason
                )
            )
        )
    }

    private fun recordFailure(
        event: String,
        traceId: String,
        source: BillSyncSource,
        sessionId: Long?,
        error: Throwable
    ) {
        diagnostics.record(
            DiagnosticEvent(
                metadata = DiagnosticEventMetadata(
                    level = DiagnosticLevel.Error,
                    component = DiagnosticComponent.AccessibilityService,
                    event = event,
                    traceId = traceId,
                    sessionId = sessionId?.toString(),
                    source = source.diagnosticSource(),
                    outcome = "failed",
                    reason = event
                ),
                sensitivePayload = DiagnosticSensitivePayload(
                    mapOf(
                        DiagnosticSensitiveField.ExceptionDetails to
                            error.toDiagnosticExceptionDetails()
                    )
                )
            )
        )
    }

    private fun recordPageDecision(
        event: String,
        traceId: String,
        packageName: String,
        reason: String,
        pageText: String?
    ) {
        diagnostics.record(
            DiagnosticEvent(
                metadata = DiagnosticEventMetadata(
                    level = DiagnosticLevel.Info,
                    component = DiagnosticComponent.AccessibilityService,
                    event = event,
                    traceId = traceId,
                    source = packageName.diagnosticSource(),
                    outcome = "rejected",
                    reason = reason
                ),
                sensitivePayload = pageText?.let {
                    DiagnosticSensitivePayload(
                        mapOf(DiagnosticSensitiveField.PageText to it)
                    )
                } ?: DiagnosticSensitivePayload()
            )
        )
    }
}

private fun String.diagnosticSource(): DiagnosticSource = when (this) {
    BillSyncSource.WeChat.packageName -> DiagnosticSource.WeChat
    BillSyncSource.Alipay.packageName -> DiagnosticSource.Alipay
    else -> DiagnosticSource.Unknown
}

private fun BillSyncSource.diagnosticSource(): DiagnosticSource = when (this) {
    BillSyncSource.WeChat -> DiagnosticSource.WeChat
    BillSyncSource.Alipay -> DiagnosticSource.Alipay
}
