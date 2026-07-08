package com.autoaccounting.feature.ledger

import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerReportQueriesTest {
    @Test
    fun monthlySummarySeparatesExpenseIncomeAndNet() {
        val entries = listOf(
            sampleLedgerEntry(id = "food", amountMinor = 3590, flowType = LedgerFlowType.EXPENSE),
            sampleLedgerEntry(id = "ride", amountMinor = 600, flowType = LedgerFlowType.EXPENSE),
            sampleLedgerEntry(id = "refund", amountMinor = 1290, flowType = LedgerFlowType.INCOME)
        )

        val summary = monthlySummary(entries, monthKey = "2026-07")

        assertEquals(4190, summary.expenseMinor)
        assertEquals(1290, summary.incomeMinor)
        assertEquals(-2900, summary.netMinor)
    }

    @Test
    fun categoryTotalsGroupCurrentMonthExpenses() {
        val entries = listOf(
            sampleLedgerEntry(id = "food-1", amountMinor = 3590, category = "餐饮"),
            sampleLedgerEntry(id = "food-2", amountMinor = 1200, category = "餐饮"),
            sampleLedgerEntry(id = "ride", amountMinor = 600, category = "交通"),
            sampleLedgerEntry(id = "old", amountMinor = 9999, category = "餐饮", monthKey = "2026-06")
        )

        val totals = categoryExpenseTotals(entries, monthKey = "2026-07")

        assertEquals(
            listOf(
                CategoryTotal(category = "餐饮", amountMinor = 4790),
                CategoryTotal(category = "交通", amountMinor = 600)
            ),
            totals
        )
    }

    @Test
    fun categoryTrendReturnsLatestSixMonthsInOrder() {
        val entries = listOf(
            sampleLedgerEntry(id = "jan", amountMinor = 100, category = "餐饮", monthKey = "2026-01"),
            sampleLedgerEntry(id = "jun", amountMinor = 600, category = "餐饮", monthKey = "2026-06"),
            sampleLedgerEntry(id = "jul", amountMinor = 700, category = "餐饮", monthKey = "2026-07"),
            sampleLedgerEntry(id = "other", amountMinor = 5000, category = "交通", monthKey = "2026-07")
        )

        val trend = categoryTrend(
            entries = entries,
            category = "餐饮",
            latestMonthKey = "2026-07",
            monthCount = 6
        )

        assertEquals(
            listOf(
                MonthlyCategoryTotal("2026-02", 0),
                MonthlyCategoryTotal("2026-03", 0),
                MonthlyCategoryTotal("2026-04", 0),
                MonthlyCategoryTotal("2026-05", 0),
                MonthlyCategoryTotal("2026-06", 600),
                MonthlyCategoryTotal("2026-07", 700)
            ),
            trend
        )
    }

    private fun sampleLedgerEntry(
        id: String,
        amountMinor: Long,
        category: String = "餐饮",
        flowType: LedgerFlowType = LedgerFlowType.EXPENSE,
        monthKey: String = "2026-07"
    ): LedgerUiEntry = LedgerUiEntry(
        id = id,
        title = id,
        amountMinor = amountMinor,
        monthKey = monthKey,
        transactionTimeText = "$monthKey-08 12:20",
        category = category,
        sourceLabel = "微信",
        kindLabel = if (flowType == LedgerFlowType.EXPENSE) "支出" else "收入",
        flowType = flowType
    )
}
