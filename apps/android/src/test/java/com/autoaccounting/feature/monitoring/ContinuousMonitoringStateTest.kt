package com.autoaccounting.feature.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousMonitoringStateTest {
    @Test
    fun enabledAutomaticBookkeepingRequiresNotificationListenerAccess() {
        val status = summarizeAutomaticBookkeeping(
            state = ContinuousMonitoringState(enabled = true),
            notificationListenerAccessGranted = false,
            permissionHealth = ContinuousMonitoringPermissionHealth(
                billSyncAccessibilityGranted = true
            )
        )

        assertEquals(
            AutomaticBookkeepingStatus.RequiresAttention(
                AutomaticBookkeepingAttentionReason.RequiresNotificationListenerAccess
            ),
            status
        )
    }

    @Test
    fun automaticBookkeepingSummaryIsReadyWithBothCapturePermissionsAndClosedWhenDisabled() {
        val healthyPermissions = ContinuousMonitoringPermissionHealth(
            billSyncAccessibilityGranted = true
        )

        assertEquals(
            AutomaticBookkeepingStatus.Ready,
            summarizeAutomaticBookkeeping(
                state = ContinuousMonitoringState(enabled = true),
                notificationListenerAccessGranted = true,
                permissionHealth = healthyPermissions
            )
        )
        assertEquals(
            AutomaticBookkeepingStatus.Disabled,
            summarizeAutomaticBookkeeping(
                state = ContinuousMonitoringState(),
                notificationListenerAccessGranted = false,
                permissionHealth = ContinuousMonitoringPermissionHealth()
            )
        )
    }

    @Test
    fun automaticBookkeepingSummaryKeepsServiceInterruptionVisibleUntilUserRepairsIt() {
        val status = summarizeAutomaticBookkeeping(
            state = ContinuousMonitoringState(
                blockReason = ContinuousMonitoringBlockReason
                    .RequiresBillSyncAccessibilityPermission
            ),
            notificationListenerAccessGranted = true,
            permissionHealth = ContinuousMonitoringPermissionHealth(
                billSyncAccessibilityGranted = true
            )
        )

        assertEquals(
            AutomaticBookkeepingStatus.RequiresAttention(
                AutomaticBookkeepingAttentionReason.RequiresAccessibilityPermission
            ),
            status
        )
    }

    @Test
    fun automaticBookkeepingSummaryRequiresAttentionWhenAccessibilityServiceIsDisconnected() {
        val status = summarizeAutomaticBookkeeping(
            state = ContinuousMonitoringState(enabled = true),
            notificationListenerAccessGranted = true,
            permissionHealth = ContinuousMonitoringPermissionHealth(
                billSyncAccessibilityGranted = true,
                billSyncAccessibilityServiceConnected = false
            )
        )

        assertEquals(
            AutomaticBookkeepingStatus.RequiresAttention(
                AutomaticBookkeepingAttentionReason.RequiresAccessibilityServiceConnection
            ),
            status
        )
    }

    @Test
    fun automaticCaptureRequiresAccessibilityButNotBillSyncOrNotificationListener() {
        val enabled = reduceContinuousMonitoringState(
            ContinuousMonitoringState(),
            ContinuousMonitoringAction.Enable(
                ContinuousMonitoringPermissionHealth(
                    billSyncAccessibilityGranted = true
                )
            )
        )

        assertTrue(enabled.enabled)
        assertEquals(null, enabled.blockReason)

        val blocked = reduceContinuousMonitoringState(
            ContinuousMonitoringState(),
            ContinuousMonitoringAction.Enable(
                ContinuousMonitoringPermissionHealth(
                    billSyncAccessibilityGranted = false
                )
            )
        )

        assertFalse(blocked.enabled)
        assertEquals(
            ContinuousMonitoringBlockReason.RequiresBillSyncAccessibilityPermission,
            blocked.blockReason
        )
    }

    @Test
    fun automaticCaptureCanBeDisabledAndStopsWhenAccessibilityIsRevoked() {
        val enabled = ContinuousMonitoringState(enabled = true)

        assertFalse(
            reduceContinuousMonitoringState(enabled, ContinuousMonitoringAction.Disable).enabled
        )

        val revoked = reduceContinuousMonitoringState(
            enabled,
            ContinuousMonitoringAction.RefreshPermissionHealth(
                ContinuousMonitoringPermissionHealth(
                    billSyncAccessibilityGranted = false
                )
            )
        )
        assertFalse(revoked.enabled)
        assertEquals(
            ContinuousMonitoringBlockReason.RequiresBillSyncAccessibilityPermission,
            revoked.blockReason
        )
    }

    @Test
    fun monitoringAllowsPaymentHistoryAndResultSurfacesWithoutManualSync() {
        val state = ContinuousMonitoringState(enabled = true)
        val history = decide(
            state,
            "com.tencent.mm",
            "微信支付 账单 2026-07-08 12:20 午餐 支出 ¥35.90"
        )
        val result = decide(
            state,
            "com.eg.android.AlipayGphone",
            "支付成功 ¥12.34 收款方 测试门店"
        )

        assertEquals(ContinuousMonitoringObservation.PaymentRelated, history.observation)
        assertEquals(ContinuousMonitoringObservation.PaymentRelated, result.observation)
    }

    @Test
    fun monitoringKeepsChatsAndPaymentInitiationSurfacesDenied() {
        val state = ContinuousMonitoringState(enabled = true)
        val chat = decide(state, "com.tencent.mm", "聊天 消息 微信支付助手")
        val cashier = decide(
            state,
            "com.eg.android.AlipayGphone",
            "支付宝 收银台 立即付款 ¥20.00 确认支付"
        )
        val transferSend = decide(
            state,
            "com.tencent.mm",
            "转账给 测试对象 转账金额 ¥20.00 添加转账说明"
        )

        assertEquals(ContinuousMonitoringObservation.Ignored, chat.observation)
        assertEquals(ContinuousMonitoringObservation.Ignored, cashier.observation)
        assertEquals(ContinuousMonitoringObservation.Ignored, transferSend.observation)
    }

    @Test
    fun monitoringAllowsPaymentMessageCenterAndCompletedTransfer() {
        val state = ContinuousMonitoringState(enabled = true)
        val paymentMessage = decide(
            state,
            "com.eg.android.AlipayGphone",
            "消息 消息盒子 支付信息 支付成功 商户 金额 ¥20.00 交易时间 2026-07-10 09:12"
        )
        val transferResult = decide(
            state,
            "com.tencent.mm",
            "转账成功 ¥0.01 对方 测试对象"
        )
        val genericMessage = decide(
            state,
            "com.eg.android.AlipayGphone",
            "消息 朋友消息提醒 你收到一条聊天消息"
        )
        val resultWithNavigationChrome = decide(
            state,
            "com.eg.android.AlipayGphone",
            "支付成功 ¥20.00 首页 消息 我的"
        )
        val completedTransferInsideChat = decide(
            state,
            "com.tencent.mm",
            "聊天记录 转账成功 ¥20.00 发送消息"
        )

        assertEquals(ContinuousMonitoringObservation.PaymentRelated, paymentMessage.observation)
        assertEquals(ContinuousMonitoringObservation.PaymentRelated, transferResult.observation)
        assertEquals(
            ContinuousMonitoringObservation.PaymentRelated,
            resultWithNavigationChrome.observation
        )
        assertEquals(ContinuousMonitoringObservation.Ignored, genericMessage.observation)
        assertEquals(ContinuousMonitoringObservation.Ignored, completedTransferInsideChat.observation)
    }

    @Test
    fun monitoringRejectsOtherPackagesAndMissingAccessibility() {
        val eventText = "支付成功 ¥12.34"
        val otherPackage = decide(
            ContinuousMonitoringState(enabled = true),
            "com.example.chat",
            eventText
        )
        val missingPermission = decideContinuousMonitoringCapture(
            state = ContinuousMonitoringState(enabled = true),
            event = ContinuousMonitoringEvent("com.tencent.mm", eventText),
            permissionHealth = ContinuousMonitoringPermissionHealth(
                billSyncAccessibilityGranted = false
            )
        )

        assertEquals(ContinuousMonitoringObservation.Ignored, otherPackage.observation)
        assertEquals(ContinuousMonitoringObservation.Disabled, missingPermission.observation)
    }

    @Test
    fun screenDebouncerSuppressesImmediateDuplicateSnapshots() {
        var now = 1_000L
        val debouncer = PaymentScreenCaptureDebouncer(clock = { now })

        assertTrue(debouncer.shouldProcess("com.tencent.mm", "支付成功 ¥1.00"))
        assertFalse(debouncer.shouldProcess("com.tencent.mm", "支付成功 ¥1.00"))

        now += 30_001
        assertTrue(debouncer.shouldProcess("com.tencent.mm", "支付成功 ¥1.00"))
    }

    private fun decide(
        state: ContinuousMonitoringState,
        packageName: String,
        screenText: String
    ): ContinuousMonitoringCaptureDecision = decideContinuousMonitoringCapture(
        state = state,
        event = ContinuousMonitoringEvent(packageName, screenText),
        permissionHealth = healthyPermissions
    )

    private companion object {
        val healthyPermissions = ContinuousMonitoringPermissionHealth(
            billSyncAccessibilityGranted = true
        )
    }
}
