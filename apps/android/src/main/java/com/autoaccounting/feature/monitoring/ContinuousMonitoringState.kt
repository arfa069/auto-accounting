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
        val blockReason = action.permissionHealth.firstBlockReason
        when {
            state.enabled && blockReason != null ->
                state.copy(enabled = false, blockReason = blockReason)
            blockReason == null && state.blockReason != null ->
                state.copy(blockReason = null)
            else -> state
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
    if (PAYMENT_INITIATION_DENY_KEYWORDS.any { keyword -> text.contains(keyword) }) return false
    val hasChatOrGenericMessage =
        CHAT_OR_GENERIC_MESSAGE_DENY_KEYWORDS.any { keyword -> text.contains(keyword) }
    val hasCompletedPayment = PAYMENT_COMPLETION_KEYWORDS.any { keyword -> text.contains(keyword) } &&
        PAYMENT_AMOUNT_REGEX.containsMatchIn(text)
    if (packageName == "com.tencent.mm") {
        val hasSupportedCompletion = hasWechatMerchantPaymentSuccessSignature(text) ||
            hasWechatTransferCompletionContext(text)
        return hasCompletedPayment &&
            hasSupportedCompletion &&
            !hasChatOrGenericMessage &&
            ACTIVE_CHAT_INPUT_KEYWORDS.none { keyword -> text.contains(keyword) }
    }
    if (hasCompletedPayment) {
        return ACTIVE_CHAT_INPUT_KEYWORDS.none { keyword -> text.contains(keyword) }
    }
    if (hasChatOrGenericMessage &&
        PAYMENT_MESSAGE_CENTER_KEYWORDS.none { keyword -> text.contains(keyword) }
    ) {
        return false
    }
    if (TRANSFER_OR_RED_PACKET_KEYWORDS.any { keyword -> text.contains(keyword) } &&
        PAYMENT_RECORD_CONTEXT_KEYWORDS.none { keyword -> text.contains(keyword) }
    ) {
        return false
    }
    return PAYMENT_AMOUNT_REGEX.containsMatchIn(text) &&
        (
            PAYMENT_HISTORY_KEYWORDS.any { keyword -> text.contains(keyword) } ||
                PAYMENT_MESSAGE_CENTER_KEYWORDS.any { keyword -> text.contains(keyword) }
            )
}

private val PAYMENT_SOURCE_PACKAGES = setOf(
    "com.tencent.mm",
    "com.eg.android.AlipayGphone"
)

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
    fun shouldProcess(packageName: String, screenText: String): Boolean {
        val now = clock()
        val fingerprint = 31 * packageName.hashCode() + screenText.hashCode()
        if (
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
