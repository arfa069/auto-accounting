package com.autoaccounting.feature.capture

import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.feature.review.ReviewQueueAction
import com.autoaccounting.feature.review.ReviewQueueState
import com.autoaccounting.feature.review.reduceReviewQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCapturePipelineTest {
    @Test
    fun capturedPaymentNotificationBecomesReviewQueueEntry() {
        val pipeline = NotificationCapturePipeline(
            parser = PaymentNotificationParser(),
            captureTimeFormatter = { "2026-07-08 12:21" }
        )

        val entry = pipeline.capture(
            PaymentNotificationEvent(
                packageName = "com.tencent.mm",
                title = "微信支付",
                text = "付款成功 商户：午餐 金额：¥35.90",
                postedAtEpochMillis = NOW
            )
        )

        requireNotNull(entry)
        assertEquals("午餐", entry.title)
        assertEquals(3590, entry.amountMinor)
        assertEquals("通知捕获", entry.captureReasonLabel)
        assertEquals(ConfidenceState.NEEDS_REVIEW, entry.confidence)
        assertEquals(
            "[通知捕获]\n微信支付 付款成功 商户：午餐 金额：¥35.90",
            entry.rawEvidenceText
        )
        assertTrue(entry.parsedFields.contains("来源=微信"))
        assertTrue(entry.parsedFields.contains("金额=35.90"))
    }

    @Test
    fun sameTimestampAndAmountWithDifferentEvidenceProduceDifferentIds() {
        val pipeline = NotificationCapturePipeline()
        val first = requireNotNull(
            pipeline.capture(
                PaymentNotificationEvent(
                    packageName = "com.tencent.mm",
                    title = "微信支付",
                    text = "付款成功 商户：午餐 金额：¥35.90",
                    postedAtEpochMillis = NOW
                )
            )
        )
        val second = requireNotNull(
            pipeline.capture(
                PaymentNotificationEvent(
                    packageName = "com.tencent.mm",
                    title = "微信支付",
                    text = "付款成功 商户：晚餐 金额：¥35.90",
                    postedAtEpochMillis = NOW
                )
            )
        )

        assertTrue(first.id != second.id)
    }

    @Test
    fun reviewQueueReceivesCapturedPendingEntry() {
        val pipeline = NotificationCapturePipeline(
            parser = PaymentNotificationParser(),
            captureTimeFormatter = { "2026-07-08 12:21" }
        )
        val entry = requireNotNull(
            pipeline.capture(
                PaymentNotificationEvent(
                    packageName = "com.eg.android.AlipayGphone",
                    title = "支付宝",
                    text = "支付成功 地铁出行 6.00元",
                    postedAtEpochMillis = NOW
                )
            )
        )

        val state = reduceReviewQueue(
            ReviewQueueState(),
            ReviewQueueAction.AddPending(entry)
        )

        assertEquals(listOf(entry), state.pendingEntries)
        assertEquals(1, state.todaysNewlyCapturedCount)
    }

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
