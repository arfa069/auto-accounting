package com.autoaccounting.feature.ledger

import com.autoaccounting.data.local.LedgerEntryEntity
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.TransactionKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LedgerFlowType {
    EXPENSE,
    INCOME
}

data class LedgerUiEntry(
    val id: String,
    val title: String,
    val amountMinor: Long,
    val monthKey: String,
    val transactionTimeText: String,
    val category: String,
    val sourceLabel: String,
    val kindLabel: String,
    val flowType: LedgerFlowType,
    val note: String? = null
)

data class MonthlySummary(
    val expenseMinor: Long,
    val incomeMinor: Long,
    val netMinor: Long
)

data class CategoryTotal(
    val category: String,
    val amountMinor: Long
)

data class MonthlyCategoryTotal(
    val monthKey: String,
    val amountMinor: Long
)

fun LedgerEntryEntity.toLedgerUiEntry(
    zoneId: ZoneId = ZoneId.systemDefault()
): LedgerUiEntry {
    val transactionTimeText = formatLedgerDateTime(transactionTimeEpochMillis, zoneId)
    val kind = transactionKind.toLabel()
    return LedgerUiEntry(
        id = id,
        title = merchantTitle,
        amountMinor = amountMinor,
        monthKey = transactionTimeText.take(7),
        transactionTimeText = transactionTimeText,
        category = categoryId?.toCategoryLabel() ?: "未分类",
        sourceLabel = source.toLabel(),
        kindLabel = kind,
        flowType = if (transactionKind == TransactionKind.INCOME ||
            transactionKind == TransactionKind.REFUND
        ) {
            LedgerFlowType.INCOME
        } else {
            LedgerFlowType.EXPENSE
        },
        note = note
    )
}

fun monthlySummary(
    entries: List<LedgerUiEntry>,
    monthKey: String
): MonthlySummary {
    val currentMonthEntries = entries.filter { it.monthKey == monthKey }
    val expense = currentMonthEntries
        .filter { it.flowType == LedgerFlowType.EXPENSE }
        .sumOf { it.amountMinor }
    val income = currentMonthEntries
        .filter { it.flowType == LedgerFlowType.INCOME }
        .sumOf { it.amountMinor }
    return MonthlySummary(
        expenseMinor = expense,
        incomeMinor = income,
        netMinor = income - expense
    )
}

fun categoryExpenseTotals(
    entries: List<LedgerUiEntry>,
    monthKey: String
): List<CategoryTotal> = entries
    .filter { it.monthKey == monthKey && it.flowType == LedgerFlowType.EXPENSE }
    .groupBy { it.category }
    .map { (category, categoryEntries) ->
        CategoryTotal(
            category = category,
            amountMinor = categoryEntries.sumOf { it.amountMinor }
        )
    }
    .sortedByDescending { it.amountMinor }

fun categoryTrend(
    entries: List<LedgerUiEntry>,
    category: String,
    latestMonthKey: String,
    monthCount: Int = 6
): List<MonthlyCategoryTotal> {
    return previousMonths(latestMonthKey, monthCount).map { monthKey ->
        MonthlyCategoryTotal(
            monthKey = monthKey,
            amountMinor = entries
                .filter {
                    it.monthKey == monthKey &&
                        it.category == category &&
                        it.flowType == LedgerFlowType.EXPENSE
                }
                .sumOf { it.amountMinor }
        )
    }
}

fun latestMonthKey(entries: List<LedgerUiEntry>): String =
    entries.maxOfOrNull { it.monthKey } ?: "2026-07"

fun formatMoney(amountMinor: Long): String {
    val yuan = amountMinor / 100
    val cents = kotlin.math.abs(amountMinor % 100)
    return "¥$yuan.${cents.toString().padStart(2, '0')}"
}

fun formatSignedMoney(amountMinor: Long): String {
    val sign = when {
        amountMinor > 0 -> "+"
        amountMinor < 0 -> "-"
        else -> ""
    }
    return "$sign${formatMoney(kotlin.math.abs(amountMinor))}"
}

private fun previousMonths(
    latestMonthKey: String,
    count: Int
): List<String> {
    val year = latestMonthKey.substringBefore("-").toInt()
    val month = latestMonthKey.substringAfter("-").toInt()
    val latestMonthIndex = year * 12 + (month - 1)
    return ((latestMonthIndex - count + 1)..latestMonthIndex).map { monthIndex ->
        val itemYear = monthIndex / 12
        val itemMonth = monthIndex % 12 + 1
        "$itemYear-${itemMonth.toString().padStart(2, '0')}"
    }
}

private fun PaymentSource.toLabel(): String = when (this) {
    PaymentSource.WECHAT -> "微信"
    PaymentSource.ALIPAY -> "支付宝"
}

private fun TransactionKind.toLabel(): String = when (this) {
    TransactionKind.EXPENSE -> "支出"
    TransactionKind.INCOME -> "收入"
    TransactionKind.REFUND -> "退款"
    TransactionKind.TRANSFER -> "转账"
    TransactionKind.RED_PACKET -> "红包"
    TransactionKind.REPAYMENT -> "还款"
    TransactionKind.INVESTMENT -> "理财"
    TransactionKind.FEE -> "手续费"
    TransactionKind.OTHER -> "其他"
}

private fun String.toCategoryLabel(): String = when (this) {
    "food" -> "餐饮"
    "transport" -> "交通"
    "shopping" -> "购物"
    "housing" -> "居住"
    "healthcare" -> "医疗健康"
    "salary" -> "工资"
    "refund" -> "退款"
    "uncategorized" -> "未分类"
    else -> this
}

private fun formatLedgerDateTime(epochMillis: Long, zoneId: ZoneId): String =
    LEDGER_DATE_TIME_FORMATTER.withZone(zoneId).format(Instant.ofEpochMilli(epochMillis))

private val LEDGER_DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
