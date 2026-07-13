package com.autoaccounting.feature.billsync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.autoaccounting.feature.monitoring.ContinuousMonitoringServiceHealth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.Job

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BillSyncAccessibilityServiceHealthTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun interruptionDoesNotReportBoundServiceAsDisconnected() {
        ContinuousMonitoringServiceHealth.markServiceConnected(context, false)
        val controller = Robolectric.buildService(BillSyncAccessibilityService::class.java)
            .create()
        val service = controller.get()

        BillSyncAccessibilityService::class.java
            .getDeclaredMethod("onServiceConnected")
            .apply { isAccessible = true }
            .invoke(service)
        service.onInterrupt()

        assertTrue(ContinuousMonitoringServiceHealth.isServiceConnected(context))
        val heartbeatJob = service.healthHeartbeatJob()
        assertTrue(heartbeatJob.isActive)

        controller.destroy()
        assertFalse(ContinuousMonitoringServiceHealth.isServiceConnected(context))
        assertFalse(heartbeatJob.isActive)
    }

    private fun BillSyncAccessibilityService.healthHeartbeatJob(): Job =
        BillSyncAccessibilityService::class.java
            .getDeclaredField("healthHeartbeatJob")
            .apply { isAccessible = true }
            .get(this) as Job
}
