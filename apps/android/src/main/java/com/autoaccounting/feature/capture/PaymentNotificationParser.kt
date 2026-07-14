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
        val counterpartyTitle = extractCounterpartyTitle(rawText)
            ?: FALLBACK_COUNTERPARTY

        return ParsedPaymentNotification(
            sourceLabel = source.label,
            merchantTitle = counterpartyTitle,
            amountMinor = amountMinor,
            transactionKindLabel = kindLabel,
            fundingAccountLabel = source.defaultFundingAccountLabel,
            rawEvidenceText = rawText,
            parsedFields = listOf(
                "来源=${source.label}",
                "商户=$counterpartyTitle",
                "金额=${amountMinorToText(amountMinor)}",
                "类型=$kindLabel"
            )
        )
    }
}

internal const val FALLBACK_COUNTERPARTY = "未知来源"

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
    // 收到退款
    contains("退款") -> "退款"

    // P2P incoming: receive red packet or transfer
    contains("收到") && (contains("红包") || contains("转账")) -> "收入"
    Regex("向你转账").containsMatchIn(this) -> "收入"

    // P2P outgoing: send red packet or transfer
    contains("发出红包") || contains("红包已发出") -> "支出"
    contains("转账给") -> "支出"
    Regex("向(?!你).*转账").containsMatchIn(this) -> "支出"

    // Merchant payment keywords (original)
    contains("收款") || contains("收款到账") || contains("到账") -> "收入"
    contains("付款") || contains("支付成功") || contains("付款成功") || contains("已支付") -> "支出"
    contains("支出") && contains("交易") -> "支出"

    // else
    else -> null
}

private fun extractCounterpartyTitle(rawText: String): String? {
    // Merchant payment patterns (original, highest priority)
    val merchantRegexes = listOf(
        Regex("商户[:：]\\s*([^\\s，,]+)"),
        Regex("支付成功\\s+([^\\s，,]+)\\s+\\d"),
        Regex("付款成功\\s+([^\\s，,]+)\\s+\\d")
    )
    merchantRegexes
        .asSequence()
        .mapNotNull { it.find(rawText)?.groupValues?.getOrNull(1)?.trim() }
        .firstOrNull { it.isNotBlank() }
        ?.let { return it }

    // P2P patterns: red packets and transfers (both directions)
    val p2pRegexes = listOf(
        // Received red packet: "收到xxx的红包"
        Regex("收到(.+?)的红包"),
        // Received transfer: "收到xxx的转账" or "xxx向你转账"
        Regex("收到(.+?)的转账"),
        Regex("([^\\s]+?)向你转账"),
        // Sent transfer: "转账给xxx" or "向xxx转账"
        Regex("转账给(.+?)\\s"),
        Regex("转账给(.+?)$"),
        Regex("向([^\\s]+?)转账")
    )
    p2pRegexes
        .asSequence()
        .mapNotNull { it.find(rawText)?.groupValues?.getOrNull(1)?.trim() }
        .firstOrNull { it.isNotBlank() && it != "你" }
        ?.let { return it }

    // Sent red packet with no named recipient
    if (rawText.contains("发出红包") || rawText.contains("红包已发出")) {
        return "红包"
    }

    return null
}

private fun parseAmountMinor(rawText: String): Long? {
    val explicitAmount = listOf(
        Regex("(?:¥|￥)\\s*(\\d+(?:\\.\\d{1,2})?)"),
        Regex("(\\d+(?:\\.\\d{1,2})?)\\s*元")
    )
        .asSequence()
        .flatMap { it.findAll(rawText).map { match -> match.groupValues[1] } }
        .firstOrNull()
    if (explicitAmount != null) {
        return explicitAmount.toAmountMinor()
    }

    val implicitAmounts = Regex("\\d+(?:\\.\\d{1,2})?")
        .findAll(rawText)
        .map { it.value }
        .toList()
    return implicitAmounts.singleOrNull()?.toAmountMinor()
}

private fun String.toAmountMinor(): Long? =
    runCatching {
        BigDecimal(this)
            .setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2)
            .longValueExact()
    }.getOrNull()

private fun amountMinorToText(amountMinor: Long): String {
    val yuan = amountMinor / 100
    val cents = kotlin.math.abs(amountMinor % 100)
    return "$yuan.${cents.toString().padStart(2, '0')}"
}
