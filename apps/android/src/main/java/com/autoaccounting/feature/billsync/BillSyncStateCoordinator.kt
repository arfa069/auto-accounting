package com.autoaccounting.feature.billsync

import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf

class BillSyncStateCoordinator(
    private val activity: ComponentActivity
) {
    val billSyncAccessibilityAccessGranted = mutableStateOf(false)
    val billSyncAccessibilityServiceConnected = mutableStateOf(false)

    private var serviceHealthListener:
        android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun onCreate() {
        billSyncAccessibilityServiceConnected.value = BillSyncServiceHealth.isServiceConnected(activity)
        serviceHealthListener = BillSyncServiceHealth.registerListener(activity) { connected ->
            billSyncAccessibilityServiceConnected.value = connected
        }
    }

    fun onResume() {
        billSyncAccessibilityAccessGranted.value = BillSyncPermission.isGranted(activity)
        billSyncAccessibilityServiceConnected.value = BillSyncServiceHealth.isServiceConnected(activity)
    }

    fun onDestroy() {
        serviceHealthListener?.let { listener ->
            BillSyncServiceHealth.unregisterListener(activity, listener)
        }
        serviceHealthListener = null
    }

    fun openBillSyncAccessibilitySettings() {
        runCatching {
            activity.startActivity(BillSyncPermission.settingsIntent())
        }.getOrElse {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun launchBillSyncSource(source: BillSyncSource): Boolean {
        val launchIntent = activity.packageManager.getLaunchIntentForPackage(source.packageName)
            ?: return false
        return runCatching {
            activity.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }
}
