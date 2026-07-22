package com.autoaccounting.feature.monitoring

import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import com.autoaccounting.feature.billsync.BillSyncPermission
import com.autoaccounting.feature.billsync.BillSyncSource
import com.autoaccounting.feature.capture.BookkeepingResultNotificationPermission
import com.autoaccounting.feature.capture.NotificationListenerPermission
import com.autoaccounting.feature.capture.shouldRequestBookkeepingResultNotificationPermission

/**
 * Manages continuous monitoring service health, permissions, and settings intent routing.
 */
class MonitoringStateCoordinator(
    private val activity: ComponentActivity
) {
    val notificationListenerAccessGranted = mutableStateOf(false)
    val billSyncAccessibilityAccessGranted = mutableStateOf(false)
    val billSyncAccessibilityServiceConnected = mutableStateOf(false)
    val resultNotificationPermissionGranted = mutableStateOf(false)
    val backgroundReliabilityState = mutableStateOf(BackgroundReliabilityState())
    val permissionStateLoaded = mutableStateOf(false)

    private var monitoringServiceHealthListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val monitoringServiceHealthHandler = Handler(Looper.getMainLooper())
    private val refreshMonitoringServiceHealth = object : Runnable {
        override fun run() {
            if (activity.isFinishing || activity.isDestroyed) return
            billSyncAccessibilityServiceConnected.value =
                ContinuousMonitoringServiceHealth.isServiceConnected(activity)
            monitoringServiceHealthHandler.postDelayed(
                this,
                SERVICE_HEARTBEAT_INTERVAL_MILLIS
            )
        }
    }

    private val requestResultNotificationPermissionLauncher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            resultNotificationPermissionGranted.value = granted
        }

    fun onCreate() {
        billSyncAccessibilityServiceConnected.value =
            ContinuousMonitoringServiceHealth.isServiceConnected(activity)
        monitoringServiceHealthListener = ContinuousMonitoringServiceHealth.registerListener(activity) { connected ->
            billSyncAccessibilityServiceConnected.value = connected
        }
        monitoringServiceHealthHandler.post(refreshMonitoringServiceHealth)
    }

    fun onResume() {
        notificationListenerAccessGranted.value = NotificationListenerPermission.isGranted(activity)
        billSyncAccessibilityAccessGranted.value = BillSyncPermission.isGranted(activity)
        billSyncAccessibilityServiceConnected.value = ContinuousMonitoringServiceHealth.isServiceConnected(activity)
        resultNotificationPermissionGranted.value = BookkeepingResultNotificationPermission.isGranted(activity)
        backgroundReliabilityState.value = BackgroundReliability.read(activity)
        permissionStateLoaded.value = true
    }

    fun onStop() {
        monitoringServiceHealthHandler.removeCallbacks(refreshMonitoringServiceHealth)
    }

    fun onDestroy() {
        monitoringServiceHealthListener?.let { listener ->
            ContinuousMonitoringServiceHealth.unregisterListener(activity, listener)
        }
        monitoringServiceHealthListener = null
        monitoringServiceHealthHandler.removeCallbacks(refreshMonitoringServiceHealth)
    }

    fun openNotificationListenerSettings() {
        runCatching {
            activity.startActivity(NotificationListenerPermission.settingsIntent())
        }.getOrElse {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun openBillSyncAccessibilitySettings() {
        runCatching {
            activity.startActivity(BillSyncPermission.settingsIntent())
        }.getOrElse {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun requestResultNotificationPermission() {
        if (
            !shouldRequestBookkeepingResultNotificationPermission(
                sdkInt = android.os.Build.VERSION.SDK_INT,
                isGranted = BookkeepingResultNotificationPermission.isGranted(activity)
            )
        ) return
        requestResultNotificationPermissionLauncher.launch(
            BookkeepingResultNotificationPermission.permission
        )
    }

    fun openBackgroundRunningSettings() {
        startFirstAvailable(BackgroundReliability.backgroundRunningIntents(activity))
    }

    fun openAutoStartSettings() {
        startFirstAvailable(BackgroundReliability.autoStartIntents(activity))
    }

    fun openBatteryOptimizationSettings() {
        startFirstAvailable(BackgroundReliability.batteryOptimizationIntents(activity))
    }

    fun openBatterySaverSettings() {
        startFirstAvailable(BackgroundReliability.batterySaverIntents(activity))
    }

    fun launchBillSyncSource(source: BillSyncSource): Boolean {
        val launchIntent = activity.packageManager.getLaunchIntentForPackage(source.packageName)
            ?: return false
        return runCatching {
            activity.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    private fun startFirstAvailable(intents: List<Intent>) {
        launchSettingsIntent(
            intents = intents,
            fallback = BackgroundReliability.applicationDetailsIntent(activity),
            canResolve = { it.resolveActivity(activity.packageManager) != null },
            launch = activity::startActivity
        )
    }
}
