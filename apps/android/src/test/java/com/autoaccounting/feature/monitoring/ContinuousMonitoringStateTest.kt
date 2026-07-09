package com.autoaccounting.feature.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousMonitoringStateTest {
    @Test
    fun monitoringCannotBeEnabledUntilBillSyncAndPermissionsAreHealthy() {
        val blocked = reduceContinuousMonitoringState(
            ContinuousMonitoringState(),
            ContinuousMonitoringAction.Enable(healthyPermissions)
        )

        assertFalse(blocked.enabled)
        assertEquals(ContinuousMonitoringBlockReason.RequiresBillSyncFirst, blocked.blockReason)

        val missingNotification = reduceContinuousMonitoringState(
            ContinuousMonitoringState(billSyncCompleted = true),
            ContinuousMonitoringAction.Enable(
                ContinuousMonitoringPermissionHealth(
                    notificationListenerGranted = false,
                    billSyncAccessibilityGranted = true
                )
            )
        )
        assertFalse(missingNotification.enabled)
        assertEquals(
            ContinuousMonitoringBlockReason.RequiresNotificationListenerPermission,
            missingNotification.blockReason
        )

        val missingAccessibility = reduceContinuousMonitoringState(
            ContinuousMonitoringState(billSyncCompleted = true),
            ContinuousMonitoringAction.Enable(
                ContinuousMonitoringPermissionHealth(
                    notificationListenerGranted = true,
                    billSyncAccessibilityGranted = false
                )
            )
        )
        assertFalse(missingAccessibility.enabled)
        assertEquals(
            ContinuousMonitoringBlockReason.RequiresBillSyncAccessibilityPermission,
            missingAccessibility.blockReason
        )

        val enabled = reduceContinuousMonitoringState(
            ContinuousMonitoringState(billSyncCompleted = true),
            ContinuousMonitoringAction.Enable(healthyPermissions)
        )

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
    fun monitoringStopsWhenPermissionHealthBecomesUnhealthy() {
        val enabled = ContinuousMonitoringState(
            billSyncCompleted = true,
            enabled = true
        )

        val disabled = reduceContinuousMonitoringState(
            enabled,
            ContinuousMonitoringAction.RefreshPermissionHealth(
                ContinuousMonitoringPermissionHealth(
                    notificationListenerGranted = false,
                    billSyncAccessibilityGranted = true
                )
            )
        )

        assertFalse(disabled.enabled)
        assertEquals(
            ContinuousMonitoringBlockReason.RequiresNotificationListenerPermission,
            disabled.blockReason
        )
    }

    @Test
    fun monitoringOnlyKeepsPaymentHistorySurfaces() {
        val state = ContinuousMonitoringState(billSyncCompleted = true, enabled = true)

        val payment = decideContinuousMonitoringCapture(
            state = state,
            event = ContinuousMonitoringEvent(
                packageName = "com.tencent.mm",
                screenText = "微信支付 账单 2026-07-08 12:20 午餐 支出 ¥35.90"
            ),
            permissionHealth = healthyPermissions
        )
        val chat = decideContinuousMonitoringCapture(
            state = state,
            event = ContinuousMonitoringEvent(
                packageName = "com.tencent.mm",
                screenText = "聊天 消息 微信支付助手"
            ),
            permissionHealth = healthyPermissions
        )
        val paymentInitiation = decideContinuousMonitoringCapture(
            state = state,
            event = ContinuousMonitoringEvent(
                packageName = "com.eg.android.AlipayGphone",
                screenText = "支付宝 收银台 立即付款 确认支付"
            ),
            permissionHealth = healthyPermissions
        )
        val transfer = decideContinuousMonitoringCapture(
            state = state,
            event = ContinuousMonitoringEvent(
                packageName = "com.tencent.mm",
                screenText = "转账给 小明 ¥20.00"
            ),
            permissionHealth = healthyPermissions
        )
        val otherPackage = decideContinuousMonitoringCapture(
            state = state,
            event = ContinuousMonitoringEvent(
                packageName = "com.example.chat",
                screenText = "账单 2026-07-08 12:20 午餐 支出 ¥35.90"
            ),
            permissionHealth = healthyPermissions
        )

        assertEquals(ContinuousMonitoringObservation.PaymentRelated, payment.observation)
        assertEquals("com.tencent.mm", payment.packageName)
        assertEquals(ContinuousMonitoringObservation.Ignored, chat.observation)
        assertEquals(ContinuousMonitoringObservation.Ignored, paymentInitiation.observation)
        assertEquals(ContinuousMonitoringObservation.Ignored, transfer.observation)
        assertEquals(ContinuousMonitoringObservation.Ignored, otherPackage.observation)
    }

    @Test
    fun monitoringDecisionIsDisabledWhenStateOrPermissionsAreUnhealthy() {
        val event = ContinuousMonitoringEvent(
            packageName = "com.tencent.mm",
            screenText = "微信支付 账单 2026-07-08 12:20 午餐 支出 ¥35.90"
        )

        val disabledState = decideContinuousMonitoringCapture(
            state = ContinuousMonitoringState(billSyncCompleted = true, enabled = false),
            event = event,
            permissionHealth = healthyPermissions
        )
        val missingPermission = decideContinuousMonitoringCapture(
            state = ContinuousMonitoringState(billSyncCompleted = true, enabled = true),
            event = event,
            permissionHealth = ContinuousMonitoringPermissionHealth(
                notificationListenerGranted = true,
                billSyncAccessibilityGranted = false
            )
        )

        assertEquals(ContinuousMonitoringObservation.Disabled, disabledState.observation)
        assertEquals(ContinuousMonitoringObservation.Disabled, missingPermission.observation)
    }

    private companion object {
        val healthyPermissions = ContinuousMonitoringPermissionHealth(
            notificationListenerGranted = true,
            billSyncAccessibilityGranted = true
        )
    }
}
