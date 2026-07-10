package com.autoaccounting.feature.billsync

import java.math.BigDecimal
import java.math.RoundingMode

enum class BillSyncSource(
    val label: String,
    val defaultFundingAccountLabel: String,
    val packageName: String
) {
    WeChat("微信", "微信零钱", "com.tencent.mm"),
    Alipay("支付宝", "支付宝余额", "com.eg.android.AlipayGphone");

    companion object {
        fun fromPackageName(packageName: String): BillSyncSource? =
            entries.firstOrNull { it.packageName == packageName }
    }
}

data class ParsedBillEntry(
    val sourceLabel: String,
    val merchantTitle: String,
    val amountMinor: Long,
    val transactionKindLabel: String,
    val fundingAccountLabel: String,
    val transactionTimeText: String,
    val rawLine: String,
    val parsedFields: List<String>,
    val transactionTimeFromFallback: Boolean = false
)

enum class BillSyncPageObservation {
    PaymentResult,
    PaymentRecord,
    BlockedPaymentInitiation,
    Ignored
}

fun observeBillSyncPage(
    source: BillSyncSource,
    pageText: String
): BillSyncPageObservation {
    val normalizedText = pageText.normalizedLines().joinToString("\n")
    if (normalizedText.isBlank()) return BillSyncPageObservation.Ignored
    if (
        normalizedText.hasPaymentInitiationKeyword() &&
        explicitPaymentAmountRegex.containsMatchIn(normalizedText)
    ) {
        return BillSyncPageObservation.BlockedPaymentInitiation
    }
    if (normalizedText.hasCompletedPaymentResultEvidence()) {
        return BillSyncPageObservation.PaymentResult
    }
    if (BillPageParser().parse(source, normalizedText).isNotEmpty()) {
        return BillSyncPageObservation.PaymentRecord
    }
    return if (
        normalizedText.isSupportedPaymentRecordSurface() &&
        normalizedText.hasPaymentRecordEvidence()
    ) {
        BillSyncPageObservation.PaymentRecord
    } else {
        BillSyncPageObservation.Ignored
    }
}

class BillPageParser {
    fun parse(
        source: BillSyncSource,
        pageText: String,
        fallbackTransactionTimeText: String? = null
    ): List<ParsedBillEntry> {
        val lines = pageText.normalizedLines()
        return (lines.mapNotNull { line -> parseLine(source, line) } +
            parsePaymentRecordSurface(source, lines, fallbackTransactionTimeText))
            .distinctBy { entry ->
                "${entry.sourceLabel}|${entry.transactionTimeText}|${entry.amountMinor}|" +
                    "${entry.transactionKindLabel}|${entry.merchantTitle}"
            }
    }

    private fun parseLine(
        source: BillSyncSource,
        line: String
    ): ParsedBillEntry? {
        val match = billLineRegex.matchEntire(line) ?: return null
        val transactionTimeText = match.groupValues[1].trim()
        val merchantTitle = match.groupValues[2].trim()
        val transactionKindLabel = match.groupValues[3].trim()
        val amountMinor = parseAmountMinor(match.groupValues[4]) ?: return null
        val fundingAccountLabel = match.groupValues[5].trim()
            .ifBlank { source.defaultFundingAccountLabel }

        return ParsedBillEntry(
            sourceLabel = source.label,
            merchantTitle = merchantTitle,
            amountMinor = amountMinor,
            transactionKindLabel = transactionKindLabel,
            fundingAccountLabel = fundingAccountLabel,
            transactionTimeText = transactionTimeText,
            rawLine = line,
            parsedFields = listOf(
                "来源=${source.label}",
                "商户=$merchantTitle",
                "金额=${amountMinorToText(amountMinor)}",
                "类型=$transactionKindLabel"
            )
        )
    }

    private fun parseAmountMinor(text: String): Long? = runCatching {
        BigDecimal(text.trim().removePrefix("+"))
            .abs()
            .setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2)
            .longValueExact()
    }.getOrNull()

    private fun parsePaymentRecordSurface(
        source: BillSyncSource,
        lines: List<String>,
        fallbackTransactionTimeText: String?
    ): List<ParsedBillEntry> {
        val pageText = lines.joinToString("\n")
        if (!pageText.isSupportedPaymentRecordSurface() && !pageText.isCompletedPaymentResultSurface()) {
            return emptyList()
        }
        if (pageText.hasPaymentInitiationKeyword()) return emptyList()

        val amountMatches = mutableListOf<Pair<Int, MatchResult>>()
        lines.forEachIndexed { amountLineIndex, line ->
            if (line.isNonTransactionAmountLine()) return@forEachIndexed
            explicitPaymentAmountRegex.findAll(line).forEach { match ->
                if (match.isNonTransactionAmountMatch(line)) return@forEach
                amountMatches += amountLineIndex to match
            }
        }
        val selectedMatches = if (pageText.isCompletedPaymentResultSurface()) {
            amountMatches.take(1)
        } else {
            amountMatches
        }
        return selectedMatches.mapNotNull { (amountLineIndex, match) ->
            val amountText = match.amountText() ?: return@mapNotNull null
            parsePaymentRecordWindow(
                source = source,
                lines = lines,
                amountLineIndex = amountLineIndex,
                amountText = amountText,
                fallbackTransactionTimeText = fallbackTransactionTimeText
            )
        }.distinctBy { entry ->
            "${entry.transactionTimeText}|${entry.amountMinor}|${entry.transactionKindLabel}|${entry.merchantTitle}"
        }
    }

    private fun parsePaymentRecordWindow(
        source: BillSyncSource,
        lines: List<String>,
        amountLineIndex: Int,
        amountText: String,
        fallbackTransactionTimeText: String?
    ): ParsedBillEntry? {
        val start = (amountLineIndex - RECORD_WINDOW_BEFORE_LINES).coerceAtLeast(0)
        val end = (amountLineIndex + RECORD_WINDOW_AFTER_LINES).coerceAtMost(lines.lastIndex)
        val windowLines = lines.subList(start, end + 1)
        val windowText = windowLines.joinToString("\n")
        val linesBeforeAmount = windowLines.take(amountLineIndex - start)
        if (windowText.hasPaymentInitiationKeyword()) return null

        val amountMinor = parseAmountMinor(amountText) ?: return null
        val isCompletedPaymentResult = windowText.isCompletedPaymentResultSurface()
        val explicitTransactionTimeText = windowLines.firstNotNullOfOrNull {
            it.extractTransactionTimeText()
        }
        val fallbackTimeText = fallbackTransactionTimeText.takeIf {
            explicitTransactionTimeText == null && isCompletedPaymentResult
        }
        val transactionTimeText = explicitTransactionTimeText
            ?: fallbackTimeText
            ?: return null
        val transactionKindLabel = windowText.inferTransactionKindLabel() ?: return null
        val merchantTitle = extractMerchantTitle(
            windowText = windowText,
            lines = windowLines,
            linesBeforeAmount = linesBeforeAmount
        ) ?: source.genericPaymentTitle.takeIf { isCompletedPaymentResult }
            ?: return null
        val fundingAccountLabel = extractFundingAccountLabel(windowText, windowLines)
            ?: source.defaultFundingAccountLabel
        val rawLine = windowLines.joinToString(" ")

        return ParsedBillEntry(
            sourceLabel = source.label,
            merchantTitle = merchantTitle,
            amountMinor = amountMinor,
            transactionKindLabel = transactionKindLabel,
            fundingAccountLabel = fundingAccountLabel,
            transactionTimeText = transactionTimeText,
            rawLine = rawLine,
            parsedFields = listOf(
                "来源=${source.label}",
                "商户=$merchantTitle",
                "金额=${amountMinorToText(amountMinor)}",
                "类型=$transactionKindLabel"
            ),
            transactionTimeFromFallback = fallbackTimeText != null
        )
    }

    private fun amountMinorToText(amountMinor: Long): String {
        val yuan = amountMinor / 100
        val cents = kotlin.math.abs(amountMinor % 100)
        return "$yuan.${cents.toString().padStart(2, '0')}"
    }

    private companion object {
        const val RECORD_WINDOW_BEFORE_LINES = 8
        const val RECORD_WINDOW_AFTER_LINES = 10

        val billLineRegex = Regex(
            pattern = """^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})\s+(.+?)\s+(支出|收入|退款|转账|红包|还款|投资|手续费)\s+(?:[¥￥])?(\d+(?:\.\d{1,2})?)(?:元)?(?:\s+(.+))?$"""
        )

    }
}

private fun String.normalizedLines(): List<String> = lineSequence()
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .toList()

private fun MatchResult.amountText(): String? =
    groupValues.drop(1).firstOrNull { it.isNotBlank() }

private fun String.isSupportedPaymentRecordSurface(): Boolean =
    PAYMENT_RECORD_SURFACE_KEYWORDS.any { contains(it) }

private fun String.isCompletedPaymentResultSurface(): Boolean =
    PAYMENT_COMPLETION_KEYWORDS.any { contains(it) }

private fun String.hasPaymentInitiationKeyword(): Boolean =
    PAYMENT_INITIATION_KEYWORDS.any { contains(it) }

private fun String.hasPaymentRecordEvidence(): Boolean =
    explicitPaymentAmountRegex.containsMatchIn(this) &&
        lineSequence().any { it.extractTransactionTimeText() != null } &&
        inferTransactionKindLabel() != null

private fun String.hasCompletedPaymentResultEvidence(): Boolean =
    isCompletedPaymentResultSurface() &&
        explicitPaymentAmountRegex.containsMatchIn(this) &&
        inferTransactionKindLabel() != null

private fun String.inferTransactionKindLabel(): String? = when {
    contains("退款") -> "退款"
    contains("收入") ||
        contains("收款到账") ||
        contains("收款成功") ||
        contains("入账") ||
        Regex("""收到.+?(转账|红包)""").containsMatchIn(this) ||
        contains("向你转账") -> "收入"

    contains("发出红包") || contains("红包已发出") || contains("红包发送成功") -> "支出"
    contains("支出") ||
        contains("付款") ||
        contains("支付成功") ||
        contains("交易成功") ||
        contains("转账成功") ||
        contains("扫码支付") ||
        contains("碰一碰支付") ||
        contains("转账给") ||
        Regex("""向(?!你).+?转账""").containsMatchIn(this) -> "支出"

    contains("红包") -> "红包"
    contains("转账") -> "转账"
    else -> null
}

private fun String.extractTransactionTimeText(): String? {
    numericDateTimeRegex.find(this)?.let { match ->
        return formatTransactionTime(
            year = match.groupValues[1],
            month = match.groupValues[2],
            day = match.groupValues[3],
            hour = match.groupValues[4],
            minute = match.groupValues[5]
        )
    }
    chineseDateTimeRegex.find(this)?.let { match ->
        return formatTransactionTime(
            year = match.groupValues[1],
            month = match.groupValues[2],
            day = match.groupValues[3],
            hour = match.groupValues[4],
            minute = match.groupValues[5]
        )
    }
    return null
}

private fun formatTransactionTime(
    year: String,
    month: String,
    day: String,
    hour: String,
    minute: String
): String = "${year.padStart(4, '0')}-${month.padStart(2, '0')}-${day.padStart(2, '0')} " +
    "${hour.padStart(2, '0')}:${minute.padStart(2, '0')}"

private fun extractMerchantTitle(
    windowText: String,
    lines: List<String>,
    linesBeforeAmount: List<String>
): String? {
    val p2pTitle = extractP2pTitle(windowText)
    if (p2pTitle != null) return p2pTitle

    merchantInlineRegex.find(windowText)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isMeaningfulPaymentRecordValue() }
        ?.let { return it }

    extractValueAfterLabels(lines, MERCHANT_LABELS)?.let { return it }

    if (windowText.contains("发出红包") || windowText.contains("红包已发出")) {
        return "红包"
    }

    return linesBeforeAmount
        .asReversed()
        .firstOrNull { it.isMeaningfulPaymentRecordTitle() }
}

private fun extractP2pTitle(windowText: String): String? {
    val p2pPatterns = listOf(
        Regex("""待(.+?)确认收款"""),
        Regex("""收到(.+?)的红包"""),
        Regex("""收到(.+?)的转账"""),
        Regex("""([^\s]+?)向你转账"""),
        Regex("""转账给(.+?)(?:\s|$)"""),
        Regex("""向([^\s]+?)转账""")
    )
    return p2pPatterns
        .asSequence()
        .mapNotNull { regex -> regex.find(windowText)?.groupValues?.getOrNull(1)?.trim() }
        .firstOrNull { it.isMeaningfulPaymentRecordValue() && it != "你" }
}

private fun extractFundingAccountLabel(
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

private fun extractValueAfterLabels(
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
                    .firstOrNull { it.isMeaningfulPaymentRecordValue() }
                    ?.let { return it }
            }
        }
    }
    return null
}

private fun String.valueAfterLabel(label: String): String? {
    if (!startsWith(label)) return null
    val value = removePrefix(label)
    if (value.isBlank()) return ""
    if (value.first() !in listOf(':', '：', ' ', '\t')) return null
    return value.trim().trimStart(':', '：').trim()
}

private fun String.isMeaningfulPaymentRecordValue(): Boolean {
    val value = trim()
    if (value.isBlank()) return false
    if (explicitPaymentAmountRegex.containsMatchIn(value)) return false
    if (value.extractTransactionTimeText() != null) return false
    if (value in FIELD_LABELS) return false
    if (PAYMENT_RECORD_NOISE_VALUES.any { value == it }) return false
    if (PAYMENT_RECORD_NOISE_CONTAINS.any { value.contains(it) }) return false
    return true
}

private fun String.isMeaningfulPaymentRecordTitle(): Boolean {
    val value = trim()
    return value.length in 2..64 &&
        value.isMeaningfulPaymentRecordValue() &&
        PAYMENT_RECORD_TITLE_NOISE_VALUES.none { value == it }
}

private fun String.isNonTransactionAmountLine(): Boolean {
    val text = trim()
    return NON_TRANSACTION_AMOUNT_KEYWORDS.any { text.contains(it) } &&
        TRANSACTION_AMOUNT_OVERRIDE_KEYWORDS.none { text.contains(it) }
}

private fun MatchResult.isNonTransactionAmountMatch(line: String): Boolean {
    val amountPrefix = line.substring(0, range.first.coerceAtMost(line.length)).takeLast(8)
    return NON_TRANSACTION_AMOUNT_KEYWORDS.any { amountPrefix.contains(it) } &&
        TRANSACTION_AMOUNT_OVERRIDE_KEYWORDS.none { amountPrefix.contains(it) }
}

private val numericDateTimeRegex = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\s+(\d{1,2}):(\d{2})""")
private val chineseDateTimeRegex = Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日?\s*(\d{1,2}):(\d{2})""")

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
    "付款成功",
    "交易成功",
    "转账成功",
    "收款成功",
    "退款成功",
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

private val NON_TRANSACTION_AMOUNT_KEYWORDS = listOf(
    "余额",
    "可用额度",
    "优惠",
    "折扣",
    "抵扣",
    "红包抵扣",
    "积分",
    "手续费"
)

private val TRANSACTION_AMOUNT_OVERRIDE_KEYWORDS = listOf(
    "交易金额",
    "付款金额",
    "收款金额",
    "转账金额",
    "红包金额",
    "实付",
    "实收",
    "金额"
)

private val MERCHANT_LABELS = listOf(
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
    "扣款方式",
    "资金渠道",
    "支付账户",
    "付款账户"
)

private val FIELD_LABELS = MERCHANT_LABELS + FUNDING_LABELS + listOf(
    "金额",
    "交易金额",
    "付款金额",
    "交易时间",
    "支付时间",
    "创建时间",
    "当前状态",
    "交易状态"
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
    "扫码支付",
    "碰一碰支付",
    "当前状态"
)

private val PAYMENT_RECORD_TITLE_NOISE_VALUES = listOf(
    "返回",
    "更多",
    "帮助",
    "投诉",
    "查看往来记录",
    "对此订单有疑问"
)

private val merchantInlineRegex = Regex(
    pattern = """(?:商户|商家|收款方|付款方|对方账户|对方|交易对象|收款人|付款人)[:：]\s*([^\n，,]+)"""
)

private val fundingInlineRegex = Regex(
    pattern = """(?:付款方式|支付方式|扣款方式|资金渠道|支付账户|付款账户)[:：]\s*([^\n，,]+)"""
)

private val explicitPaymentAmountRegex = Regex(
    pattern = """(?:[¥￥]\s*([+-]?\d+(?:\.\d{1,2})?)|([+-]?\d+(?:\.\d{1,2})?)\s*元)"""
)

private val BillSyncSource.genericPaymentTitle: String
    get() = "${label}支付"
