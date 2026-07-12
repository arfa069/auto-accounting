package com.autoaccounting.feature.capture

import android.provider.Settings
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationListenerPermissionTest {
    @Test
    fun mapsEnabledListenerPackagesToAppAccess() {
        assertTrue(
            hasNotificationListenerAccess(
                enabledListenerPackages = setOf("com.autoaccounting", "com.example.other"),
                applicationPackage = "com.autoaccounting"
            )
        )
        assertFalse(
            hasNotificationListenerAccess(
                enabledListenerPackages = setOf("com.example.other"),
                applicationPackage = "com.autoaccounting"
            )
        )
    }

    @Test
    fun settingsIntentTargetsNotificationListenerAccess() {
        assertEquals(
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            NotificationListenerPermission.settingsIntent().action
        )
    }

    @Test
    fun notificationCaptureStopsWhenAutomaticBookkeepingIsDisabled() {
        assertFalse(
            isAutomaticBookkeepingNotificationCaptureEnabled(ContinuousMonitoringState())
        )
        assertTrue(
            isAutomaticBookkeepingNotificationCaptureEnabled(
                ContinuousMonitoringState(enabled = true)
            )
        )
    }
}
