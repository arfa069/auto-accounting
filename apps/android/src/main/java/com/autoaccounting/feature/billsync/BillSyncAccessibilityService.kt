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
import com.autoaccounting.data.local.AutoAccountingDatabaseProvider
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.capture.BookkeepingResultNotifier
import com.autoaccounting.feature.capture.toBookkeepingResultNotification
import com.autoaccounting.feature.monitoring.ContinuousMonitoringEvent
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.monitoring.PaymentScreenCaptureDebouncer
import com.autoaccounting.feature.monitoring.decideContinuousMonitoringCapture
import com.autoaccounting.feature.monitoring.isContinuousMonitoringPackageAllowed
import com.autoaccounting.feature.review.ReviewQueuePersistence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    private var wechatOcrCaptureJob: Job? = null
    private val automaticCaptureDebouncer = PaymentScreenCaptureDebouncer()
    private var lastWechatOcrAttemptAtElapsedMillis = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceScope.launch {
            preferencesRepository.userPreferences.collect { preferences ->
                continuousMonitoringState = preferences.continuousMonitoringState
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
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
        if (pageText.isBlank()) {
            if (
                shouldConsiderContinuousMonitoring &&
                shouldAttemptWechatOcrFallback(packageName, pageText, Build.VERSION.SDK_INT)
            ) {
                captureWechatOcrFallback(packageName)
            }
            return
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

                val screenshot = captureScreenBitmap(requireNotNull(activeRoot).windowId) ?: return@launch
                try {
                    if (rootInActiveWindow?.packageName?.toString() != packageName) return@launch
                    if (
                        !isScreenReadyForWechatOcr(
                            powerManager.isInteractive,
                            keyguardManager.isKeyguardLocked
                        )
                    ) {
                        return@launch
                    }
                    val pageText = ocrRecognizer.recognize(screenshot)
                    captureOcrPaymentResult(
                        packageName = packageName,
                        pageText = pageText
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

    private suspend fun captureOcrPaymentResult(
        packageName: String,
        pageText: String
    ) {
        if (pageText.isBlank()) return
        val permissionHealth = currentContinuousMonitoringPermissionHealth()
        if (!continuousMonitoringState.enabled || !permissionHealth.isHealthy) return
        val decision = decideContinuousMonitoringCapture(
            state = continuousMonitoringState,
            event = ContinuousMonitoringEvent(
                packageName = packageName,
                screenText = pageText
            ),
            permissionHealth = permissionHealth
        )
        if (!decision.shouldCapture) return
        if (!automaticCaptureDebouncer.shouldProcess(packageName, pageText)) return

        val source = BillSyncSource.fromPackageName(packageName) ?: return
        runCatching {
            processor.processAutomatic(
                source = source,
                pageText = pageText,
                retainRawEvidence = false
            )
        }.onSuccess { result ->
            result.toBookkeepingResultNotification(source.label)?.let(resultNotifier::notify)
        }.onFailure { error ->
            Log.w(TAG, "Automatic OCR payment capture failed", error)
        }
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

    private fun currentContinuousMonitoringPermissionHealth(): ContinuousMonitoringPermissionHealth =
        ContinuousMonitoringPermissionHealth(
            billSyncAccessibilityGranted = BillSyncPermission.isGranted(this)
        )

    override fun onInterrupt() {
        BillSyncSessions.controller.fail("无障碍服务已中断")
    }

    override fun onDestroy() {
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
    }
}

internal fun shouldAttemptWechatOcrFallback(
    packageName: String,
    pageText: String,
    sdkInt: Int
): Boolean = packageName == BillSyncSource.WeChat.packageName &&
    pageText.isBlank() &&
    sdkInt >= Build.VERSION_CODES.R

internal fun isScreenReadyForWechatOcr(
    screenInteractive: Boolean,
    keyguardLocked: Boolean
): Boolean = screenInteractive && !keyguardLocked

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
