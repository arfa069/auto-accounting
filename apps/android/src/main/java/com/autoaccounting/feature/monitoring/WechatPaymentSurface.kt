package com.autoaccounting.feature.monitoring

internal fun hasWechatMerchantPaymentSuccessSignature(screenText: String): Boolean {
    val successIndex = screenText.indexOf("支付成功")
    val returnMerchantIndex = screenText.indexOf("返回商家")
    return successIndex >= 0 && returnMerchantIndex > successIndex
}

internal fun hasWechatTransferCompletionContext(screenText: String): Boolean =
    screenText.contains("转账成功") ||
        WECHAT_TRANSFER_COMPLETION_REGEX.containsMatchIn(screenText)

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
    if (waiting != null && refund != null) {
        if (
            waiting.range.first > senderTitle.range.last &&
            refund.range.first > waiting.range.last
        ) {
            return true
        }
    }

    val claimed = WECHAT_SENT_RED_PACKET_CLAIMED_REGEX.find(screenText)
    if (claimed == null || claimed.range.first <= senderTitle.range.last) return false
    val claimedDetails = screenText.substring(claimed.range.last + 1)
    return WECHAT_SENT_RED_PACKET_DETAIL_AMOUNT_REGEX.containsMatchIn(claimedDetails) &&
        WECHAT_SENT_RED_PACKET_DETAIL_TIME_REGEX.containsMatchIn(claimedDetails)
}

internal fun hasOnlyGenericWechatAccessibilityText(screenText: String): Boolean =
    screenText.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .all { line -> line in WECHAT_GENERIC_ACCESSIBILITY_LABELS }

private val WECHAT_TRANSFER_COMPLETION_REGEX = Regex("""待.+?确认收款""")
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

private val WECHAT_GENERIC_ACCESSIBILITY_LABELS = setOf(
    "返回",
    "返回上一页",
    "关闭",
    "关闭页面",
    "更多",
    "更多操作"
)
