package com.autoaccounting.feature.ledger

import com.autoaccounting.feature.review.ReviewQueueConfirmedEntry

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

fun ReviewQueueConfirmedEntry.toLedgerUiEntry(): LedgerUiEntry {
    val source = entry.sourceLabel
    val kind = entry.kindLabel
    return LedgerUiEntry(
        id = id,
        title = entry.title,
        amountMinor = entry.amountMinor,
        monthKey = entry.transactionTimeText.take(7),
        transactionTimeText = entry.transactionTimeText,
        category = entry.category,
        sourceLabel = source,
        kindLabel = kind,
        flowType = if (kind == "收入" || kind == "退款") {
            LedgerFlowType.INCOME
        } else {
            LedgerFlowType.EXPENSE
        },
        note = entry.note
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
