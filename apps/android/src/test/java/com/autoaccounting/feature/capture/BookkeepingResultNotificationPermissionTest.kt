package com.autoaccounting.feature.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookkeepingResultNotificationPermissionTest {
    @Test
    fun requestsOnlyOnAndroid13OrLaterWhenPermissionIsMissing() {
        assertFalse(
            shouldRequestBookkeepingResultNotificationPermission(
                sdkInt = 32,
                isGranted = false
            )
        )
        assertTrue(
            shouldRequestBookkeepingResultNotificationPermission(
                sdkInt = 33,
                isGranted = false
            )
        )
        assertFalse(
            shouldRequestBookkeepingResultNotificationPermission(
                sdkInt = 36,
                isGranted = true
            )
        )
    }
}
