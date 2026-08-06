package com.autoaccounting.feature.billsync

import com.autoaccounting.feature.monitoring.hasAlipayPaymentResultPageSignature
import com.autoaccounting.feature.monitoring.hasWechatReceivedRedPacketSuccessSignature
import com.autoaccounting.feature.monitoring.hasWechatSentRedPacketSuccessSignature

internal fun String.isSupportedPaymentRecordSurface(): Boolean =
    PAYMENT_RECORD_SURFACE_KEYWORDS.any { contains(it) }

internal fun String.isCompletedPaymentResultSurface(source: BillSyncSource): Boolean = when (source) {
    BillSyncSource.Alipay ->
        PAYMENT_COMPLETION_KEYWORDS.any { contains(it) } &&
            hasAlipayPaymentResultPageSignature(this)
    BillSyncSource.WeChat ->
        PAYMENT_COMPLETION_KEYWORDS.any { contains(it) } ||
            hasWechatReceivedRedPacketSuccessSignature(this) ||
            hasWechatSentRedPacketSuccessSignature(this)
}

internal fun String.hasPaymentInitiationKeyword(): Boolean =
    PAYMENT_INITIATION_KEYWORDS.any { contains(it) }

internal fun String.hasPaymentRecordEvidence(): Boolean =
    explicitPaymentAmountRegex.containsMatchIn(this) &&
        lineSequence().any { it.extractTransactionTimeText() != null } &&
        inferTransactionKindLabel() != null

internal fun String.hasCompletedPaymentResultEvidence(source: BillSyncSource): Boolean =
    isCompletedPaymentResultSurface(source) &&
        explicitPaymentAmountRegex.containsMatchIn(this) &&
        inferTransactionKindLabel() != null

internal fun String.inferTransactionKindLabel(): String? {
    if (hasWechatSentRedPacketSuccessSignature(this)) return "支出"
    if (hasWechatReceivedRedPacketSuccessSignature(this)) return "收入"
    if (contains("退款")) return "退款"
    if (contains("对方已收") && contains("转账")) return "支出"
    if (hasCurrentStatusPaymentSuccessPair(this)) return "支出"
    if (hasIncomeTransactionEvidence()) return "收入"
    if (hasExpenseTransactionEvidence()) return "支出"
    return when {
        contains("红包") -> "红包"
        contains("转账") -> "转账"
        else -> null
    }
}

private fun String.hasIncomeTransactionEvidence(): Boolean =
    containsAny(
        "收入",
        "收款到账",
        "收款成功",
        "入账",
        "向你转账"
    ) || RECEIVED_TRANSFER_REGEX.containsMatchIn(this)

private fun String.hasExpenseTransactionEvidence(): Boolean =
    containsAny(
        "发出红包",
        "红包已发出",
        "红包发送成功",
        "支出",
        "付款",
        "支付成功",
        "完成支付",
        "支付完成",
        "交易成功",
        "转账成功",
        "扫码支付",
        "碰一碰支付",
        "转账给"
    ) || OUTGOING_TRANSFER_REGEX.containsMatchIn(this)

private fun String.containsAny(vararg keywords: String): Boolean = keywords.any(::contains)

private val RECEIVED_TRANSFER_REGEX = Regex("收到.+?(转账|红包)")
private val OUTGOING_TRANSFER_REGEX = Regex("向(?!你).+?转账")

private val PAYMENT_RECORD_SURFACE_KEYWORDS = listOf(
    "账单",
    "账单详情",
    "账单明细",
    "交易记录",
    "交易详情",
    "支付记录",
    "支付信息",
    "支付凭证",
    "消息盒子",
    "收支明细",
    "零钱明细",
    "红包记录",
    "转账记录",
    "bill",
    "transaction history",
    "payment record",
    "payment history",
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
    "对方已收",
    "已退款",
    "红包发送成功",
    "已支付",
    "已付款",
    "payment successful",
    "payment complete"
)

private val PAYMENT_INITIATION_KEYWORDS = listOf(
    "收银台",
    "立即付款",
    "确认付款",
    "确认支付",
    "支付密码",
    "输入密码",
    "添加转账说明",
    "pay now",
    "confirm payment",
    "cashier"
)
