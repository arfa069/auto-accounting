package com.bks.feature.billsync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

fun hasBillSyncAccessibilityAccess(
    accessibilityEnabled: Boolean,
    enabledServiceComponents: String?,
    expectedServiceComponent: String
): Boolean {
    if (!accessibilityEnabled) return false
    return enabledServiceComponents
        .orEmpty()
        .split(':')
        .any { it.equals(expectedServiceComponent, ignoreCase = true) }
}

object BillSyncPermission {
    fun isGranted(context: Context): Boolean {
        val accessibilityEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val serviceComponent = ComponentName(
            context,
            BillSyncAccessibilityService::class.java
        ).flattenToString()
        return hasBillSyncAccessibilityAccess(
            accessibilityEnabled = accessibilityEnabled,
            enabledServiceComponents = enabledServices,
            expectedServiceComponent = serviceComponent
        )
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
