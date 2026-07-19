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

    private val processor by lazy {
        BillSyncCaptureProcessor(
            pipeline = BillSyncPipeline(),
            reviewQueuePersistence = ReviewQueuePersistence(
                LocalLedgerRepository(database)
            ),
            preferencesRepository = preferencesRepository,
            diagnosticRecorder = diagnostics
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
    private var automaticCaptureJob: Job? = null
    private var healthHeartbeatJob: Job? = null
    private var wechatOcrCaptureJob: Job? = null
    private var wechatOcrGuardResetJob: Job? = null
    private val automaticCaptureDebouncer = PaymentScreenCaptureDebouncer()
    private val ocrSessionGuard = PaymentScreenOcrSessionGuard()
    private var lastWechatOcrAttemptAtElapsedMillis = 0L
    private var lastManualWechatOcrAttemptAtElapsedMillis = 0L
    @Volatile
    private var activeWechatWindowIdentity: WechatWindowIdentity? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        recordMetadata("service_connected", "connected", "service_connected")
        ContinuousMonitoringServiceHealth.markServiceConnected(this, true)
        healthHeartbeatJob?.cancel()
        healthHeartbeatJob = serviceScope.launch {
            while (isActive) {
                ContinuousMonitoringServiceHealth.markServiceConnected(this@BillSyncAccessibilityService, true)
                delay(SERVICE_HEARTBEAT_INTERVAL_MILLIS)
            }
        }
        serviceScope.launch {
            preferencesRepository.userPreferences.collect { preferences ->
                continuousMonitoringState = preferences.continuousMonitoringState
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
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
        val manualBillSyncAcceptsPackage = BillSyncSessions.controller.acceptsPackage(packageName)
        val monitoringPermissionHealth = if (manualBillSyncAcceptsPackage) {
            null
        } else {
            currentContinuousMonitoringPermissionHealth()
        }
        val shouldConsiderContinuousMonitoring = monitoringPermissionHealth != null &&
            continuousMonitoringState.enabled &&
            monitoringPermissionHealth.isHealthy &&
            isContinuousMonitoringPackageAllowed(packageName)
        if (!manualBillSyncAcceptsPackage && !shouldConsiderContinuousMonitoring) return

        val activeRoot = rootInActiveWindow ?: event.source
        val pageText = activeRoot?.collectVisibleText().orEmpty()
        val windowIdentity = activeWechatWindowIdentity
            ?.takeIf { identity -> identity.windowId == activeRoot?.windowId }
        val windowEvidence = activeRoot?.let { root ->
            currentWechatWindowEvidence(root.windowId, windowIdentity)
        }
        val shouldEvaluateManualOcr = manualBillSyncAcceptsPackage &&
            BillSyncSessions.controller.acceptsManualOcr(packageName) &&
            windowEvidence != null &&
            shouldAttemptManualWechatOcrFallback(
                packageName = packageName,
                pageText = pageText,
                sdkInt = Build.VERSION.SDK_INT,
                windowEvidence = windowEvidence
            )
        if (shouldEvaluateManualOcr) {
            captureManualWechatOcrFallback(packageName)
            return
        }
        val shouldEvaluateOcr = shouldConsiderContinuousMonitoring &&
            windowEvidence != null &&
            isWechatOcrFallbackCandidate(
                packageName = packageName,
                pageText = pageText,
                sdkInt = Build.VERSION.SDK_INT,
                windowEvidence = windowEvidence
            )
        if (shouldEvaluateOcr) {
            wechatOcrGuardResetJob?.cancel()
            wechatOcrGuardResetJob = null
            captureWechatOcrFallback(packageName)
            return
        }
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
                    BillSyncSessions.controller.submitBillPage(
                        packageName = packageName,
                        pageText = preparedPageText,
                        process = { billSource, text ->
                            processor.processManualOcr(billSource, text, traceId, sessionId)
                        }
                    )
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

    private fun currentContinuousMonitoringPermissionHealth(): ContinuousMonitoringPermissionHealth =
        ContinuousMonitoringPermissionHealth(
            billSyncAccessibilityGranted = BillSyncPermission.isGranted(this),
            billSyncAccessibilityServiceConnected =
                ContinuousMonitoringServiceHealth.isServiceConnected(this)
        )

    override fun onInterrupt() {
        recordMetadata("service_interrupted", "failed", "accessibility_interrupted")
        BillSyncSessions.controller.fail("无障碍服务已中断")
    }

    override fun onDestroy() {
        recordMetadata("service_destroyed", "stopped", "service_destroyed")
        healthHeartbeatJob?.cancel()
        healthHeartbeatJob = null
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

internal fun shouldAttemptWechatOcrFallback(
    packageName: String,
    pageText: String,
    sdkInt: Int,
    windowEvidence: WechatWindowEvidence,
    hasRecentPaymentNotification: Boolean = false
): Boolean = isWechatOcrFallbackCandidate(
    packageName = packageName,
    pageText = pageText,
    sdkInt = sdkInt,
    windowEvidence = windowEvidence
) && (windowEvidence.isApplicationWindow || hasRecentPaymentNotification)

internal fun shouldAttemptManualWechatOcrFallback(
    packageName: String,
    pageText: String,
    sdkInt: Int,
    windowEvidence: WechatWindowEvidence
): Boolean = packageName == BillSyncSource.WeChat.packageName &&
    sdkInt >= Build.VERSION_CODES.R &&
    windowEvidence.isApplicationWindow &&
    hasOnlyGenericWechatAccessibilityText(pageText)

private fun isWechatOcrFallbackCandidate(
    packageName: String,
    pageText: String,
    sdkInt: Int,
    windowEvidence: WechatWindowEvidence
): Boolean = packageName == BillSyncSource.WeChat.packageName &&
    sdkInt >= Build.VERSION_CODES.R &&
    isVerifiedWechatOcrResultActivity(windowEvidence.activityClassName) &&
    hasOnlyGenericWechatAccessibilityText(pageText) &&
    !hasWechatMerchantPaymentSuccessSignature(pageText) &&
    !hasWechatTransferCompletionContext(pageText)

internal fun isScreenReadyForWechatOcr(
    screenInteractive: Boolean,
    keyguardLocked: Boolean
): Boolean = screenInteractive && !keyguardLocked

private data class WechatWindowIdentity(
    val windowId: Int,
    val activityClassName: String
)

internal class PaymentScreenOcrSessionGuard {
    private var processedFingerprint: WechatOcrPaymentFingerprint? = null
    private val processedRedPacketFingerprints =
        linkedSetOf<WechatOcrPaymentFingerprint>()

    @Synchronized
    fun shouldProcess(
        fingerprint: WechatOcrPaymentFingerprint,
        hasNewMatchingNotification: Boolean = false
    ): Boolean =
        (fingerprint.isRedPacket && hasNewMatchingNotification) ||
            (
                fingerprint != processedFingerprint &&
                    fingerprint !in processedRedPacketFingerprints
                )

    @Synchronized
    fun markProcessed(fingerprint: WechatOcrPaymentFingerprint) {
        processedFingerprint = fingerprint
        if (fingerprint.isRedPacket) {
            processedRedPacketFingerprints += fingerprint
            while (processedRedPacketFingerprints.size > MAX_RED_PACKET_FINGERPRINTS) {
                val oldest = processedRedPacketFingerprints.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
        }
    }

    @Synchronized
    fun resetCurrentFingerprint() {
        processedFingerprint = null
    }

    private companion object {
        const val MAX_RED_PACKET_FINGERPRINTS = 64
    }
}

private fun AccessibilityNodeInfo.collectVisibleText(): String {
    val lines = mutableListOf<String>()

    fun collect(node: AccessibilityNodeInfo) {
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(lines::add)
        node.contentDescription?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(lines::add)
        repeat(node.childCount) { index ->
            node.getChild(index)?.let(::collect)
        }
    }

    collect(this)
    return lines.distinct().joinToString("\n")
}
