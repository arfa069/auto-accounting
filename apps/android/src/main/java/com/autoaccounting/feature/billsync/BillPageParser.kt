package com.autoaccounting.feature.billsync

import com.autoaccounting.feature.monitoring.hasAlipayPaymentResultPageSignature
import com.autoaccounting.feature.monitoring.hasWechatReceivedRedPacketSuccessSignature
import com.autoaccounting.feature.monitoring.hasWechatSentRedPacketSuccessSignature
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
    val transactionTimeFromFallback: Boolean = false,
    val merchantTitleFromFallback: Boolean = false
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
    if (normalizedText.hasCompletedPaymentResultEvidence(source)) {
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
        if (
            !pageText.isSupportedPaymentRecordSurface() &&
            !pageText.isCompletedPaymentResultSurface(source)
        ) {
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
        val selectedMatches = if (pageText.isCompletedPaymentResultSurface(source)) {
            amountMatches
                .filter { (lineIndex, _) ->
                    lines[lineIndex].hasTransactionAmountOverrideKeyword()
                }
                .ifEmpty { amountMatches }
                .take(1)
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
        val fullPageText = lines.joinToString("\n")
        val isCompletedPaymentResult = fullPageText.isCompletedPaymentResultSurface(source)
        val start = if (isCompletedPaymentResult) {
            0
        } else {
            (amountLineIndex - RECORD_WINDOW_BEFORE_LINES).coerceAtLeast(0)
        }
        val end = if (isCompletedPaymentResult) {
            lines.lastIndex
        } else {
            (amountLineIndex + RECORD_WINDOW_AFTER_LINES).coerceAtMost(lines.lastIndex)
        }
        val windowLines = lines.subList(start, end + 1)
        val windowText = windowLines.joinToString("\n")
        val linesBeforeAmount = windowLines.take(amountLineIndex - start)
        if (windowText.hasPaymentInitiationKeyword()) return null

        val amountMinor = parseAmountMinor(amountText) ?: return null
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
        val extractedMerchantTitle = extractMerchantTitle(
            windowText = windowText,
            lines = windowLines,
            linesBeforeAmount = linesBeforeAmount
        )
        val merchantTitle = extractedMerchantTitle
            ?: source.genericPaymentTitle.takeIf { isCompletedPaymentResult }
            ?: return null
        val fundingAccountLabel = extractFundingAccountLabel(windowText, windowLines)
            ?: source.defaultFundingAccountLabel
        val productText = extractMultilineValueAfterLabels(windowLines, PRODUCT_LABELS)
            ?: extractValueAfterLabels(windowLines, RECEIPT_NOTE_LABELS)
            ?: merchantTitle
        val counterpartyText = extractMerchantOrPayee(windowText, windowLines) ?: merchantTitle
        val currentStatus = extractImmediateValueAfterLabels(windowLines, STATUS_LABELS)
        val transactionOrderId = extractIdentifierAfterLabels(
            windowLines,
            TRANSACTION_ORDER_LABELS
        )
        val merchantOrderId = extractIdentifierAfterLabels(windowLines, MERCHANT_ORDER_LABELS)
        val rawLine = windowLines.joinToString(" ")
        val parsedFields = buildList {
            add("来源=${source.label}")
            add("商户=$merchantTitle")
            add("金额=${amountMinorToText(amountMinor)}")
            add("类型=$transactionKindLabel")
            add("交易时间=$transactionTimeText")
            add("支付方式=$fundingAccountLabel")
            add("商品=$productText")
            add("商品名称=$merchantTitle")
            add("商户或收款方=$counterpartyText")
            currentStatus?.let { add("当前状态=$it") }
            transactionOrderId?.let { add("交易单号=$it") }
            merchantOrderId?.let { add("商户单号=$it") }
        }

        return ParsedBillEntry(
            sourceLabel = source.label,
            merchantTitle = merchantTitle,
            amountMinor = amountMinor,
            transactionKindLabel = transactionKindLabel,
            fundingAccountLabel = fundingAccountLabel,
            transactionTimeText = transactionTimeText,
            rawLine = rawLine,
            parsedFields = parsedFields,
            transactionTimeFromFallback = fallbackTimeText != null,
            merchantTitleFromFallback = extractedMerchantTitle == null
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

internal fun hasUnambiguousTransactionAmount(pageText: String): Boolean {
    val amounts = pageText.normalizedLines()
        .flatMap { line ->
            if (line.isNonTransactionAmountLine()) {
                emptyList()
            } else {
                explicitPaymentAmountRegex.findAll(line)
                    .filterNot { match -> match.isNonTransactionAmountMatch(line) }
                    .mapNotNull { match ->
                        match.amountText()?.let { amountText ->
                            runCatching {
                                BigDecimal(amountText.trim().removePrefix("+"))
                                    .abs()
                                    .setScale(2, RoundingMode.HALF_UP)
                                    .movePointRight(2)
                                    .longValueExact()
                            }.getOrNull()?.let { amountMinor ->
                                amountMinor to line.hasTransactionAmountOverrideKeyword()
                            }
                        }
                    }
                    .toList()
            }
        }
    if (amounts.map { (amountMinor, _) -> amountMinor }.distinct().size == 1) return true
    return amounts.filter { (_, isPreferred) -> isPreferred }
        .map { (amountMinor, _) -> amountMinor }
        .distinct()
        .size == 1
}

private fun String.isSupportedPaymentRecordSurface(): Boolean =
    PAYMENT_RECORD_SURFACE_KEYWORDS.any { contains(it) }

private fun String.isCompletedPaymentResultSurface(source: BillSyncSource): Boolean = when (source) {
    BillSyncSource.Alipay ->
        PAYMENT_COMPLETION_KEYWORDS.any { contains(it) } &&
            hasAlipayPaymentResultPageSignature(this)
    BillSyncSource.WeChat ->
        PAYMENT_COMPLETION_KEYWORDS.any { contains(it) } ||
            hasWechatReceivedRedPacketSuccessSignature(this) ||
            hasWechatSentRedPacketSuccessSignature(this)
}

private fun String.hasPaymentInitiationKeyword(): Boolean =
    PAYMENT_INITIATION_KEYWORDS.any { contains(it) }

private fun String.hasPaymentRecordEvidence(): Boolean =
    explicitPaymentAmountRegex.containsMatchIn(this) &&
        lineSequence().any { it.extractTransactionTimeText() != null } &&
        inferTransactionKindLabel() != null

private fun String.hasCompletedPaymentResultEvidence(source: BillSyncSource): Boolean =
    isCompletedPaymentResultSurface(source) &&
        explicitPaymentAmountRegex.containsMatchIn(this) &&
        inferTransactionKindLabel() != null

private fun String.inferTransactionKindLabel(): String? = when {
    hasWechatSentRedPacketSuccessSignature(this) -> "支出"
    hasWechatReceivedRedPacketSuccessSignature(this) -> "收入"
    contains("退款") -> "退款"
    contains("对方已收") && contains("转账") -> "支出"
    hasCurrentStatusPaymentSuccessPair(this) -> "支出"
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
    if (hasWechatSentRedPacketSuccessSignature(windowText)) return "红包"

    extractMultilineValueAfterLabels(lines, PRODUCT_LABELS)?.let { return it }

    val p2pTitle = extractP2pTitle(windowText)
    if (p2pTitle != null) return p2pTitle

    linesBeforeAmount
        .asReversed()
        .firstOrNull { it.isMeaningfulPaymentRecordTitle() }
        ?.let { return it }

    extractMerchantOrPayee(windowText, lines)?.let { return it }

    if (windowText.contains("发出红包") || windowText.contains("红包已发出")) {
        return "红包"
    }

    return null
}

private fun extractMerchantOrPayee(windowText: String, lines: List<String>): String? {
    merchantInlineRegex.find(windowText)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isMeaningfulPaymentRecordValue() }
        ?.let { return it }

    return extractValueAfterLabels(lines, MERCHANT_LABELS)
}

private fun extractP2pTitle(windowText: String): String? {
    val p2pPatterns = listOf(
        Regex("""待(.+?)确认收款"""),
        Regex("""收到(.+?)的红包"""),
        Regex("""(?:^|\n)([^\n]+?)的红包(?:\n|$)"""),
        Regex("""收到(.+?)的转账"""),
        Regex("""([^\s]+?)向你转账"""),
        Regex("""(?:^|\n)转账[-－—]?转给([^\n]+)(?:\n|$)"""),
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

private fun extractImmediateValueAfterLabels(
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

private fun extractMultilineValueAfterLabels(
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

private fun extractIdentifierAfterLabels(
    lines: List<String>,
    labels: List<String>
): String? {
    for ((index, line) in lines.withIndex()) {
        for (label in labels) {
            val inlineValue = line.valueAfterLabel(label) ?: continue

            val identifierParts = buildList {
                inlineValue.filterNot(Char::isWhitespace)
                    .takeIf(String::isNotBlank)
                    ?.let(::add)
                lines.drop(index + 1)
                    .takeWhile { nextLine ->
                        !nextLine.isKnownFieldLine() &&
                            nextLine.filterNot(Char::isWhitespace).matches(IDENTIFIER_PART_REGEX)
                    }
                    .map { it.filterNot(Char::isWhitespace) }
                    .forEach(::add)
            }
            return identifierParts.joinToString("").takeIf(String::isNotBlank)
        }
    }
    return null
}

private fun String.isKnownFieldLine(): Boolean {
    val line = trim()
    return FIELD_LABELS.any { label ->
        line.trimEnd(':', '：') == label || line.valueAfterLabel(label) != null
    }
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
    if (value.isKnownFieldLine()) return false
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

private fun String.hasTransactionAmountOverrideKeyword(): Boolean =
    TRANSACTION_AMOUNT_OVERRIDE_KEYWORDS.any(::contains)

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
    "退款方式",
    "扣款方式",
    "资金渠道",
    "支付账户",
    "付款账户"
)

private val PRODUCT_LABELS = listOf(
    "商品名称",
    "商品"
)

private val RECEIPT_NOTE_LABELS = listOf("收款方备注")

private val STATUS_LABELS = listOf("当前状态", "交易状态", "退款状态")

private val TRANSACTION_ORDER_LABELS = listOf("交易单号", "转账单号", "退款单号")

private val MERCHANT_ORDER_LABELS = listOf("商户单号")

private val FIELD_LABELS = MERCHANT_LABELS + FUNDING_LABELS + PRODUCT_LABELS +
    RECEIPT_NOTE_LABELS + STATUS_LABELS + TRANSACTION_ORDER_LABELS +
    MERCHANT_ORDER_LABELS + listOf(
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
    pattern = """(?:付款方式|支付方式|扣款方式|资金渠道|支付账户|付款账户)[:：]\s*([^\n，,]+)"""
)

private val explicitPaymentAmountRegex = Regex(
    pattern = """(?:[¥￥]\s*([+-]?\d+(?:\.\d{1,2})?)|([+-]?\d+(?:\.\d{1,2})?)\s*元)"""
)

private val IDENTIFIER_PART_REGEX = Regex("[A-Za-z0-9]+")

private const val MAX_MULTILINE_FIELD_LINES = 3

private val BillSyncSource.genericPaymentTitle: String
    get() = "${label}支付"
