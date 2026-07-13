package com.autoaccounting.feature.monitoring

internal fun hasWechatMerchantPaymentSuccessSignature(screenText: String): Boolean {
    val successIndex = screenText.indexOf("支付成功")
    val returnMerchantIndex = screenText.indexOf("返回商家")
    return successIndex >= 0 && returnMerchantIndex > successIndex
}

internal fun hasWechatTransferCompletionContext(screenText: String): Boolean =
    screenText.contains("转账成功") ||
        WECHAT_TRANSFER_COMPLETION_REGEX.containsMatchIn(screenText)

internal fun hasOnlyGenericWechatAccessibilityText(screenText: String): Boolean =
    screenText.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .all { line -> line in WECHAT_GENERIC_ACCESSIBILITY_LABELS }

private val WECHAT_TRANSFER_COMPLETION_REGEX = Regex("""待.+?确认收款""")

private val WECHAT_GENERIC_ACCESSIBILITY_LABELS = setOf(
    "返回",
    "返回上一页",
    "关闭",
    "关闭页面",
    "更多",
    "更多操作"
)
