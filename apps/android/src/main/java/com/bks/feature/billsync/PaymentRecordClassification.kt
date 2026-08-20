@file:Suppress("TooManyFunctions", "ComplexCondition")

package com.bks.feature.billsync

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

internal fun hasAlipayPaymentResultPageSignature(screenText: String): Boolean =
    ALIPAY_PAYMENT_RESULT_CONTEXT_KEYWORDS.any(screenText::contains)

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

internal fun hasWechatReceivedRedPacketSuccessSignature(screenText: String): Boolean {
    val senderTitle = WECHAT_RED_PACKET_TITLE_REGEX.find(screenText) ?: return false
    val amount = WECHAT_RED_PACKET_AMOUNT_REGEX.find(
        input = screenText,
        startIndex = senderTitle.range.last + 1
    ) ?: return false
    val storedIndex = screenText.indexOf("已存入零钱")
    val replyIndex = screenText.indexOf("回复表情到聊天")
    return storedIndex > amount.range.last && replyIndex > storedIndex
}

internal fun hasWechatSentRedPacketSuccessSignature(screenText: String): Boolean {
    val senderTitle = WECHAT_RED_PACKET_TITLE_REGEX.find(screenText) ?: return false
    val waiting = WECHAT_SENT_RED_PACKET_WAITING_REGEX.find(screenText)
    val refund = WECHAT_SENT_RED_PACKET_REFUND_REGEX.find(screenText)
    if (
        waiting != null &&
        refund != null &&
        waiting.range.first > senderTitle.range.last &&
        refund.range.first > waiting.range.last
    ) {
        return true
    }

    val claimed = WECHAT_SENT_RED_PACKET_CLAIMED_REGEX.find(screenText)
    if (claimed == null || claimed.range.first <= senderTitle.range.last) return false
    val claimedDetails = screenText.substring(claimed.range.last + 1)
    return WECHAT_SENT_RED_PACKET_DETAIL_AMOUNT_REGEX.containsMatchIn(claimedDetails) &&
        WECHAT_SENT_RED_PACKET_DETAIL_TIME_REGEX.containsMatchIn(claimedDetails)
}

private val WECHAT_RED_PACKET_TITLE_REGEX =
    Regex("""(?:^|\n)\s*[^\n]{1,64}?的红包\s*(?:\n|$)""")
private val WECHAT_RED_PACKET_AMOUNT_REGEX =
    Regex("""(?:[¥￥]\s*)?\d+(?:\.\d{1,2})?\s*元""")
private val WECHAT_SENT_RED_PACKET_WAITING_REGEX =
    Regex("""红包金额\s*\d+(?:\.\d{1,2})?\s*元[，,\s]*等待对方领取""")
private val WECHAT_SENT_RED_PACKET_REFUND_REGEX =
    Regex("""未领取的红包[，,\s]*将于\s*24\s*小时后发起退款""")
private val WECHAT_SENT_RED_PACKET_CLAIMED_REGEX =
    Regex("""\d+\s*个红包共\s*\d+(?:\.\d{1,2})?\s*元""")
private val WECHAT_SENT_RED_PACKET_DETAIL_AMOUNT_REGEX =
    Regex("""\d+(?:\.\d{1,2})?\s*元""")
private val WECHAT_SENT_RED_PACKET_DETAIL_TIME_REGEX =
    Regex("""(?:^|\n)\s*\d{1,2}:\d{2}\s*(?:\n|$)""")

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

internal val PAYMENT_COMPLETION_KEYWORDS = listOf(
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
