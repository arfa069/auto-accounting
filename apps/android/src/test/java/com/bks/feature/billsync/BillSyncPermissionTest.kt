package com.bks.feature.billsync

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BillSyncPermissionTest {
    @Test
    fun mapsEnabledAccessibilityServiceToAppAccess() {
        val expected = "com.bks/.feature.billsync.BillSyncAccessibilityService"

        assertTrue(
            hasBillSyncAccessibilityAccess(
                accessibilityEnabled = true,
                enabledServiceComponents = "com.example/.Other:$expected",
                expectedServiceComponent = expected
            )
        )
        assertFalse(
            hasBillSyncAccessibilityAccess(
                accessibilityEnabled = false,
                enabledServiceComponents = expected,
                expectedServiceComponent = expected
            )
        )
        assertFalse(
            hasBillSyncAccessibilityAccess(
                accessibilityEnabled = true,
                enabledServiceComponents = "com.example/.Other",
                expectedServiceComponent = expected
            )
        )
    }

    @Test
    fun settingsIntentTargetsAccessibilitySettings() {
        assertEquals(
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
            BillSyncPermission.settingsIntent().action
        )
    }
}
