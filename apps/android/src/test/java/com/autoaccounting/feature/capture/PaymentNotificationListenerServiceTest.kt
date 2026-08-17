package com.autoaccounting.feature.capture

import android.app.Notification
import android.os.Bundle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun notificationEvidenceCanBeClaimedOnceBeforeRoomFallback() = runTest {
        val trigger = PaymentNotificationCaptureTrigger(
            packageName = "com.eg.android.AlipayGphone",
            captureId = "notification-1",
            amountMinor = 2_000L,
            notificationTimeEpochMillis = 123L,
            rawNotificationEvidence = "[通知捕获]\n支付成功 ¥20.00"
        )
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            PaymentNotificationCaptureTriggers.events
                .filter { it.captureId == trigger.captureId }
                .first()
        }

        PaymentNotificationCaptureTriggers.publish(trigger)

        assertEquals(trigger, received.await())
        assertEquals(
            trigger,
            PaymentNotificationCaptureTriggers.pendingFor(
                packageName = trigger.packageName,
                nowEpochMillis = trigger.publishedAtEpochMillis + 1_000L
            )
        )
        assertTrue(PaymentNotificationCaptureTriggers.tryClaimFusion(trigger.captureId))
        assertFalse(PaymentNotificationCaptureTriggers.tryClaimFallback(trigger.captureId))
        PaymentNotificationCaptureTriggers.complete(trigger.captureId)
        assertNull(PaymentNotificationCaptureTriggers.pendingFor(trigger.packageName))
    }

    @Test
    fun persistedNotificationTriggerExpiresWhenNoPaymentWindowConsumesIt() {
        val trigger = PaymentNotificationCaptureTrigger(
            packageName = "com.eg.android.AlipayGphone",
            captureId = "notification-expired",
            amountMinor = 2_000L,
            notificationTimeEpochMillis = 123L,
            rawNotificationEvidence = "[通知捕获]\n支付成功 ¥20.00",
            publishedAtEpochMillis = 1_000L
        )

        PaymentNotificationCaptureTriggers.publish(trigger)

        assertNull(
            PaymentNotificationCaptureTriggers.pendingFor(
                packageName = trigger.packageName,
                nowEpochMillis = 1_000L + PAYMENT_NOTIFICATION_TRIGGER_TTL_MILLIS + 1L
            )
        )
    }

    @Test
    fun notificationOnlyFallbackUsesSameClaimForWechatAndAlipay() {
        listOf("com.tencent.mm", "com.eg.android.AlipayGphone").forEachIndexed { index, packageName ->
            val trigger = PaymentNotificationCaptureTrigger(
                packageName = packageName,
                captureId = "notification-$index",
                amountMinor = 2_000L,
                notificationTimeEpochMillis = 123L,
                rawNotificationEvidence = "[通知捕获]\n支付成功 ¥20.00"
            )

            PaymentNotificationCaptureTriggers.publish(trigger)

            assertTrue(PaymentNotificationCaptureTriggers.tryClaimFallback(trigger.captureId))
            assertFalse(PaymentNotificationCaptureTriggers.tryClaimFusion(trigger.captureId))
            PaymentNotificationCaptureTriggers.complete(trigger.captureId)
        }
    }

    @Test
    fun accessibilityStartedWindowReceivesLaterNotification() = runTest {
        val awaited = async(start = CoroutineStart.UNDISPATCHED) {
            PaymentNotificationCaptureTriggers.awaitPendingFor("com.tencent.mm", waitMillis = 1_000L)
        }
        val trigger = PaymentNotificationCaptureTrigger(
            packageName = "com.tencent.mm",
            captureId = "notification-later",
            amountMinor = 699L,
            notificationTimeEpochMillis = 123L,
            rawNotificationEvidence = "[通知捕获]\n微信支付成功 6.99元"
        )

        PaymentNotificationCaptureTriggers.publish(trigger)

        assertEquals(trigger, awaited.await())
        PaymentNotificationCaptureTriggers.complete(trigger.captureId)
    }
}
