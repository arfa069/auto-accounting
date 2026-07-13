package com.autoaccounting.feature.monitoring

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

data class BackgroundReliabilityState(
    val batteryOptimizationIgnored: Boolean = false,
    val powerSaveModeEnabled: Boolean = false,
    val manufacturer: DeviceManufacturer = DeviceManufacturer.Other
)

enum class DeviceManufacturer(
    val autoStartGuidance: String
) {
    Xiaomi("设置 → 应用设置 → 应用管理 → 自动记账"),
    Huawei("设置 → 应用和服务 → 应用启动管理"),
    Oppo("设置 → 应用 → 自启动"),
    Vivo("设置 → 应用与权限 → 权限管理 → 自启动"),
    Other("请在系统应用设置中允许后台运行或自启动")
}

fun deviceManufacturer(manufacturer: String): DeviceManufacturer = when {
    manufacturer.contains("xiaomi", ignoreCase = true) -> DeviceManufacturer.Xiaomi
    manufacturer.contains("huawei", ignoreCase = true) -> DeviceManufacturer.Huawei
    manufacturer.contains("oppo", ignoreCase = true) -> DeviceManufacturer.Oppo
    manufacturer.contains("vivo", ignoreCase = true) -> DeviceManufacturer.Vivo
    else -> DeviceManufacturer.Other
}

object BackgroundReliability {
    fun read(context: Context): BackgroundReliabilityState {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return BackgroundReliabilityState(
            batteryOptimizationIgnored =
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true,
            powerSaveModeEnabled = powerManager?.isPowerSaveMode == true,
            manufacturer = deviceManufacturer(Build.MANUFACTURER.orEmpty())
        )
    }

    fun applicationDetailsIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )

    fun backgroundRunningIntents(context: Context): List<Intent> =
        listOf(applicationDetailsIntent(context))

    fun autoStartIntents(
        context: Context,
        manufacturer: DeviceManufacturer = deviceManufacturer(Build.MANUFACTURER.orEmpty())
    ): List<Intent> {
        val manufacturerIntent = when (manufacturer) {
            DeviceManufacturer.Xiaomi -> componentIntent(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            DeviceManufacturer.Huawei -> componentIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
            DeviceManufacturer.Oppo -> componentIntent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            )
            DeviceManufacturer.Vivo -> componentIntent(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            DeviceManufacturer.Other -> null
        }
        return listOfNotNull(manufacturerIntent, applicationDetailsIntent(context))
    }

    fun batteryOptimizationIntents(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        applicationDetailsIntent(context)
    )

    fun batterySaverIntents(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
        applicationDetailsIntent(context)
    )

    private fun componentIntent(packageName: String, className: String): Intent =
        Intent().setComponent(ComponentName(packageName, className))
}

fun firstResolvableSettingsIntent(
    intents: List<Intent>,
    canResolve: (Intent) -> Boolean
): Intent? = intents.firstOrNull(canResolve)

fun launchSettingsIntent(
    intents: List<Intent>,
    fallback: Intent,
    canResolve: (Intent) -> Boolean,
    launch: (Intent) -> Unit
) {
    val selected = firstResolvableSettingsIntent(intents, canResolve) ?: fallback
    runCatching { launch(selected) }
        .recoverCatching {
            if (selected !== fallback) launch(fallback)
        }
}
