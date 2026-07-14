package com.autoaccounting.feature.billsync

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.autoaccounting.data.local.AutoAccountingDatabaseProvider
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.capture.BookkeepingResultNotifier
import com.autoaccounting.feature.capture.toBookkeepingResultNotification
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
            preferencesRepository = preferencesRepository
        )
    }

    private val resultNotifier by lazy { BookkeepingResultNotifier(this) }
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
    @Volatile
    private var activeWechatWindowIdentity: WechatWindowIdentity? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
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
        if (pageText.isBlank()) return

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
        if (observation == BillSyncPageObservation.Ignored) return

        serviceScope.launch {
            runCatching {
                BillSyncSessions.controller.submitBillPage(
                    packageName = packageName,
                    pageText = pageText,
                    process = processor::process
                )
            }.onFailure { error ->
                BillSyncSessions.controller.fail(error.message ?: "账单同步失败")
                Log.w(TAG, "Bill sync capture failed", error)
            }
        }
    }

    private fun captureWechatOcrFallback(packageName: String) {
        if (!isScreenReadyForWechatOcr(powerManager.isInteractive, keyguardManager.isKeyguardLocked)) {
            return
        }
        if (wechatOcrCaptureJob?.isActive == true) return
        val nowElapsedMillis = SystemClock.elapsedRealtime()
        if (nowElapsedMillis - lastWechatOcrAttemptAtElapsedMillis < OCR_ATTEMPT_COOLDOWN_MILLIS) {
            return
        }
        lastWechatOcrAttemptAtElapsedMillis = nowElapsedMillis

        wechatOcrCaptureJob = serviceScope.launch {
            try {
                delay(AUTOMATIC_CAPTURE_SETTLE_MILLIS)
                val activeRoot = rootInActiveWindow
                val activePackageName = activeRoot?.packageName?.toString()
                if (activePackageName != packageName) return@launch
                if (
                    !isScreenReadyForWechatOcr(
                        powerManager.isInteractive,
                        keyguardManager.isKeyguardLocked
                    )
                ) {
                    return@launch
                }

                val permissionHealth = currentContinuousMonitoringPermissionHealth()
                if (!continuousMonitoringState.enabled || !permissionHealth.isHealthy) return@launch

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
                    return@launch
                }
                val screenshot = captureScreenBitmap(windowId) ?: return@launch
                try {
                    val currentRoot = rootInActiveWindow
                    if (
                        currentRoot == null ||
                        currentRoot.packageName?.toString() != packageName ||
                        currentRoot.windowId != windowId
                    ) {
                        return@launch
                    }
                    if (
                        !isScreenReadyForWechatOcr(
                            powerManager.isInteractive,
                            keyguardManager.isKeyguardLocked
                        )
                    ) {
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
                        return@launch
                    }
                    val pageText = ocrRecognizer.recognize(screenshot)
                    captureOcrPaymentResult(
                        packageName = packageName,
                        pageText = pageText,
                        windowEvidence = currentWindowEvidence
                    )
                } finally {
                    screenshot.recycle()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Local OCR payment capture failed", error)
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
        windowEvidence: WechatWindowEvidence
    ): Boolean {
        if (pageText.isBlank()) return false
        val ocrDecision = decideWechatOcrCapture(
            pageText = pageText,
            windowEvidence = windowEvidence
        )
        if (!ocrDecision.shouldCapture) return false
        val transactionFingerprint = wechatOcrPaymentFingerprint(pageText) ?: return false
        val hasNewMatchingNotification = transactionFingerprint.isRedPacket &&
            processor.hasUniqueUnlinkedRecentWechatNotification(transactionFingerprint)
        if (
            !ocrSessionGuard.shouldProcess(
                fingerprint = transactionFingerprint,
                hasNewMatchingNotification = hasNewMatchingNotification
            )
        ) {
            return false
        }
        val permissionHealth = currentContinuousMonitoringPermissionHealth()
        if (!continuousMonitoringState.enabled || !permissionHealth.isHealthy) return false
        val decision = decideContinuousMonitoringCapture(
            state = continuousMonitoringState,
            event = ContinuousMonitoringEvent(
                packageName = packageName,
                screenText = pageText
            ),
            permissionHealth = permissionHealth
        )
        if (!decision.shouldCapture) return false
        if (
            !automaticCaptureDebouncer.shouldProcess(
                packageName = packageName,
                screenText = pageText,
                bypassDuplicateWindow = hasNewMatchingNotification
            )
        ) {
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
                }
            )
        }.onSuccess { result ->
            result.toBookkeepingResultNotification(source.label)?.let(resultNotifier::notify)
        }.onFailure { error ->
            Log.w(TAG, "Automatic OCR payment capture failed", error)
        }
        val processed = outcome.isSuccess && outcome.getOrNull()?.errorMessage == null
        if (processed) {
            ocrSessionGuard.markProcessed(transactionFingerprint)
        }
        return processed
    }

    private suspend fun captureScreenBitmap(windowId: Int): Bitmap? =
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
        val decision = decideContinuousMonitoringCapture(
            state = continuousMonitoringState,
            event = ContinuousMonitoringEvent(
                packageName = packageName,
                screenText = pageText
            ),
            permissionHealth = permissionHealth
        )
        if (!decision.shouldCapture) return

        if (automaticCaptureJob?.isActive == true) return
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
            if (!refreshedDecision.shouldCapture) return@launch
            if (!automaticCaptureDebouncer.shouldProcess(packageName, settledPageText)) return@launch

            val source = BillSyncSource.fromPackageName(packageName) ?: return@launch
            runCatching {
                processor.processAutomatic(source = source, pageText = settledPageText)
            }.onSuccess { result ->
                result.toBookkeepingResultNotification(source.label)?.let(resultNotifier::notify)
            }.onFailure { error ->
                Log.w(TAG, "Automatic payment capture failed", error)
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
        BillSyncSessions.controller.fail("无障碍服务已中断")
    }

    override fun onDestroy() {
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
        const val TAG = "BillSyncService"
        const val AUTOMATIC_CAPTURE_SETTLE_MILLIS = 500L
        const val OCR_ATTEMPT_COOLDOWN_MILLIS = 3_000L
        const val OCR_SESSION_RESET_SETTLE_MILLIS = 3_000L
    }
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
