package com.autoaccounting.feature.monitoring

data class ContinuousMonitoringState(
    val billSyncCompleted: Boolean = false,
    val enabled: Boolean = false,
    val blockReason: ContinuousMonitoringBlockReason? = null
)

enum class ContinuousMonitoringBlockReason {
    RequiresBillSyncFirst
}

sealed interface ContinuousMonitoringAction {
    data object MarkBillSyncCompleted : ContinuousMonitoringAction
    data object Enable : ContinuousMonitoringAction
    data object Disable : ContinuousMonitoringAction
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

fun reduceContinuousMonitoringState(
    state: ContinuousMonitoringState,
    action: ContinuousMonitoringAction
): ContinuousMonitoringState = when (action) {
    ContinuousMonitoringAction.MarkBillSyncCompleted -> state.copy(
        billSyncCompleted = true,
        blockReason = null
    )
    ContinuousMonitoringAction.Enable -> if (state.billSyncCompleted) {
        state.copy(enabled = true, blockReason = null)
    } else {
        state.copy(enabled = false, blockReason = ContinuousMonitoringBlockReason.RequiresBillSyncFirst)
    }
    ContinuousMonitoringAction.Disable -> state.copy(enabled = false, blockReason = null)
}

fun observeContinuousMonitoringActivity(
    state: ContinuousMonitoringState,
    event: ContinuousMonitoringEvent
): ContinuousMonitoringObservation {
    if (!state.enabled) return ContinuousMonitoringObservation.Disabled
    return if (event.isPaymentRelated()) {
        ContinuousMonitoringObservation.PaymentRelated
    } else {
        ContinuousMonitoringObservation.Ignored
    }
}

private fun ContinuousMonitoringEvent.isPaymentRelated(): Boolean {
    val packageAllowed = packageName in PAYMENT_SOURCE_PACKAGES
    if (!packageAllowed) return false

    val text = screenText.lowercase()
    return PAYMENT_RELATED_KEYWORDS.any { keyword -> text.contains(keyword) }
}

private val PAYMENT_SOURCE_PACKAGES = setOf(
    "com.tencent.mm",
    "com.eg.android.AlipayGphone"
)

private val PAYMENT_RELATED_KEYWORDS = listOf(
    "支付",
    "收款",
    "付款",
    "账单",
    "交易",
    "转账",
    "退款",
    "红包",
    "alipay",
    "wechat pay",
    "bill",
    "payment",
    "transaction"
)
