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
    fun automaticCaptureCanBeDisabledAndPreservesUserIntentWhenAccessibilityIsRevoked() {
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
        assertTrue(revoked.enabled)
        assertEquals(
            ContinuousMonitoringBlockReason.RequiresBillSyncAccessibilityPermission,
            revoked.blockReason
        )

        val recovered = reduceContinuousMonitoringState(
            revoked,
            ContinuousMonitoringAction.RefreshPermissionHealth(
                ContinuousMonitoringPermissionHealth(
                    billSyncAccessibilityGranted = true
                )
            )
        )
        assertTrue(recovered.enabled)
        assertEquals(null, recovered.blockReason)
    }

    @Test
    fun monitoringRejectsWechatHistoryAndAllowsPaymentResultWithoutManualSync() {
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

        assertEquals(ContinuousMonitoringObservation.Ignored, history.observation)
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
    fun wechatMerchantPaymentRequiresStrongSuccessPageSignature() {
        val state = ContinuousMonitoringState(enabled = true)
        val paymentConversation = decide(
            state,
            "com.tencent.mm",
            "微信支付 消息 支付成功 中国电信 ¥6.99"
        )
        val incompletePaymentResult = decide(
            state,
            "com.tencent.mm",
            "支付成功 中国电信 ¥6.99"
        )
        val ambiguousCompletion = decide(
            state,
            "com.tencent.mm",
            "付款成功 中国电信 ¥6.99"
        )
        val paymentSuccessPage = decide(
            state,
            "com.tencent.mm",
            "支付成功 中国电信 ¥6.99 返回商家"
        )

        assertEquals(ContinuousMonitoringObservation.PaymentRelated, paymentSuccessPage.observation)
        assertEquals(ContinuousMonitoringObservation.Ignored, incompletePaymentResult.observation)
        assertEquals(ContinuousMonitoringObservation.Ignored, ambiguousCompletion.observation)
        assertEquals(ContinuousMonitoringObservation.Ignored, paymentConversation.observation)
    }

    @Test
    fun wechatReceivedRedPacketRequiresCompletedReceiptPageSignature() {
        val state = ContinuousMonitoringState(enabled = true)
        val received = decide(
            state,
            "com.tencent.mm",
            "Yellen的红包\n4.00元\n已存入零钱，可用于发红包\n回复表情到聊天"
        )
        val chat = decide(
            state,
            "com.tencent.mm",
            "聊天\nYellen的红包\n4.00元\n发送消息"
        )
        val sendInitiation = decide(
            state,
            "com.tencent.mm",
            "发红包\n金额 ¥3.50\n塞钱进红包"
        )

        assertEquals(ContinuousMonitoringObservation.PaymentRelated, received.observation)
        assertEquals(ContinuousMonitoringObservation.Ignored, chat.observation)
        assertEquals(ContinuousMonitoringObservation.Ignored, sendInitiation.observation)
    }

    @Test
    fun wechatSentRedPacketRequiresCompletedDetailPageSignature() {
        val state = ContinuousMonitoringState(enabled = true)
        val waiting = decide(
            state,
            "com.tencent.mm",
            "Arfa😘的红包\n红包金额3.00元，等待对方领取\n" +
                "未领取的红包，将于24小时后发起退款"
        )
        val claimed = decide(
            state,
            "com.tencent.mm",
            "Arfa😘的红包\n1个红包共3.00元\nYellen\n3.00元\n11:22"
        )
        val preparation = decide(
            state,
            "com.tencent.mm",
            "发红包\n金额 ¥3.00\n塞钱进红包"
        )
        val paymentPassword = decide(
            state,
            "com.tencent.mm",
            "微信红包\n¥3.00\n支付密码\n零钱"
        )

        assertEquals(ContinuousMonitoringObservation.PaymentRelated, waiting.observation)
        assertEquals(ContinuousMonitoringObservation.PaymentRelated, claimed.observation)
        assertEquals(ContinuousMonitoringObservation.Ignored, preparation.observation)
        assertEquals(ContinuousMonitoringObservation.Ignored, paymentPassword.observation)
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
        val transferAwaitingReceipt = decide(
            state,
            "com.tencent.mm",
            "支付成功 待测试对象确认收款 ¥0.05 完成"
        )
        val genericMessage = decide(
            state,
            "com.eg.android.AlipayGphone",
            "消息 朋友消息提醒 你收到一条聊天消息"
        )
        val resultWithNavigationChrome = decide(
            state,
            "com.eg.android.AlipayGphone",
            "支付成功 ¥20.00 收款方 测试门店 首页 消息 我的"
        )
        val homeRecentMessages = decide(
            state,
            "com.eg.android.AlipayGphone",
            """
                支付宝 首页
                扫一扫 收付款 出行 卡包
                生活缴费 高德打车 红包 我的快递
                花呗 手机营业厅 余额宝 转账
                最近消息 25条新消息
                aitoken-小店 付款成功 ¥85.00 1小时前
                拼多多平台商户 付款成功 ¥5.39 2小时前
                首页 理财 消息 我的
            """.trimIndent()
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
            transferAwaitingReceipt.observation
        )
        assertEquals(
            ContinuousMonitoringObservation.PaymentRelated,
            resultWithNavigationChrome.observation
        )
        assertEquals(ContinuousMonitoringObservation.Ignored, homeRecentMessages.observation)
        assertEquals(ContinuousMonitoringObservation.Ignored, genericMessage.observation)
        assertEquals(ContinuousMonitoringObservation.Ignored, completedTransferInsideChat.observation)
    }

    @Test
    fun monitoringIgnoresAlipayTransactionListOverview() {
        val decision = decide(
            ContinuousMonitoringState(enabled = true),
            "com.eg.android.AlipayGphone",
            """
                搜索交易记录 搜索
                全部 支出 转账 退款 订单 筛选
                7月 支出 ¥2,445.37 收入 ¥0.00
                本月已省0.27元 收支分析
                2026-07-24 12:42:48 搭乘广州地铁 -3.00
                肯德基 -8.50
            """.trimIndent()
        )

        assertEquals(ContinuousMonitoringObservation.Ignored, decision.observation)
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
    fun alipayPaymentResultRequiresAmountEvenWithResultPageSignature() {
        val decision = decide(
            ContinuousMonitoringState(enabled = true),
            "com.eg.android.AlipayGphone",
            "支付成功 收款方 测试门店 交易时间 2026-08-05 12:34"
        )

        assertEquals(ContinuousMonitoringObservation.Ignored, decision.observation)
    }

    @Test
    fun emptyScreenTextIsIgnored() {
        val decision = decide(
            ContinuousMonitoringState(enabled = true),
            "com.tencent.mm",
            ""
        )

        assertEquals(ContinuousMonitoringObservation.Ignored, decision.observation)
    }

    @Test
    fun screenDebouncerSuppressesImmediateDuplicateSnapshots() {
        var now = 1_000L
        val debouncer = PaymentScreenCaptureDebouncer(clock = { now })

        assertTrue(debouncer.shouldProcess("com.tencent.mm", "支付成功 ¥1.00"))
        assertFalse(debouncer.shouldProcess("com.tencent.mm", "支付成功 ¥1.00"))
        assertTrue(
            debouncer.shouldProcess(
                packageName = "com.tencent.mm",
                screenText = "支付成功 ¥1.00",
                bypassDuplicateWindow = true
            )
        )

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
