package com.autoaccounting.macrobenchmark

import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Batch10EnvironmentValidationTest {
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun monitoringAdmissionAndHeartbeatPolicyStayBounded() {
        check(device.executeShellCommand("pm clear $TARGET_PACKAGE").contains("Success"))
        val launchResult = device.executeShellCommand(
            "am start -W -n $TARGET_PACKAGE/com.autoaccounting.MainActivity"
        )
        check(launchResult.contains("Status: ok")) { launchResult }
        val result = requireNotNull(
            InstrumentationRegistry.getInstrumentation().context.contentResolver.call(
                DATA_PROVIDER_URI,
                METHOD_MONITORING,
                null,
                Bundle()
            )
        )

        assertEquals(
            EXPECTED_ACCEPTED_EVENT_COUNT,
            result.getInt(RESULT_MONITORING_ACCEPTED_EVENT_COUNT)
        )
        assertEquals(
            EXPECTED_PERSISTED_HEARTBEAT_COUNT,
            result.getInt(RESULT_MONITORING_PERSISTED_HEARTBEAT_COUNT)
        )
        assertTrue(result.getLong(RESULT_MONITORING_EVENT_STORM_MILLIS) >= 0L)
        println(
            "BATCH10_MONITORING accepted=${result.getInt(RESULT_MONITORING_ACCEPTED_EVENT_COUNT)} " +
                "heartbeats=${result.getInt(RESULT_MONITORING_PERSISTED_HEARTBEAT_COUNT)} " +
                "stormMs=${result.getLong(RESULT_MONITORING_EVENT_STORM_MILLIS)}"
        )
    }

    private companion object {
        val DATA_PROVIDER_URI: Uri = Uri.parse("content://$TARGET_PACKAGE.benchmark-data")
        const val TARGET_PACKAGE = "com.autoaccounting.benchmark"
        const val METHOD_MONITORING = "monitoring"
        const val RESULT_MONITORING_ACCEPTED_EVENT_COUNT = "monitoring_accepted_event_count"
        const val RESULT_MONITORING_PERSISTED_HEARTBEAT_COUNT =
            "monitoring_persisted_heartbeat_count"
        const val RESULT_MONITORING_EVENT_STORM_MILLIS = "monitoring_event_storm_millis"
        const val EXPECTED_ACCEPTED_EVENT_COUNT = 4
        const val EXPECTED_PERSISTED_HEARTBEAT_COUNT = 3
    }
}
