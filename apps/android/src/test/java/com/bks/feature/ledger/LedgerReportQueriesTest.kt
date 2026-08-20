package com.bks.feature.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LedgerReportQueriesTest {
    @Test
    fun monthlySummarySeparatesActiveExpenseIncomeAndNet() {
        val entries = listOf(
            sampleLedgerEntry(id = "food", amountMinor = 3590, flowType = LedgerFlowType.EXPENSE),
            sampleLedgerEntry(id = "ride", amountMinor = 600, flowType = LedgerFlowType.EXPENSE),
            sampleLedgerEntry(id = "refund", amountMinor = 1290, flowType = LedgerFlowType.INCOME),
            sampleLedgerEntry(id = "transfer", amountMinor = 20_000, flowType = LedgerFlowType.NEUTRAL),
            sampleLedgerEntry(
                id = "deleted",
                amountMinor = 9999,
                flowType = LedgerFlowType.EXPENSE,
                deletedAtEpochMillis = 1
            )
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
            sampleLedgerEntry(id = "old", amountMinor = 9999, category = "餐饮", monthKey = "2026-06"),
            sampleLedgerEntry(
                id = "deleted",
                amountMinor = 8888,
                category = "购物",
                deletedAtEpochMillis = 1
            )
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
    fun latestCashFlowMonthIgnoresNeutralAndDeletedEntries() {
        val entries = listOf(
            sampleLedgerEntry(id = "expense", amountMinor = 100, monthKey = "2026-01"),
            sampleLedgerEntry(
                id = "income",
                amountMinor = 600,
                monthKey = "2026-08",
                flowType = LedgerFlowType.INCOME
            ),
            sampleLedgerEntry(
                id = "neutral",
                amountMinor = 5000,
                monthKey = "2026-12",
                flowType = LedgerFlowType.NEUTRAL
            ),
            sampleLedgerEntry(
                id = "deleted",
                amountMinor = 700,
                monthKey = "2027-01",
                flowType = LedgerFlowType.INCOME,
                deletedAtEpochMillis = 1
            )
        )

        assertEquals("2026-08", latestCashFlowMonthKey(entries))
        assertNull(
            latestCashFlowMonthKey(
                listOf(
                    sampleLedgerEntry(
                        id = "neutral-only",
                        amountMinor = 100,
                        flowType = LedgerFlowType.NEUTRAL
                    )
                )
            )
        )
    }

    @Test
    fun monthlyCashFlowRangeReturnsSevenAscendingMonthsAcrossYearBoundary() {
        val entries = listOf(
            sampleLedgerEntry(id = "oct-expense", amountMinor = 100, monthKey = "2025-10"),
            sampleLedgerEntry(
                id = "dec-income",
                amountMinor = 200,
                monthKey = "2025-12",
                flowType = LedgerFlowType.INCOME
            ),
            sampleLedgerEntry(id = "jan-expense", amountMinor = 300, monthKey = "2026-01"),
            sampleLedgerEntry(
                id = "jan-income",
                amountMinor = 400,
                monthKey = "2026-01",
                flowType = LedgerFlowType.INCOME
            ),
            sampleLedgerEntry(
                id = "jan-neutral",
                amountMinor = 9999,
                monthKey = "2026-01",
                flowType = LedgerFlowType.NEUTRAL
            ),
            sampleLedgerEntry(id = "apr-expense", amountMinor = 500, monthKey = "2026-04"),
            sampleLedgerEntry(
                id = "deleted-mar",
                amountMinor = 8888,
                monthKey = "2026-03",
                deletedAtEpochMillis = 1
            )
        )

        val totals = monthlyCashFlowRange(entries, anchorMonthKey = "2026-01")

        assertEquals(
            listOf(
                MonthlyCashFlowTotal("2025-10", expenseMinor = 100, incomeMinor = 0),
                MonthlyCashFlowTotal("2025-11", expenseMinor = 0, incomeMinor = 0),
                MonthlyCashFlowTotal("2025-12", expenseMinor = 0, incomeMinor = 200),
                MonthlyCashFlowTotal("2026-01", expenseMinor = 300, incomeMinor = 400),
                MonthlyCashFlowTotal("2026-02", expenseMinor = 0, incomeMinor = 0),
                MonthlyCashFlowTotal("2026-03", expenseMinor = 0, incomeMinor = 0),
                MonthlyCashFlowTotal("2026-04", expenseMinor = 500, incomeMinor = 0)
            ),
            totals
        )
    }

    @Test
    fun categoryShareSlicesUseTopFourAndExactTenths() {
        val slices = categoryShareSlices(
            listOf(
                CategoryTotal(category = "E", amountMinor = 60),
                CategoryTotal(category = "B", amountMinor = 90),
                CategoryTotal(category = "F", amountMinor = 50),
                CategoryTotal(category = "D", amountMinor = 70),
                CategoryTotal(category = "A", amountMinor = 100),
                CategoryTotal(category = "C", amountMinor = 80)
            )
        )

        assertEquals(
            listOf(
                CategoryShareSlice(category = "A", amountMinor = 100, percentageTenths = 222),
                CategoryShareSlice(category = "B", amountMinor = 90, percentageTenths = 200),
                CategoryShareSlice(category = "C", amountMinor = 80, percentageTenths = 178),
                CategoryShareSlice(category = "D", amountMinor = 70, percentageTenths = 156),
                CategoryShareSlice(category = "其他", amountMinor = 110, percentageTenths = 244)
            ),
            slices
        )
        assertEquals(1000, slices.sumOf { it.percentageTenths })
    }

    @Test
    fun categoryShareSlicesAllocateRoundingRemainderDeterministically() {
        val slices = categoryShareSlices(
            listOf(
                CategoryTotal(category = "C", amountMinor = 1),
                CategoryTotal(category = "B", amountMinor = 1),
                CategoryTotal(category = "A", amountMinor = 1)
            )
        )

        assertEquals(
            listOf(
                CategoryShareSlice(category = "A", amountMinor = 1, percentageTenths = 334),
                CategoryShareSlice(category = "B", amountMinor = 1, percentageTenths = 333),
                CategoryShareSlice(category = "C", amountMinor = 1, percentageTenths = 333)
            ),
            slices
        )
    }

    @Test
    fun categoryShareSlicesAreSafeForEmptyAndZeroTotals() {
        assertEquals(emptyList<CategoryShareSlice>(), categoryShareSlices(emptyList()))
        assertEquals(
            emptyList<CategoryShareSlice>(),
            categoryShareSlices(
                listOf(
                    CategoryTotal(category = "zero", amountMinor = 0),
                    CategoryTotal(category = "negative", amountMinor = -1)
                )
            )
        )
    }

    private fun sampleLedgerEntry(
        id: String,
        amountMinor: Long,
        category: String = "餐饮",
        flowType: LedgerFlowType = LedgerFlowType.EXPENSE,
        monthKey: String = "2026-07",
        deletedAtEpochMillis: Long? = null
    ): LedgerUiEntry = LedgerUiEntry(
        id = id,
        title = id,
        amountMinor = amountMinor,
        monthKey = monthKey,
        transactionTimeText = "$monthKey-08 12:20",
        category = category,
        sourceLabel = "微信",
        kindLabel = if (flowType == LedgerFlowType.EXPENSE) "支出" else "收入",
        flowType = flowType,
        deletedAtEpochMillis = deletedAtEpochMillis
    )
}
