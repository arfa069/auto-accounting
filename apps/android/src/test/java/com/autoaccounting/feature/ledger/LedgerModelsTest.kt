package com.autoaccounting.feature.ledger

import com.autoaccounting.data.local.LedgerEntryEntity
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.TransactionKind
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerModelsTest {
    @Test
    fun ledgerUiEntryMapsPersistedLedgerRecordForLedgerAndReports() {
        val entry = ledgerEntry(
            id = "ledger-1",
            transactionKind = TransactionKind.REFUND,
            categoryId = "refund"
        ).toLedgerUiEntry(ZoneId.of("UTC"))

        assertEquals("ledger-1", entry.id)
        assertEquals("2026-07", entry.monthKey)
        assertEquals("2026-07-08 12:20", entry.transactionTimeText)
        assertEquals("退款", entry.category)
        assertEquals("支付宝", entry.sourceLabel)
        assertEquals("退款", entry.kindLabel)
        assertEquals(LedgerFlowType.INCOME, entry.flowType)
    }

    @Test
    fun reportAggregatesUsePersistedLedgerEntries() {
        val entries = listOf(
            ledgerEntry(
                id = "food-july",
                amountMinor = 3590,
                categoryId = "food",
                transactionTimeEpochMillis = JULY_2026
            ).toLedgerUiEntry(ZoneId.of("UTC")),
            ledgerEntry(
                id = "transport-july",
                amountMinor = 600,
                categoryId = "transport",
                transactionTimeEpochMillis = JULY_2026 + 60_000
            ).toLedgerUiEntry(ZoneId.of("UTC")),
            ledgerEntry(
                id = "salary-july",
                transactionKind = TransactionKind.INCOME,
                amountMinor = 10_000,
                categoryId = "salary",
                transactionTimeEpochMillis = JULY_2026 + 120_000
            ).toLedgerUiEntry(ZoneId.of("UTC")),
            ledgerEntry(
                id = "food-june",
                amountMinor = 4200,
                categoryId = "food",
                transactionTimeEpochMillis = JUNE_2026
            ).toLedgerUiEntry(ZoneId.of("UTC"))
        )

        val summary = monthlySummary(entries, "2026-07")
        val categoryTotals = categoryExpenseTotals(entries, "2026-07")
        val trend = categoryTrend(entries, "餐饮", "2026-07")

        assertEquals(4190, summary.expenseMinor)
        assertEquals(10_000, summary.incomeMinor)
        assertEquals(5810, summary.netMinor)
        assertEquals(listOf("餐饮", "交通"), categoryTotals.map { it.category })
        assertEquals(3590, categoryTotals.first().amountMinor)
        assertEquals(4200, trend.first { it.monthKey == "2026-06" }.amountMinor)
        assertEquals(3590, trend.first { it.monthKey == "2026-07" }.amountMinor)
    }

    private fun ledgerEntry(
        id: String,
        transactionKind: TransactionKind = TransactionKind.EXPENSE,
        amountMinor: Long = 3590,
        categoryId: String? = "food",
        transactionTimeEpochMillis: Long = JULY_2026
    ): LedgerEntryEntity = LedgerEntryEntity(
        id = id,
        source = PaymentSource.ALIPAY,
        originPendingEntryId = "pending-$id",
        transactionKind = transactionKind,
        amountMinor = amountMinor,
        currency = "CNY",
        merchantTitle = "午餐",
        transactionTimeEpochMillis = transactionTimeEpochMillis,
        categoryId = categoryId,
        fundingAccountId = null,
        note = "客户会议",
        confirmedAtEpochMillis = transactionTimeEpochMillis + 60_000
    )

    private companion object {
        const val JULY_2026 = 1_783_513_200_000L
        const val JUNE_2026 = 1_780_921_200_000L
    }
}
