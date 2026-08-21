package com.bks.feature.billsync

import java.math.BigDecimal
import java.math.RoundingMode

data class ParsedBillEntry(
    val merchantTitle: String,
    val amountMinor: Long,
    val transactionKindLabel: String,
    val transactionTimeText: String,
    val parsedFields: List<String>,
    val merchantTitleFromFallback: Boolean
)

class BillPageParser {
    fun parse(pageText: String, fallbackTransactionTimeText: String): List<ParsedBillEntry> {
        val lines = pageText.normalizedLines()
        val normalizedText = lines.joinToString("\n")
        if (normalizedText.isBlank() || REJECTED_STATUS.any(normalizedText::contains)) return emptyList()
        if (COMPLETED_STATUS.none(normalizedText::contains)) return emptyList()

        val direction = inferDirection(normalizedText) ?: return emptyList()
        val amounts = AMOUNT_REGEX.findAll(normalizedText)
            .mapNotNull { match -> match.groupValues.drop(1).firstOrNull(String::isNotBlank) }
            .mapNotNull(::parseAmountMinor)
            .distinct()
            .toList()
        if (amounts.size != 1 || CONTEXT_LABELS.none(normalizedText::contains)) return emptyList()

        val merchant = extractMerchant(lines)
        val title = merchant ?: FALLBACK_MERCHANT_TITLE
        val kind = when {
            normalizedText.contains("退款") -> "退款"
            direction == PaymentDirection.Inflow -> "收入"
            else -> "支出"
        }
        val transactionTime = TRANSACTION_TIME_REGEX.find(normalizedText)?.value
            ?: fallbackTransactionTimeText
        return listOf(
            ParsedBillEntry(
                merchantTitle = title,
                amountMinor = amounts.single(),
                transactionKindLabel = kind,
                transactionTimeText = transactionTime,
                parsedFields = listOf(
                    "来源=$GENERIC_PAYMENT_SOURCE_LABEL",
                    "商户=$title",
                    "金额=${amountMinorToText(amounts.single())}",
                    "类型=$kind"
                ),
                merchantTitleFromFallback = merchant == null
            )
        )
    }

    private fun inferDirection(text: String): PaymentDirection? {
        val hasInflow = INFLOW_STATUS.any(text::contains)
        val hasOutflow = OUTFLOW_STATUS.any(text::contains)
        return when {
            hasInflow == hasOutflow -> null
            hasInflow -> PaymentDirection.Inflow
            else -> PaymentDirection.Outflow
        }
    }

    private fun extractMerchant(lines: List<String>): String? {
        lines.forEachIndexed { index, line ->
            val inline = MERCHANT_LABELS.firstNotNullOfOrNull { label ->
                line.substringAfter(label, missingDelimiterValue = "")
                    .trimStart(' ', ':', '：')
                    .takeIf(::isMerchantValue)
            }
            if (inline != null) return inline
            if (line.trimEnd(' ', ':', '：') in MERCHANT_LABELS) {
                lines.getOrNull(index + 1)?.takeIf(::isMerchantValue)?.let { return it }
            }
        }
        return null
    }

    private fun isMerchantValue(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_MERCHANT_LENGTH &&
            !AMOUNT_REGEX.containsMatchIn(value) &&
            COMPLETED_STATUS.none(value::contains) &&
            REJECTED_STATUS.none(value::contains)
}

private enum class PaymentDirection { Inflow, Outflow }

internal fun parseAmountMinor(text: String): Long? = runCatching {
    BigDecimal(text.trim().replace(",", "").removePrefix("+"))
        .abs()
        .setScale(2, RoundingMode.HALF_UP)
        .movePointRight(2)
        .longValueExact()
        .takeIf { it > 0 }
}.getOrNull()

internal fun amountMinorToText(amountMinor: Long): String {
    val yuan = amountMinor / 100
    val cents = kotlin.math.abs(amountMinor % 100)
    return "$yuan.${cents.toString().padStart(2, '0')}"
}

internal fun String.normalizedLines(): List<String> = lineSequence()
    .map { it.trim() }
    .filter(String::isNotBlank)
    .toList()

internal const val GENERIC_PAYMENT_SOURCE_LABEL = "其他应用"
internal const val ACCESSIBILITY_AUTO_CAPTURE_REASON_LABEL = "支付结果自动捕获"
internal const val FALLBACK_MERCHANT_TITLE = "其他应用支付"

private const val MAX_MERCHANT_LENGTH = 80
private val AMOUNT_REGEX = Regex(
    "(?:[¥￥]\\s*([0-9]{1,9}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)|" +
        "([0-9]{1,9}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)\\s*元)"
)
private val TRANSACTION_TIME_REGEX = Regex(
    "\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}(?:日)?\\s+\\d{1,2}:\\d{2}(?::\\d{2})?"
)
private val COMPLETED_STATUS = listOf(
    "支付成功", "付款成功", "交易成功", "收款成功", "收款到账", "已收款",
    "已支付", "扣款成功", "退款成功", "退款到账", "交易完成", "已完成"
)
private val INFLOW_STATUS = listOf("收款成功", "收款到账", "已收款", "收入", "入账", "退款成功", "退款到账")
private val OUTFLOW_STATUS = listOf("支付成功", "付款成功", "已支付", "扣款成功", "支出")
private val REJECTED_STATUS = listOf(
    "待支付", "待付款", "处理中", "支付失败", "付款失败", "交易失败", "已取消", "交易取消",
    "输入密码", "支付密码", "确认付款", "立即支付", "立即付款", "继续支付"
)
private val CONTEXT_LABELS = listOf(
    "商户", "收款方", "付款方", "交易对象", "对方", "商品", "订单", "交易单号", "付款方式",
    "支付方式", "交易时间", "创建时间", "账单"
)
private val MERCHANT_LABELS = listOf("商户", "收款方", "付款方", "交易对象", "对方", "商品")
