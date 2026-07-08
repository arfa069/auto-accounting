package com.autoaccounting.feature.capture

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

fun hasNotificationListenerAccess(
    enabledListenerPackages: Set<String>,
    applicationPackage: String
): Boolean = applicationPackage in enabledListenerPackages

object NotificationListenerPermission {
    fun isGranted(context: Context): Boolean = hasNotificationListenerAccess(
        enabledListenerPackages = NotificationManagerCompat.getEnabledListenerPackages(context),
        applicationPackage = context.packageName
    )

    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
}
