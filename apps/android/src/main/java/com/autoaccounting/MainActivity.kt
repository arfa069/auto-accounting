package com.autoaccounting

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.autoaccounting.feature.account.WechatAuthCallback
import com.autoaccounting.feature.account.WechatAuthCallbackIntent
import com.autoaccounting.feature.billsync.BillSyncStateCoordinator
class MainActivity : ComponentActivity() {
    private val coordinator by lazy { BillSyncStateCoordinator(this) }
    private val pendingWechatAuthCallback = mutableStateOf<WechatAuthCallback?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNavigationIntent(intent)
        coordinator.onCreate()
        setContent {
            AutoAccountingApp(
                bindings = AutoAccountingAppBindings(
                    billSyncAccessibilityAccessGranted = coordinator.billSyncAccessibilityAccessGranted.value,
                    billSyncAccessibilityServiceConnected = coordinator.billSyncAccessibilityServiceConnected.value,
                    onOpenBillSyncAccessibilitySettings = coordinator::openBillSyncAccessibilitySettings,
                    onLaunchBillSyncSource = coordinator::launchBillSyncSource,
                    wechatAuthCallback = pendingWechatAuthCallback.value,
                    onWechatAuthCallbackConsumed = { pendingWechatAuthCallback.value = null }
                )
            )
        }
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
    }
}
