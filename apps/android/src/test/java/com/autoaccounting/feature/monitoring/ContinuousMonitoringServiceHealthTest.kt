package com.autoaccounting.feature.monitoring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContinuousMonitoringServiceHealthTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun serviceConnectionHealthTracksConnectionAndInterruption() {
        ContinuousMonitoringServiceHealth.markServiceConnected(context, false)
        assertFalse(ContinuousMonitoringServiceHealth.isServiceConnected(context))

        ContinuousMonitoringServiceHealth.markServiceConnected(context, true)
        assertTrue(ContinuousMonitoringServiceHealth.isServiceConnected(context))

        ContinuousMonitoringServiceHealth.markServiceConnected(context, false)
        assertFalse(ContinuousMonitoringServiceHealth.isServiceConnected(context))
    }

    @Test
    fun staleServiceHeartbeatIsReportedAsDisconnected() {
        val connectedAt = 1_000L
        ContinuousMonitoringServiceHealth.markServiceConnected(
            context = context,
            connected = true,
            nowEpochMillis = connectedAt
        )

        assertTrue(
            ContinuousMonitoringServiceHealth.isServiceConnected(
                context = context,
                nowEpochMillis = connectedAt + SERVICE_CONNECTION_TIMEOUT_MILLIS
            )
        )
        assertFalse(
            ContinuousMonitoringServiceHealth.isServiceConnected(
                context = context,
                nowEpochMillis = connectedAt + SERVICE_CONNECTION_TIMEOUT_MILLIS + 1
            )
        )
    }
}
