package com.autoaccounting.feature.billsync

internal fun extractMerchantOrPayee(windowText: String, lines: List<String>): String? {
    merchantInlineRegex.find(windowText)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isMeaningfulPaymentRecordValue() }
        ?.let { return it }

    return extractValueAfterLabels(lines, MERCHANT_LABELS)
}

internal fun extractFundingAccountLabel(
    windowText: String,
    lines: List<String>
): String? {
    fundingInlineRegex.find(windowText)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isMeaningfulPaymentRecordValue() }
        ?.let { return it }

    return extractValueAfterLabels(lines, FUNDING_LABELS)
}

internal fun extractValueAfterLabels(
    lines: List<String>,
    labels: List<String>
): String? {
    lines.forEachIndexed { index, line ->
        labels.forEach { label ->
            line.valueAfterLabel(label)
                ?.takeIf { it.isMeaningfulPaymentRecordValue() }
                ?.let { return it }
            if (line.trimEnd(':', '：') == label) {
                lines.drop(index + 1)
                    .takeWhile { nextLine -> !nextLine.isKnownFieldLine() }
                    .firstOrNull { it.isMeaningfulPaymentRecordValue() }
                    ?.let { return it }
            }
        }
    }
    return null
}

internal fun extractImmediateValueAfterLabels(
    lines: List<String>,
    labels: List<String>
): String? {
    for ((index, line) in lines.withIndex()) {
        for (label in labels) {
            val inlineValue = line.valueAfterLabel(label) ?: continue
            if (inlineValue.isNotBlank()) return inlineValue
            return lines.getOrNull(index + 1)
                ?.trim()
                ?.takeIf { value -> value.isNotBlank() && !value.isKnownFieldLine() }
        }
    }
    return null
}

internal fun extractMultilineValueAfterLabels(
    lines: List<String>,
    labels: List<String>
): String? {
    for ((index, line) in lines.withIndex()) {
        for (label in labels) {
            val inlineValue = line.valueAfterLabel(label) ?: continue

            val values = buildList {
                inlineValue.takeIf(String::isNotBlank)?.let(::add)
                lines.drop(index + 1)
                    .takeWhile { nextLine -> !nextLine.isKnownFieldLine() }
                    .take(MAX_MULTILINE_FIELD_LINES)
                    .forEach(::add)
            }
            return values.joinToString(" ").trim().takeIf(String::isNotBlank)
        }
    }
    return null
}

internal fun String.isKnownFieldLine(): Boolean {
    val line = trim()
    return FIELD_LABELS.any { label ->
        line.trimEnd(':', '：') == label || line.valueAfterLabel(label) != null
    }
}

internal fun String.valueAfterLabel(label: String): String? {
    if (!startsWith(label)) return null
    val value = removePrefix(label)
    if (value.isBlank()) return ""
    if (value.first() !in listOf(':', '：', ' ', '\t')) return null
    return value.trim().trimStart(':', '：').trim()
}

internal fun String.isMeaningfulPaymentRecordValue(): Boolean {
    val value = trim()
    if (value.isBlank()) return false
    if (explicitPaymentAmountRegex.containsMatchIn(value)) return false
    if (value.extractTransactionTimeText() != null) return false
    if (value.isKnownFieldLine()) return false
    if (PAYMENT_RECORD_NOISE_VALUES.any { value == it }) return false
    if (PAYMENT_RECORD_NOISE_CONTAINS.any { value.contains(it) }) return false
    return true
}

internal fun String.isMeaningfulPaymentRecordTitle(): Boolean {
    val value = trim()
    return value.length in 2..64 &&
        value.isMeaningfulPaymentRecordValue() &&
        PAYMENT_RECORD_TITLE_NOISE_VALUES.none { value == it }
}

private val MERCHANT_LABELS = listOf(
    "商户全称",
    "商户",
    "商家",
    "收款方",
    "付款方",
    "对方账户",
    "对方",
    "交易对象",
    "收款人",
    "付款人"
)

private val FUNDING_LABELS = listOf(
    "付款方式",
    "支付方式",
    "交易方式",
    "付款渠道",
    "退款方式",
    "扣款方式",
    "资金渠道",
    "支付账户",
    "付款账户"
)

internal val PRODUCT_LABELS = listOf("商品名称", "商品")
internal val RECEIPT_NOTE_LABELS = listOf("收款方备注")
internal val STATUS_LABELS = listOf("当前状态", "交易状态", "退款状态")
internal val TRANSACTION_ORDER_LABELS = listOf("交易单号", "转账单号", "退款单号")
internal val MERCHANT_ORDER_LABELS = listOf("商户单号")

private val FIELD_LABELS = MERCHANT_LABELS + FUNDING_LABELS + PRODUCT_LABELS +
    RECEIPT_NOTE_LABELS + STATUS_LABELS + TRANSACTION_ORDER_LABELS + MERCHANT_ORDER_LABELS + listOf(
    "金额",
    "交易金额",
    "付款金额",
    "交易时间",
    "转账时间",
    "收款时间",
    "支付时间",
    "退款时间",
    "创建时间",
    "收单机构"
)

private val PAYMENT_RECORD_NOISE_VALUES = listOf(
    "支付宝",
    "微信支付",
    "支付信息",
    "消息盒子",
    "账单",
    "账单详情",
    "账单明细",
    "交易详情",
    "交易记录",
    "支付记录",
    "支付凭证",
    "收支明细",
    "零钱明细",
    "转账记录",
    "红包记录",
    "详情",
    "完成",
    "成功",
    "支出",
    "收入",
    "退款",
    "转账",
    "红包"
)

private val PAYMENT_RECORD_NOISE_CONTAINS = listOf(
    "支付成功",
    "付款成功",
    "交易成功",
    "转账成功",
    "收款成功",
    "退款成功",
    "红包发送成功",
    "已存入零钱",
    "扫码支付",
    "碰一碰支付",
    "当前状态"
)

private val PAYMENT_RECORD_TITLE_NOISE_VALUES = listOf(
    "返回",
    "返回商家",
    "回首页",
    "返回首页",
    "更多",
    "帮助",
    "投诉",
    "查看往来记录",
    "对此订单有疑问"
)

private val merchantInlineRegex = Regex(
    pattern = """(?:商户全称|商户|商家|收款方|付款方|对方账户|对方|交易对象|收款人|付款人)[:：]\s*([^\n，,]+)"""
)

private val fundingInlineRegex = Regex(
    pattern = """(?:付款方式|支付方式|交易方式|付款渠道|扣款方式|资金渠道|支付账户|付款账户)[:：]\s*([^\n，,]+)"""
)

private const val MAX_MULTILINE_FIELD_LINES = 3

internal val BillSyncSource.genericPaymentTitle: String
    get() = "${label}支付"
