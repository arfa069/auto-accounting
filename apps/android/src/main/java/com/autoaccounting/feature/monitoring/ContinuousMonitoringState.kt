package com.autoaccounting.feature.monitoring

data class ContinuousMonitoringState(
    val enabled: Boolean = false,
    val blockReason: ContinuousMonitoringBlockReason? = null
)

data class ContinuousMonitoringPermissionHealth(
    val billSyncAccessibilityGranted: Boolean = false,
    val billSyncAccessibilityServiceConnected: Boolean = true
) {
    val isHealthy: Boolean
        get() = billSyncAccessibilityGranted && billSyncAccessibilityServiceConnected

    val firstBlockReason: ContinuousMonitoringBlockReason?
        get() = when {
            !billSyncAccessibilityGranted ->
                ContinuousMonitoringBlockReason.RequiresBillSyncAccessibilityPermission
            !billSyncAccessibilityServiceConnected ->
                ContinuousMonitoringBlockReason.AccessibilityServiceDisconnected
            else -> null
    }
}

sealed interface AutomaticBookkeepingStatus {
    data object Ready : AutomaticBookkeepingStatus
    data object Disabled : AutomaticBookkeepingStatus
    data class RequiresAttention(
        val reason: AutomaticBookkeepingAttentionReason
    ) : AutomaticBookkeepingStatus
}

enum class AutomaticBookkeepingAttentionReason {
    RequiresNotificationListenerAccess,
    RequiresAccessibilityPermission,
    RequiresAccessibilityServiceConnection
}

enum class ContinuousMonitoringBlockReason {
    RequiresBillSyncAccessibilityPermission,
    AccessibilityServiceDisconnected
}

fun summarizeAutomaticBookkeeping(
    state: ContinuousMonitoringState,
    notificationListenerAccessGranted: Boolean,
    permissionHealth: ContinuousMonitoringPermissionHealth
): AutomaticBookkeepingStatus = when {
    state.blockReason != null -> AutomaticBookkeepingStatus.RequiresAttention(
        state.blockReason.toAutomaticBookkeepingAttentionReason()
    )

    !state.enabled -> AutomaticBookkeepingStatus.Disabled
    !notificationListenerAccessGranted -> AutomaticBookkeepingStatus.RequiresAttention(
        AutomaticBookkeepingAttentionReason.RequiresNotificationListenerAccess
    )

    !permissionHealth.isHealthy -> AutomaticBookkeepingStatus.RequiresAttention(
        requireNotNull(permissionHealth.firstBlockReason)
            .toAutomaticBookkeepingAttentionReason()
    )

    else -> AutomaticBookkeepingStatus.Ready
}

private fun ContinuousMonitoringBlockReason.toAutomaticBookkeepingAttentionReason():
    AutomaticBookkeepingAttentionReason = when (this) {
    ContinuousMonitoringBlockReason.RequiresBillSyncAccessibilityPermission ->
        AutomaticBookkeepingAttentionReason.RequiresAccessibilityPermission
    ContinuousMonitoringBlockReason.AccessibilityServiceDisconnected ->
        AutomaticBookkeepingAttentionReason.RequiresAccessibilityServiceConnection
}

sealed interface ContinuousMonitoringAction {
    data class Enable(
        val permissionHealth: ContinuousMonitoringPermissionHealth
    ) : ContinuousMonitoringAction
    data object Disable : ContinuousMonitoringAction
    data class RefreshPermissionHealth(
        val permissionHealth: ContinuousMonitoringPermissionHealth
    ) : ContinuousMonitoringAction
}

data class ContinuousMonitoringEvent(
    val packageName: String,
    val screenText: String
)

enum class ContinuousMonitoringObservation {
    PaymentRelated,
    Ignored,
    Disabled
}

data class ContinuousMonitoringCaptureDecision(
    val observation: ContinuousMonitoringObservation,
    val packageName: String? = null
) {
    val shouldCapture: Boolean
        get() = observation == ContinuousMonitoringObservation.PaymentRelated &&
            packageName != null
}

fun reduceContinuousMonitoringState(
    state: ContinuousMonitoringState,
    action: ContinuousMonitoringAction
): ContinuousMonitoringState = when (action) {
    is ContinuousMonitoringAction.Enable -> {
        val blockReason = action.permissionHealth.firstBlockReason
        if (blockReason == null) {
            state.copy(enabled = true, blockReason = null)
        } else {
            state.copy(enabled = false, blockReason = blockReason)
        }
    }

    ContinuousMonitoringAction.Disable -> state.copy(enabled = false, blockReason = null)

    is ContinuousMonitoringAction.RefreshPermissionHealth -> {
        if (state.enabled) {
            state.copy(blockReason = action.permissionHealth.firstBlockReason)
        } else {
            state.copy(blockReason = null)
        }
    }
}

fun decideContinuousMonitoringCapture(
    state: ContinuousMonitoringState,
    event: ContinuousMonitoringEvent,
    permissionHealth: ContinuousMonitoringPermissionHealth
): ContinuousMonitoringCaptureDecision {
    val observation = observeContinuousMonitoringActivity(
        state = state,
        event = event,
        permissionHealth = permissionHealth
    )
    return ContinuousMonitoringCaptureDecision(
        observation = observation,
        packageName = event.packageName.takeIf {
            observation == ContinuousMonitoringObservation.PaymentRelated
        }
    )
}

fun observeContinuousMonitoringActivity(
    state: ContinuousMonitoringState,
    event: ContinuousMonitoringEvent,
    permissionHealth: ContinuousMonitoringPermissionHealth
): ContinuousMonitoringObservation {
    if (!state.enabled || !permissionHealth.isHealthy) {
        return ContinuousMonitoringObservation.Disabled
    }
    return if (event.isPaymentHistorySurface()) {
        ContinuousMonitoringObservation.PaymentRelated
    } else {
        ContinuousMonitoringObservation.Ignored
    }
}

fun isContinuousMonitoringPackageAllowed(packageName: String): Boolean =
    packageName in PAYMENT_SOURCE_PACKAGES

private fun ContinuousMonitoringEvent.isPaymentHistorySurface(): Boolean {
    if (!isContinuousMonitoringPackageAllowed(packageName)) return false

    val text = screenText.lowercase()
    return when (packageName) {
        "com.tencent.mm" -> if (
            PAYMENT_INITIATION_DENY_KEYWORDS.any { keyword -> text.contains(keyword) }
        ) {
            false
        } else if (
            hasWechatReceivedRedPacketSuccessSignature(text) ||
            hasWechatSentRedPacketSuccessSignature(text)
        ) {
            PAYMENT_AMOUNT_REGEX.containsMatchIn(text) &&
                ACTIVE_CHAT_INPUT_KEYWORDS.none { keyword -> text.contains(keyword) }
        } else {
            val hasCompletedPayment =
                PAYMENT_COMPLETION_KEYWORDS.any { keyword -> text.contains(keyword) } &&
                    PAYMENT_AMOUNT_REGEX.containsMatchIn(text)
            val hasSupportedCompletion = hasWechatMerchantPaymentSuccessSignature(text) ||
                hasWechatTransferCompletionContext(text)
            hasCompletedPayment &&
                hasSupportedCompletion &&
                CHAT_OR_GENERIC_MESSAGE_DENY_KEYWORDS.none { keyword -> text.contains(keyword) } &&
                ACTIVE_CHAT_INPUT_KEYWORDS.none { keyword -> text.contains(keyword) }
        }
        ALIPAY_PACKAGE_NAME -> text.isAlipayPaymentHistorySurface()
        else -> false
    }
}

private fun String.isAlipayPaymentHistorySurface(): Boolean {
    if (hasAlipayTransactionListPageSignature(this)) return false
    if (PAYMENT_INITIATION_DENY_KEYWORDS.any { keyword -> contains(keyword) }) return false

    val hasCompletedPayment = PAYMENT_COMPLETION_KEYWORDS.any { keyword -> contains(keyword) } &&
        PAYMENT_AMOUNT_REGEX.containsMatchIn(this)
    if (hasCompletedPayment) {
        return hasAlipayPaymentResultPageSignature(this) &&
            ACTIVE_CHAT_INPUT_KEYWORDS.none { keyword -> contains(keyword) }
    }
    if (
        CHAT_OR_GENERIC_MESSAGE_DENY_KEYWORDS.any { keyword -> contains(keyword) } &&
        PAYMENT_MESSAGE_CENTER_KEYWORDS.none { keyword -> contains(keyword) }
    ) {
        return false
    }
    if (
        TRANSFER_OR_RED_PACKET_KEYWORDS.any { keyword -> contains(keyword) } &&
        PAYMENT_RECORD_CONTEXT_KEYWORDS.none { keyword -> contains(keyword) }
    ) {
        return false
    }
    return PAYMENT_AMOUNT_REGEX.containsMatchIn(this) &&
        (
            PAYMENT_HISTORY_KEYWORDS.any { keyword -> contains(keyword) } ||
                PAYMENT_MESSAGE_CENTER_KEYWORDS.any { keyword -> contains(keyword) }
            )
}

internal fun hasAlipayPaymentResultPageSignature(screenText: String): Boolean =
    ALIPAY_PAYMENT_RESULT_CONTEXT_KEYWORDS.any { keyword -> screenText.contains(keyword) }

internal fun hasAlipayTransactionListPageSignature(screenText: String): Boolean {
    val text = screenText.lowercase()
    return text.contains("搜索交易记录") ||
        (
            text.contains("收支分析") &&
                text.contains("筛选") &&
                text.contains("全部")
            )
}

private val PAYMENT_SOURCE_PACKAGES = setOf(
    "com.tencent.mm",
    ALIPAY_PACKAGE_NAME
)

private const val ALIPAY_PACKAGE_NAME = "com.eg.android.AlipayGphone"

private val PAYMENT_HISTORY_KEYWORDS = listOf(
    "账单",
    "交易记录",
    "支付记录",
    "收支明细",
    "账单明细",
    "交易详情",
    "账单详情",
    "支付信息",
    "消息盒子",
    "支付凭证",
    "零钱明细",
    "转账记录",
    "红包记录",
    "退款",
    "支出",
    "收入",
    "bill",
    "transaction history",
    "payment record",
    "payment history",
    "receipt"
)

private val PAYMENT_MESSAGE_CENTER_KEYWORDS = listOf(
    "支付信息",
    "支付凭证",
    "消息盒子",
    "交易详情",
    "账单详情",
    "payment record",
    "receipt"
)

private val PAYMENT_COMPLETION_KEYWORDS = listOf(
    "支付成功",
    "完成支付",
    "支付完成",
    "付款成功",
    "交易成功",
    "转账成功",
    "收款成功",
    "退款成功",
    "红包发送成功",
    "已支付",
    "已付款",
    "payment successful",
    "payment complete"
)

private val PAYMENT_RECORD_CONTEXT_KEYWORDS = listOf(
    "账单",
    "交易记录",
    "支付记录",
    "账单详情",
    "交易详情",
    "支付信息",
    "收支明细",
    "零钱明细",
    "转账记录",
    "红包记录",
    "交易时间",
    "当前状态",
    "payment record",
    "payment history",
    "receipt"
)

private val ALIPAY_PAYMENT_RESULT_CONTEXT_KEYWORDS = listOf(
    "收款方",
    "付款方式",
    "支付方式",
    "交易方式",
    "付款渠道",
    "交易时间",
    "订单号",
    "交易单号",
    "支付信息",
    "支付凭证",
    "交易详情",
    "账单详情",
    "查看账单",
    "返回首页"
)

private val TRANSFER_OR_RED_PACKET_KEYWORDS = listOf(
    "转账",
    "红包",
    "transfer"
)

private val CHAT_OR_GENERIC_MESSAGE_DENY_KEYWORDS = listOf(
    "聊天",
    "消息",
    "发送消息",
    "通讯录",
    "朋友圈",
    "chat",
    "message"
)

private val ACTIVE_CHAT_INPUT_KEYWORDS = listOf(
    "发送消息",
    "按住 说话",
    "切换到键盘",
    "send message"
)

private val PAYMENT_INITIATION_DENY_KEYWORDS = listOf(
    "收银台",
    "立即付款",
    "确认付款",
    "确认支付",
    "支付密码",
    "添加转账说明",
    "send money",
    "pay now",
    "confirm payment",
    "cashier"
)

private val PAYMENT_AMOUNT_REGEX = Regex(
    """(?:[¥￥]\s*[+-]?\d+(?:\.\d{1,2})?|[+-]?\d+(?:\.\d{1,2})?\s*元)"""
)

class PaymentScreenCaptureDebouncer(
    private val duplicateWindowMillis: Long = 30_000,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var lastFingerprint: Int? = null
    private var lastProcessedAtEpochMillis: Long = 0

    @Synchronized
    fun shouldProcess(
        packageName: String,
        screenText: String,
        bypassDuplicateWindow: Boolean = false
    ): Boolean {
        val now = clock()
        val fingerprint = 31 * packageName.hashCode() + screenText.hashCode()
        if (
            !bypassDuplicateWindow &&
            fingerprint == lastFingerprint &&
            now - lastProcessedAtEpochMillis < duplicateWindowMillis
        ) {
            return false
        }
        lastFingerprint = fingerprint
        lastProcessedAtEpochMillis = now
        return true
    }
}
