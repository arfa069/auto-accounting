package com.autoaccounting.feature.capture

import java.math.BigDecimal
import java.math.RoundingMode

data class PaymentNotificationEvent(
    val packageName: String,
    val title: String,
    val text: String,
    val postedAtEpochMillis: Long
)

data class ParsedPaymentNotification(
    val sourceLabel: String,
    val merchantTitle: String,
    val amountMinor: Long,
    val transactionKindLabel: String,
    val fundingAccountLabel: String,
    val rawEvidenceText: String,
    val parsedFields: List<String>
)

class PaymentNotificationParser {
    fun parse(event: PaymentNotificationEvent): ParsedPaymentNotification? {
        val source = event.paymentSource() ?: return null
        val rawText = listOf(event.title, event.text)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val amountMinor = parseAmountMinor(rawText) ?: return null
        val kindLabel = rawText.transactionKindLabel() ?: return null
        val merchantTitle = extractMerchantTitle(rawText)
            ?: return null

        return ParsedPaymentNotification(
            sourceLabel = source.label,
            merchantTitle = merchantTitle,
            amountMinor = amountMinor,
            transactionKindLabel = kindLabel,
            fundingAccountLabel = source.defaultFundingAccountLabel,
            rawEvidenceText = rawText,
            parsedFields = listOf(
                "来源=${source.label}",
                "商户=$merchantTitle",
                "金额=${amountMinorToText(amountMinor)}",
                "类型=$kindLabel"
            )
        )
    }
}

private enum class PaymentNotificationSource(
    val label: String,
    val defaultFundingAccountLabel: String
) {
    WeChat("微信", "微信零钱"),
    Alipay("支付宝", "支付宝余额")
}

private fun PaymentNotificationEvent.paymentSource(): PaymentNotificationSource? {
    return when (packageName) {
        "com.tencent.mm" -> PaymentNotificationSource.WeChat
        "com.eg.android.AlipayGphone" -> PaymentNotificationSource.Alipay
        else -> null
    }
}

private fun String.transactionKindLabel(): String? = when {
    contains("退款") -> "退款"
    contains("收款") || contains("收款到账") || contains("到账") -> "收入"
    contains("付款") || contains("支付成功") || contains("付款成功") -> "支出"
    else -> null
}

private fun extractMerchantTitle(rawText: String): String? {
    val merchantRegexes = listOf(
        Regex("商户[:：]\\s*([^\\s，,]+)"),
        Regex("支付成功\\s+([^\\s，,]+)\\s+\\d"),
        Regex("付款成功\\s+([^\\s，,]+)\\s+\\d")
    )
    return merchantRegexes
        .asSequence()
        .mapNotNull { it.find(rawText)?.groupValues?.getOrNull(1)?.trim() }
        .firstOrNull { it.isNotBlank() }
}

private fun parseAmountMinor(rawText: String): Long? {
    val match = Regex("(?:¥|￥)?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*(?:元)?")
        .findAll(rawText)
        .lastOrNull()
        ?: return null
    return runCatching {
        BigDecimal(match.groupValues[1])
            .setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2)
            .longValueExact()
    }.getOrNull()
}

private fun amountMinorToText(amountMinor: Long): String {
    val yuan = amountMinor / 100
    val cents = kotlin.math.abs(amountMinor % 100)
    return "$yuan.${cents.toString().padStart(2, '0')}"
}
