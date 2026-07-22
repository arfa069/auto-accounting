package com.autoaccounting.feature.monitoring

import android.provider.Settings
import androidx.activity.ComponentActivity
import com.autoaccounting.feature.billsync.BillSyncPermission
import com.autoaccounting.feature.capture.BookkeepingResultNotificationPermission
import com.autoaccounting.feature.capture.NotificationListenerPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MonitoringStateCoordinatorTest {
    @Test
    fun onResumeRefreshesPermissionAndReliabilityState() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        val coordinator = MonitoringStateCoordinator(activity)

        controller.setup()
        coordinator.onResume()

        assertEquals(
            NotificationListenerPermission.isGranted(activity),
            coordinator.notificationListenerAccessGranted.value
        )
        assertEquals(
            BillSyncPermission.isGranted(activity),
            coordinator.billSyncAccessibilityAccessGranted.value
        )
        assertEquals(
            BookkeepingResultNotificationPermission.isGranted(activity),
            coordinator.resultNotificationPermissionGranted.value
        )
        assertEquals(
            BackgroundReliability.read(activity),
            coordinator.backgroundReliabilityState.value
        )
        assertTrue(coordinator.permissionStateLoaded.value)
    }

    @Test
    fun settingsActionsUseTheirPlatformDestinations() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        val coordinator = MonitoringStateCoordinator(activity)
        controller.create()

        coordinator.openNotificationListenerSettings()
        assertEquals(
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            shadowOf(activity).nextStartedActivity.action
        )

        coordinator.openBillSyncAccessibilitySettings()
        assertEquals(
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
            shadowOf(activity).nextStartedActivity.action
        )
    }

    @Test
    fun launchBillSyncSourceReturnsFalseWhenSourceAppIsUnavailable() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        val coordinator = MonitoringStateCoordinator(activity)
        controller.create()

        assertFalse(coordinator.launchBillSyncSource(com.autoaccounting.feature.billsync.BillSyncSource.WeChat))
    }
}



