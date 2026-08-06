package com.autoaccounting.feature.capture

import android.app.Notification
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaymentNotificationListenerServiceTest {
    @Test
    fun notificationTextIncludesInboxStyleLinesWhenPrimaryTextIsIncomplete() {
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TEXT, "完成支付")
            putCharSequenceArray(
                Notification.EXTRA_TEXT_LINES,
                arrayOf("金额 ¥20.00", "收款方 便利店")
            )
        }

        assertEquals(
            "完成支付\n金额 ¥20.00\n收款方 便利店",
            extractNotificationText(extras)
        )
    }
}
