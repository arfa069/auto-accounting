@file:Suppress("TooManyFunctions")

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
    val parsedFields: List<String>,
    val note: String? = null,
    val paymentMethod: String? = null,
    val orderNumber: String? = null,
    val merchantOrderNumber: String? = null
)

enum class PaymentNotificationRejectionReason {
    UnsupportedSource,
    NonPaymentNotification,
    MissingAmount,
    MissingTransactionKind
}

data class PaymentNotificationParseResult(
    val parsed: ParsedPaymentNotification? = null,
    val rejectionReason: PaymentNotificationRejectionReason? = null,
    val isPaymentRelated: Boolean = false
)

class PaymentNotificationParser {
    fun parse(event: PaymentNotificationEvent): ParsedPaymentNotification? =
        parseDetailed(event).parsed

    fun parseDetailed(event: PaymentNotificationEvent): PaymentNotificationParseResult {
        val source = event.paymentSource() ?: return PaymentNotificationParseResult(
            rejectionReason = PaymentNotificationRejectionReason.UnsupportedSource
        )
        val rawText = listOf(event.title, event.text)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (!rawText.hasPaymentNotificationSignature()) {
            return PaymentNotificationParseResult(
                rejectionReason = PaymentNotificationRejectionReason.NonPaymentNotification
            )
        }
        val amountMinor = parseAmountMinor(rawText) ?: return PaymentNotificationParseResult(
            rejectionReason = PaymentNotificationRejectionReason.MissingAmount,
            isPaymentRelated = true
        )
        val kindLabel = rawText.transactionKindLabel() ?: return PaymentNotificationParseResult(
            rejectionReason = PaymentNotificationRejectionReason.MissingTransactionKind,
            isPaymentRelated = true
        )
        val counterpartyTitle = extractCounterpartyTitle(rawText)
            ?: FALLBACK_COUNTERPARTY
        val account = rawText.extractLabeledValue(ACCOUNT_LABEL_REGEX)
        val paymentMethod = rawText.extractLabeledValue(PAYMENT_METHOD_LABEL_REGEX)
            ?: rawText.extractUsedPaymentMethod()
            ?: source.label
        val fundingAccount = account
            ?: paymentMethod.takeUnless { it.equals(source.label, ignoreCase = true) }
            ?: source.defaultFundingAccountLabel
        val note = rawText.extractLabeledValue(NOTE_LABEL_REGEX)
        val merchantOrderNumber = rawText.extractLabeledValue(MERCHANT_ORDER_LABEL_REGEX)
        val orderNumber = rawText.extractLabeledValue(ORDER_LABEL_REGEX)

        return PaymentNotificationParseResult(
            parsed = ParsedPaymentNotification(
                sourceLabel = source.label,
                merchantTitle = counterpartyTitle,
                amountMinor = amountMinor,
                transactionKindLabel = kindLabel,
                fundingAccountLabel = fundingAccount,
                rawEvidenceText = rawText,
                parsedFields = buildList {
                    add("来源=${source.label}")
                    add("商户=$counterpartyTitle")
                    add("金额=${amountMinorToText(amountMinor)}")
                    add("类型=$kindLabel")
                    account?.let { add("paymentAccount=$it") }
                    add("paymentMethod=$paymentMethod")
                    orderNumber?.let { add("orderNumber=$it") }
                    merchantOrderNumber?.let { add("merchantOrderNumber=$it") }
                },
                note = note,
                paymentMethod = paymentMethod,
                orderNumber = orderNumber,
                merchantOrderNumber = merchantOrderNumber
            ),
            isPaymentRelated = true
        )
    }
}

private fun String.hasPaymentNotificationSignature(): Boolean =
    PAYMENT_WORD_REGEX.containsMatchIn(this) &&
        (PAYMENT_AMOUNT_REGEX.containsMatchIn(this) || PAYMENT_OUTCOME_REGEX.containsMatchIn(this))

private fun String.extractLabeledValue(regex: Regex): String? =
    regex.find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf(String::isNotBlank)

private fun String.extractUsedPaymentMethod(): String? = USED_PAYMENT_METHOD_REGEX.find(this)
    ?.groupValues
    ?.getOrNull(1)
    ?.trim()
    ?.takeIf { it.isNotBlank() && it.length <= 20 }

private const val ALL_DETAIL_LABELS =
    "付款账户|支付账户|付款账号|支付账号|账号|付款方式|支付方式|备注|商品说明|商户订单号|交易单号|交易号|订单号"

private fun labeledValueRegex(labelPattern: String) = Regex(
    """(?:$labelPattern)\s*[:：]\s*([^\n\r，,；;]{1,80}?)(?=\s+(?:$ALL_DETAIL_LABELS)\s*[:：]|$)"""
)

private val PAYMENT_WORD_REGEX = Regex("支付|付款|收款|到账|交易|转账|红包|退款|扣款|消费")
private val PAYMENT_AMOUNT_REGEX = Regex("""(?:¥|￥|\d+(?:\.\d{1,2})?\s*元)""")
private val PAYMENT_OUTCOME_REGEX = Regex("成功|完成支付|支付完成|到账|退款|转账|红包|已支付|已付款")
private val ACCOUNT_LABEL_REGEX = labeledValueRegex("(?:付款账户|支付账户|付款账号|支付账号|账号)")
private val PAYMENT_METHOD_LABEL_REGEX = labeledValueRegex("(?:付款方式|支付方式)")
private val USED_PAYMENT_METHOD_REGEX = Regex("使用([^，,。；;\\s]{1,20}?)支付")
private val NOTE_LABEL_REGEX = labeledValueRegex("(?:备注|商品说明)")
private val MERCHANT_ORDER_LABEL_REGEX = labeledValueRegex("商户订单号")
private val ORDER_LABEL_REGEX = labeledValueRegex("(?:交易单号|交易号|(?<!商户)订单号)")
private val OUTGOING_TRANSFER_REGEX = Regex("向(?!你).*转账")
private val MERCHANT_REGEXES = listOf(
    Regex("""商户[:：]\s*([^\s，,]+)"""),
    Regex("""支付成功\s+([^\s，,]+)\s+\d"""),
    Regex("""付款成功\s+([^\s，,]+)\s+\d""")
)
private val P2P_REGEXES = listOf(
    Regex("收到(.+?)的红包"),
    Regex("收到(.+?)的转账"),
    Regex("""([^\s]+?)向你转账"""),
    Regex("""转账给(.+?)\s"""),
    Regex("转账给(.+?)$"),
    Regex("""向([^\s]+?)转账""")
)
private val EXPLICIT_AMOUNT_REGEXES = listOf(
    Regex("""(?:¥|￥)\s*(\d+(?:\.\d{1,2})?)"""),
    Regex("""(\d+(?:\.\d{1,2})?)\s*元""")
)
private val IMPLICIT_AMOUNT_REGEX = Regex("""\d+(?:\.\d{1,2})?""")

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
    contains("退款") -> "退款"
    isIncomingPeerTransfer() -> "收入"
    isOutgoingPeerTransfer() -> "支出"
    contains("收款") || contains("到账") -> "收入"
    contains("付款") ||
        contains("支付成功") ||
        contains("完成支付") ||
        contains("支付完成") ||
        contains("已支付") -> "支出"
    contains("支出") && contains("交易") -> "支出"
    else -> null
}

private fun String.isIncomingPeerTransfer(): Boolean =
    contains("收到") && (contains("红包") || contains("转账")) || contains("向你转账")

private fun String.isOutgoingPeerTransfer(): Boolean =
    contains("发出红包") ||
        contains("红包已发出") ||
        contains("转账给") ||
        OUTGOING_TRANSFER_REGEX.containsMatchIn(this)

private fun extractCounterpartyTitle(rawText: String): String? {
    // Merchant payment patterns (original, highest priority)
    val merchantRegexes = MERCHANT_REGEXES
    merchantRegexes
        .asSequence()
        .mapNotNull { it.find(rawText)?.groupValues?.getOrNull(1)?.trim() }
        .firstOrNull { it.isNotBlank() }
        ?.let { return it }

    // P2P patterns: red packets and transfers (both directions)
    val p2pRegexes = P2P_REGEXES
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
    val explicitAmount = EXPLICIT_AMOUNT_REGEXES
        .asSequence()
        .flatMap { it.findAll(rawText).map { match -> match.groupValues[1] } }
        .firstOrNull()
    if (explicitAmount != null) {
        return explicitAmount.toAmountMinor()
    }

    val implicitAmounts = IMPLICIT_AMOUNT_REGEX
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
