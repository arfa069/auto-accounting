package com.autoaccounting.feature.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentNotificationReplayTest {
    @Test
    fun replaysRecentAllowedPaymentNotifications() {
        assertTrue(
            shouldReplayActivePaymentNotification(
                packageName = "com.tencent.mm",
                postedAtEpochMillis = NOW - 30 * 60 * 1000,
                nowEpochMillis = NOW
            )
        )
    }

    @Test
    fun rejectsExpiredFutureAndUnrelatedNotifications() {
        assertFalse(
            shouldReplayActivePaymentNotification(
                packageName = "com.tencent.mm",
                postedAtEpochMillis = NOW - 61 * 60 * 1000,
                nowEpochMillis = NOW
            )
        )
        assertFalse(
            shouldReplayActivePaymentNotification(
                packageName = "com.tencent.mm",
                postedAtEpochMillis = NOW + 1,
                nowEpochMillis = NOW
            )
        )
        assertFalse(
            shouldReplayActivePaymentNotification(
                packageName = "com.example.chat",
                postedAtEpochMillis = NOW,
                nowEpochMillis = NOW
            )
        )
    }

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
