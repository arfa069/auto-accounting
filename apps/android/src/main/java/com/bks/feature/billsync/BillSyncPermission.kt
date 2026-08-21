package com.bks.feature.billsync

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.ven.assists.AssistsCore

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
    fun isGranted(context: Context): Boolean = AssistsCore.isA11yEnabled(
        context,
        BillSyncAccessibilityService::class.java
    )

    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
