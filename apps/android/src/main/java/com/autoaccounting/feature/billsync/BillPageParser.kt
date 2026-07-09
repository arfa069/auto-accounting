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
    val parsedFields: List<String>
)

class BillPageParser {
    fun parse(
        source: BillSyncSource,
        pageText: String
    ): List<ParsedBillEntry> = pageText
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line -> parseLine(source, line) }
        .toList()

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
        BigDecimal(text.trim())
            .setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2)
            .longValueExact()
    }.getOrNull()

    private fun amountMinorToText(amountMinor: Long): String {
        val yuan = amountMinor / 100
        val cents = kotlin.math.abs(amountMinor % 100)
        return "$yuan.${cents.toString().padStart(2, '0')}"
    }

    private companion object {
        val billLineRegex = Regex(
            pattern = """^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})\s+(.+?)\s+(支出|收入|退款|转账|红包|还款|投资|手续费)\s+(?:[¥￥])?(\d+(?:\.\d{1,2})?)(?:元)?(?:\s+(.+))?$"""
        )
    }
}
