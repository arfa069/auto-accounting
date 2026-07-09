package com.autoaccounting.feature.monitoring

data class ContinuousMonitoringState(
    val billSyncCompleted: Boolean = false,
    val enabled: Boolean = false,
    val blockReason: ContinuousMonitoringBlockReason? = null
)

data class ContinuousMonitoringPermissionHealth(
    val notificationListenerGranted: Boolean = false,
    val billSyncAccessibilityGranted: Boolean = false
) {
    val isHealthy: Boolean
        get() = notificationListenerGranted && billSyncAccessibilityGranted

    val firstBlockReason: ContinuousMonitoringBlockReason?
        get() = when {
            !notificationListenerGranted ->
                ContinuousMonitoringBlockReason.RequiresNotificationListenerPermission
            !billSyncAccessibilityGranted ->
                ContinuousMonitoringBlockReason.RequiresBillSyncAccessibilityPermission
            else -> null
        }
}

enum class ContinuousMonitoringBlockReason {
    RequiresBillSyncFirst,
    RequiresNotificationListenerPermission,
    RequiresBillSyncAccessibilityPermission
}

sealed interface ContinuousMonitoringAction {
    data object MarkBillSyncCompleted : ContinuousMonitoringAction
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
    ContinuousMonitoringAction.MarkBillSyncCompleted -> state.copy(
        billSyncCompleted = true,
        blockReason = state.blockReason.takeUnless {
            it == ContinuousMonitoringBlockReason.RequiresBillSyncFirst
        }
    )

    is ContinuousMonitoringAction.Enable -> {
        val blockReason = state.startBlockReason(action.permissionHealth)
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
            blockReason == null && state.blockReason.isPermissionBlockReason() ->
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

private fun ContinuousMonitoringState.startBlockReason(
    permissionHealth: ContinuousMonitoringPermissionHealth
): ContinuousMonitoringBlockReason? = when {
    !billSyncCompleted -> ContinuousMonitoringBlockReason.RequiresBillSyncFirst
    else -> permissionHealth.firstBlockReason
}

private fun ContinuousMonitoringBlockReason?.isPermissionBlockReason(): Boolean =
    this == ContinuousMonitoringBlockReason.RequiresNotificationListenerPermission ||
        this == ContinuousMonitoringBlockReason.RequiresBillSyncAccessibilityPermission

private fun ContinuousMonitoringEvent.isPaymentHistorySurface(): Boolean {
    if (!isContinuousMonitoringPackageAllowed(packageName)) return false

    val text = screenText.lowercase()
    if (PAYMENT_DENY_KEYWORDS.any { keyword -> text.contains(keyword) }) return false
    return PAYMENT_HISTORY_KEYWORDS.any { keyword -> text.contains(keyword) }
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
    "退款",
    "支出",
    "收入",
    "bill",
    "transaction history",
    "payment record",
    "payment history",
    "receipt"
)

private val PAYMENT_DENY_KEYWORDS = listOf(
    "聊天",
    "消息",
    "发送消息",
    "通讯录",
    "朋友圈",
    "转账",
    "红包",
    "收银台",
    "立即付款",
    "确认付款",
    "确认支付",
    "付款码",
    "二维码",
    "扫一扫",
    "支付密码",
    "chat",
    "message",
    "transfer",
    "send money",
    "pay now",
    "confirm payment",
    "cashier",
    "qr code",
    "scan"
)
