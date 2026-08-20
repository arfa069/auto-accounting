package com.bks.feature.billsync

internal fun parsePaymentRecordSurface(
    source: BillSyncSource,
    lines: List<String>,
    fallbackTransactionTimeText: String?
): List<ParsedBillEntry> {
    val pageText = lines.joinToString("\n")
    if (!pageText.isSupportedPaymentRecordSurface() &&
        !pageText.isCompletedPaymentResultSurface(source)
    ) {
        return emptyList()
    }
    if (pageText.hasPaymentInitiationKeyword()) return emptyList()

    val isCompletedPaymentResult = pageText.isCompletedPaymentResultSurface(source)

    val amountMatches = collectAmountMatches(lines)
    val selectedMatches = selectAmountMatches(source, pageText, lines, amountMatches)
    return selectedMatches.mapNotNull { (amountLineIndex, match) ->
        val amountText = match.amountText() ?: return@mapNotNull null
        parsePaymentRecordWindow(
            PaymentRecordWindowRequest(
                source = source,
                lines = lines,
                amountLineIndex = amountLineIndex,
                amountText = amountText,
                fallbackTransactionTimeText = fallbackTransactionTimeText,
                isCompletedPaymentResult = isCompletedPaymentResult
            )
        )
    }.distinctBy { entry ->
        "${entry.transactionTimeText}|${entry.amountMinor}|${entry.transactionKindLabel}|${entry.merchantTitle}"
    }
}

private fun collectAmountMatches(lines: List<String>): List<Pair<Int, MatchResult>> = buildList {
    lines.forEachIndexed { amountLineIndex, line ->
        if (line.isNonTransactionAmountLine()) return@forEachIndexed
        if (isPromotionalAmountLine(lines, amountLineIndex)) return@forEachIndexed
        explicitPaymentAmountRegex.findAll(line).forEach { match ->
            if (match.isNonTransactionAmountMatch(line)) return@forEach
            add(amountLineIndex to match)
        }
    }
}

private fun selectAmountMatches(
    source: BillSyncSource,
    pageText: String,
    lines: List<String>,
    amountMatches: List<Pair<Int, MatchResult>>
): List<Pair<Int, MatchResult>> {
    if (!pageText.isCompletedPaymentResultSurface(source)) return amountMatches
    return amountMatches
        .filter { (lineIndex, _) -> lines[lineIndex].hasTransactionAmountOverrideKeyword() }
        .ifEmpty { amountMatches }
        .take(1)
}

private data class PaymentRecordWindowRequest(
    val source: BillSyncSource,
    val lines: List<String>,
    val amountLineIndex: Int,
    val amountText: String,
    val fallbackTransactionTimeText: String?,
    val isCompletedPaymentResult: Boolean
)

private fun parsePaymentRecordWindow(
    request: PaymentRecordWindowRequest
): ParsedBillEntry? {
    val context = PaymentRecordWindowContext.create(
        source = request.source,
        lines = request.lines,
        amountLineIndex = request.amountLineIndex,
        amountText = request.amountText,
        isCompletedPaymentResult = request.isCompletedPaymentResult
    )
    if (context.windowText.hasPaymentInitiationKeyword()) return null

    val amountMinor = parseAmountMinor(context.amountText) ?: return null
    val resolvedTime = context.resolveTransactionTime(request.fallbackTransactionTimeText) ?: return null
    val transactionKindLabel = context.windowText.inferTransactionKindLabel() ?: return null
    return context.toParsedBillEntry(amountMinor, transactionKindLabel, resolvedTime)
}

private data class PaymentRecordWindowContext(
    val source: BillSyncSource,
    val windowLines: List<String>,
    val linesBeforeAmount: List<String>,
    val linesAfterAmount: List<String>,
    val windowText: String,
    val amountText: String,
    val isCompletedPaymentResult: Boolean
) {
    fun resolveTransactionTime(fallbackTransactionTimeText: String?): ResolvedTransactionTime? {
        val explicitTime = windowLines.firstNotNullOfOrNull { it.extractTransactionTimeText() }
        val fallbackTime = fallbackTransactionTimeText.takeIf {
            explicitTime == null && isCompletedPaymentResult
        }
        val time = explicitTime ?: fallbackTime ?: return null
        return ResolvedTransactionTime(time, fallbackTime != null)
    }

    fun toParsedBillEntry(
        amountMinor: Long,
        transactionKindLabel: String,
        resolvedTime: ResolvedTransactionTime
    ): ParsedBillEntry? {
        val extractedMerchantTitle = extractMerchantTitle(
            source = source,
            windowText = windowText,
            lines = windowLines,
            linesBeforeAmount = linesBeforeAmount,
            linesAfterAmount = linesAfterAmount.takeIf { isCompletedPaymentResult }.orEmpty()
        )
        val merchantTitle = extractedMerchantTitle
            ?: source.genericPaymentTitle.takeIf { isCompletedPaymentResult }
            ?: return null
        val extractedFundingAccountLabel = extractFundingAccountLabel(windowText, windowLines)
        val fundingAccountLabel = extractedFundingAccountLabel
            ?: source.defaultFundingAccountLabel
        val productText = extractMultilineValueAfterLabels(windowLines, PRODUCT_LABELS)
            ?: extractValueAfterLabels(windowLines, RECEIPT_NOTE_LABELS)
            ?: merchantTitle
        val counterpartyText = extractMerchantOrPayee(windowText, windowLines) ?: merchantTitle
        val currentStatus = extractImmediateValueAfterLabels(windowLines, STATUS_LABELS)
        val transactionOrderId = extractIdentifierAfterLabels(windowLines, TRANSACTION_ORDER_LABELS)
        val merchantOrderId = extractIdentifierAfterLabels(windowLines, MERCHANT_ORDER_LABELS)
        val rawLine = windowLines.joinToString(" ")
        val parsedFields = buildList {
            add("来源=${source.label}")
            add("商户=$merchantTitle")
            add("金额=${amountMinorToText(amountMinor)}")
            add("类型=$transactionKindLabel")
            add("交易时间=${resolvedTime.text}")
            add("支付方式=$fundingAccountLabel")
            if (extractedFundingAccountLabel == null) add("支付方式来源=默认")
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
            transactionTimeText = resolvedTime.text,
            rawLine = rawLine,
            parsedFields = parsedFields,
            transactionTimeFromFallback = resolvedTime.fromFallback,
            merchantTitleFromFallback = extractedMerchantTitle == null,
            fundingAccountFromFallback = extractedFundingAccountLabel == null
        )
    }

    companion object {
        fun create(
            source: BillSyncSource,
            lines: List<String>,
            amountLineIndex: Int,
            amountText: String,
            isCompletedPaymentResult: Boolean
        ): PaymentRecordWindowContext {
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
            return PaymentRecordWindowContext(
                source = source,
                windowLines = windowLines,
                linesBeforeAmount = windowLines.take(amountLineIndex - start),
                linesAfterAmount = windowLines.drop(amountLineIndex - start + 1),
                windowText = windowLines.joinToString("\n"),
                amountText = amountText,
                isCompletedPaymentResult = isCompletedPaymentResult
            )
        }
    }
}

private data class ResolvedTransactionTime(
    val text: String,
    val fromFallback: Boolean
)

internal fun hasUnambiguousTransactionAmount(pageText: String): Boolean =
    extractUnambiguousTransactionAmountMinor(pageText) != null

internal fun extractUnambiguousTransactionAmountMinor(pageText: String): Long? {
    val lines = pageText.normalizedLines()
    val amounts = lines
        .mapIndexed { index, line -> index to line }
        .flatMap { (index, line) ->
            if (line.isNonTransactionAmountLine() || isPromotionalAmountLine(lines, index)) {
                emptyList()
            } else {
                explicitPaymentAmountRegex.findAll(line)
                    .filterNot { match -> match.isNonTransactionAmountMatch(line) }
                    .mapNotNull { match ->
                        match.amountText()?.let { amountText ->
                            parseAmountMinor(amountText)?.let { amountMinor ->
                                amountMinor to line.hasTransactionAmountOverrideKeyword()
                            }
                        }
                    }
                    .toList()
            }
        }
    val distinctAmounts = amounts.map { (amountMinor, _) -> amountMinor }.distinct()
    if (distinctAmounts.size == 1) return distinctAmounts.single()
    return amounts.filter { (_, isPreferred) -> isPreferred }
        .map { (amountMinor, _) -> amountMinor }
        .distinct()
        .singleOrNull()
}

private const val RECORD_WINDOW_BEFORE_LINES = 8
private const val RECORD_WINDOW_AFTER_LINES = 10

internal val explicitPaymentAmountRegex = Regex(
    pattern = """(?:[¥￥]\s*([+-]?\d+(?:\.\d{1,2})?)|([+-]?\d+(?:\.\d{1,2})?)\s*元)"""
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

internal val TRANSACTION_AMOUNT_OVERRIDE_KEYWORDS = listOf(
    "交易金额",
    "付款金额",
    "收款金额",
    "转账金额",
    "红包金额",
    "实付",
    "实收",
    "金额"
)

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
