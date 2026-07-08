package com.autoaccounting.feature.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousMonitoringStateTest {
    @Test
    fun monitoringCannotBeEnabledUntilBillSyncHasBeenTried() {
        val blocked = reduceContinuousMonitoringState(
            ContinuousMonitoringState(),
            ContinuousMonitoringAction.Enable
        )

        assertFalse(blocked.enabled)
        assertEquals(ContinuousMonitoringBlockReason.RequiresBillSyncFirst, blocked.blockReason)

        val afterBillSync = reduceContinuousMonitoringState(
            blocked,
            ContinuousMonitoringAction.MarkBillSyncCompleted
        )
        val enabled = reduceContinuousMonitoringState(afterBillSync, ContinuousMonitoringAction.Enable)

        assertTrue(enabled.enabled)
        assertEquals(null, enabled.blockReason)
    }

    @Test
    fun monitoringCanBeDisabledAtAnyTime() {
        val enabled = ContinuousMonitoringState(
            billSyncCompleted = true,
            enabled = true
        )

        val disabled = reduceContinuousMonitoringState(enabled, ContinuousMonitoringAction.Disable)

        assertFalse(disabled.enabled)
    }

    @Test
    fun monitoringOnlyKeepsPaymentRelatedObservations() {
        val state = ContinuousMonitoringState(billSyncCompleted = true, enabled = true)

        val payment = observeContinuousMonitoringActivity(
            state = state,
            event = ContinuousMonitoringEvent(
                packageName = "com.tencent.mm",
                screenText = "微信支付 账单 收款 35.90"
            )
        )
        val chat = observeContinuousMonitoringActivity(
            state = state,
            event = ContinuousMonitoringEvent(
                packageName = "com.tencent.mm",
                screenText = "聊天 发送消息"
            )
        )

        assertEquals(ContinuousMonitoringObservation.PaymentRelated, payment)
        assertEquals(ContinuousMonitoringObservation.Ignored, chat)
    }
}
