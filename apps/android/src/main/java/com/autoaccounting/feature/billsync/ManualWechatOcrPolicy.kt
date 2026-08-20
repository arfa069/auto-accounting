package com.autoaccounting.feature.billsync

internal fun prepareManualWechatOcrResultText(pageText: String): String? {
    val lines = pageText.lineSequence()
        .map(String::trim)
        .map(String::normalizeManualWechatOcrText)
        .filter(String::isNotBlank)
        .toList()
    val normalizedText = lines.joinToString("\n")
    if (normalizedText.isBlank()) return null

    val compactText = normalizedText.filterNot(Char::isWhitespace)
    if (MANUAL_OCR_DENY_KEYWORDS.any(compactText::contains)) return null
    if (
        MANUAL_OCR_ACCEPTED_SIGNATURES.none { signature ->
            signature.all(compactText::contains)
        }
    ) return null
    if (!hasUnambiguousTransactionAmount(normalizedText)) return null
    return lines
        .filterNot { line ->
            line.filterNot(Char::isWhitespace).contains(MANUAL_OCR_BILL_SERVICE_KEYWORD)
        }
        .joinToString("\n")
}

internal fun hasCurrentStatusPaymentSuccessPair(pageText: String): Boolean =
    hasCurrentStatusPaymentSuccessPair(
        pageText.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    )

private fun hasCurrentStatusPaymentSuccessPair(lines: List<String>): Boolean {
    val normalizedLines = lines.map { line ->
        line.filterNot(Char::isWhitespace).replace(":", "").replace("：", "")
    }
    return normalizedLines.any { it == "当前状态支付成功" } ||
        normalizedLines.windowed(2).any { pair ->
            pair[0] == "当前状态" && pair[1] == "支付成功"
        }
}

private fun String.normalizeManualWechatOcrText(): String =
    replace("对方己收", "对方已收")
        .replace("对方已收线", "对方已收钱")

private val MANUAL_OCR_DENY_KEYWORDS = listOf(
    "确认支付",
    "立即支付",
    "收银台",
    "支付密码",
    "待支付",
    "处理中",
    "支付失败",
    "已取消"
)

private val MANUAL_OCR_ACCEPTED_SIGNATURES = listOf(
    listOf("当前状态", "支付成功"),
    listOf("当前状态", "对方已收"),
    listOf("退款状态", "已退款")
)

private const val MANUAL_OCR_BILL_SERVICE_KEYWORD = "账单服务"
