package com.autoaccounting

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.autoaccounting.feature.account.WechatAuthCallback
import com.autoaccounting.feature.account.WechatAuthCallbackIntent
import com.autoaccounting.feature.billsync.BillSyncSource
import com.autoaccounting.feature.monitoring.MonitoringStateCoordinator
import com.autoaccounting.ui.requestHighRefreshRate

class MainActivity : ComponentActivity() {
    private val coordinator by lazy { MonitoringStateCoordinator(this) }
    private val reviewNavigationRequest = mutableStateOf(0L)
    private val pendingEntryNavigationId = mutableStateOf<String?>(null)
    private val pendingWechatAuthCallback = mutableStateOf<WechatAuthCallback?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNavigationIntent(intent)
        coordinator.onCreate()
        setContent {
            AutoAccountingApp(
                bindings = AutoAccountingAppBindings(
                    notificationListenerAccessGranted = coordinator.notificationListenerAccessGranted.value,
                    onOpenNotificationListenerSettings = coordinator::openNotificationListenerSettings,
                    billSyncAccessibilityAccessGranted = coordinator.billSyncAccessibilityAccessGranted.value,
                    billSyncAccessibilityServiceConnected = coordinator.billSyncAccessibilityServiceConnected.value,
                    onOpenBillSyncAccessibilitySettings = coordinator::openBillSyncAccessibilitySettings,
                    resultNotificationPermissionGranted = coordinator.resultNotificationPermissionGranted.value,
                    onRequestResultNotificationPermission = coordinator::requestResultNotificationPermission,
                    backgroundReliabilityState = coordinator.backgroundReliabilityState.value,
                    onOpenBackgroundRunningSettings = coordinator::openBackgroundRunningSettings,
                    onOpenAutoStartSettings = coordinator::openAutoStartSettings,
                    onOpenBatteryOptimizationSettings = coordinator::openBatteryOptimizationSettings,
                    onOpenBatterySaverSettings = coordinator::openBatterySaverSettings,
                    onLaunchBillSyncSource = coordinator::launchBillSyncSource,
                    permissionStateLoaded = coordinator.permissionStateLoaded.value,
                    reviewNavigationRequest = reviewNavigationRequest.value,
                    pendingEntryNavigationId = pendingEntryNavigationId.value,
                    wechatAuthCallback = pendingWechatAuthCallback.value,
                    onWechatAuthCallbackConsumed = { pendingWechatAuthCallback.value = null }
                )
            )
        }
        requestHighRefreshRate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNavigationIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        coordinator.onResume()
    }

    override fun onStop() {
        coordinator.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        coordinator.onDestroy()
        super.onDestroy()
    }

    private fun handleNavigationIntent(intent: Intent?) {
        WechatAuthCallbackIntent.consume(this, intent)?.let { callback ->
            pendingWechatAuthCallback.value = callback
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_REVIEW, false) == true) {
            pendingEntryNavigationId.value = intent.getStringExtra(EXTRA_PENDING_ENTRY_ID)
            reviewNavigationRequest.value += 1
        }
    }

    companion object {
        const val EXTRA_OPEN_REVIEW = "com.autoaccounting.extra.OPEN_REVIEW"
        const val EXTRA_PENDING_ENTRY_ID = "com.autoaccounting.extra.PENDING_ENTRY_ID"
    }
}
