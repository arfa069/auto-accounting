package com.autoaccounting.feature.billsync

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoaccounting.data.local.AutoAccountingDatabaseProvider
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.capture.NotificationListenerPermission
import com.autoaccounting.feature.monitoring.ContinuousMonitoringEvent
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.monitoring.decideContinuousMonitoringCapture
import com.autoaccounting.feature.monitoring.isContinuousMonitoringPackageAllowed
import com.autoaccounting.feature.review.ReviewQueuePersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

    @Volatile
    private var continuousMonitoringState = ContinuousMonitoringState()

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

        val pageText = (rootInActiveWindow ?: event.source)
            ?.collectVisibleText()
            .orEmpty()
        if (pageText.isBlank()) return

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

        val source = BillSyncSource.fromPackageName(packageName) ?: return
        serviceScope.launch {
            runCatching {
                processor.process(source = source, pageText = pageText)
            }.onFailure { error ->
                Log.w(TAG, "Continuous monitoring capture failed", error)
            }
        }
    }

    private fun currentContinuousMonitoringPermissionHealth(): ContinuousMonitoringPermissionHealth =
        ContinuousMonitoringPermissionHealth(
            notificationListenerGranted = NotificationListenerPermission.isGranted(this),
            billSyncAccessibilityGranted = BillSyncPermission.isGranted(this)
        )

    override fun onInterrupt() {
        BillSyncSessions.controller.fail("无障碍服务已中断")
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BillSyncService"
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
