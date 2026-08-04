package com.autoaccounting.feature.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerListFilterTest {
    private val entries = listOf(
        entry("food", "午餐", 3590, "餐饮", "微信").copy(transactionTimeEpochMillis = 300),
        entry("ride", "地铁出行", 600, "交通", "支付宝").copy(transactionTimeEpochMillis = 100),
        entry("salary", "工资", 12900, "工资", "银行")
            .copy(kindLabel = "收入", flowType = LedgerFlowType.INCOME, transactionTimeEpochMillis = 200),
        entry("old", "历史午餐", 1000, "餐饮", "微信")
            .copy(monthKey = "2026-06", transactionTimeEpochMillis = 50)
    )

    @Test
    fun filterKeepsOnlyEntriesOfTheSelectedMonth() {
        val filtered = entries.filterLedgerEntries(
            monthKey = "2026-06",
            searchText = "",
            sourceFilter = "",
            categoryFilter = "",
            kindFilter = ""
        )

        assertEquals(listOf("old"), filtered.map { it.id })
    }

    @Test
    fun searchMatchesTitleIgnoringCase() {
        val filtered = entries.filterLedgerEntries(
            monthKey = "2026-07",
            searchText = "地铁",
            sourceFilter = "",
            categoryFilter = "",
            kindFilter = ""
        )

        assertEquals(listOf("ride"), filtered.map { it.id })
    }

    @Test
    fun searchMatchesNoteAndCategoryText() {
        val withNote = entries + entry("note-hit", "无标题", 100, "购物", "微信")
            .copy(note = "客户会议", transactionTimeEpochMillis = 150)
        val byNote = withNote.filterLedgerEntries(
            monthKey = "2026-07",
            searchText = "客户",
            sourceFilter = "",
            categoryFilter = "",
            kindFilter = ""
        )
        val byCategory = withNote.filterLedgerEntries(
            monthKey = "2026-07",
            searchText = "购物",
            sourceFilter = "",
            categoryFilter = "",
            kindFilter = ""
        )

        assertEquals(listOf("note-hit"), byNote.map { it.id })
        assertEquals(listOf("note-hit"), byCategory.map { it.id })
    }

    @Test
    fun sourceCategoryAndKindFiltersApplyIndependently() {
        val bySource = entries.filterLedgerEntries(
            monthKey = "2026-07",
            searchText = "",
            sourceFilter = "微信",
            categoryFilter = "",
            kindFilter = ""
        )
        val byCategory = entries.filterLedgerEntries(
            monthKey = "2026-07",
            searchText = "",
            sourceFilter = "",
            categoryFilter = "餐饮",
            kindFilter = ""
        )
        val byKind = entries.filterLedgerEntries(
            monthKey = "2026-07",
            searchText = "",
            sourceFilter = "",
            categoryFilter = "",
            kindFilter = "收入"
        )

        assertEquals(listOf("food"), bySource.map { it.id })
        assertEquals(listOf("food"), byCategory.map { it.id })
        assertEquals(listOf("salary"), byKind.map { it.id })
    }

    @Test
    fun combinedFiltersNarrowToIntersection() {
        val filtered = entries.filterLedgerEntries(
            monthKey = "2026-07",
            searchText = "餐",
            sourceFilter = "微信",
            categoryFilter = "餐饮",
            kindFilter = "支出"
        )

        assertEquals(listOf("food"), filtered.map { it.id })
    }

    @Test
    fun resultsAreSortedByTransactionTimeDescending() {
        val filtered = entries.filterLedgerEntries(
            monthKey = "2026-07",
            searchText = "",
            sourceFilter = "",
            categoryFilter = "",
            kindFilter = ""
        )

        assertEquals(listOf("food", "salary", "ride"), filtered.map { it.id })
    }

    @Test
    fun noMatchReturnsEmptyList() {
        val filtered = entries.filterLedgerEntries(
            monthKey = "2026-07",
            searchText = "不存在的关键词",
            sourceFilter = "",
            categoryFilter = "",
            kindFilter = ""
        )

        assertTrue(filtered.isEmpty())
    }

    private fun entry(
        id: String,
        title: String,
        amountMinor: Long,
        category: String,
        sourceLabel: String
    ): LedgerUiEntry = LedgerUiEntry(
        id = id,
        title = title,
        amountMinor = amountMinor,
        monthKey = "2026-07",
        transactionTimeText = "2026-07-08 12:20",
        category = category,
        sourceLabel = sourceLabel,
        kindLabel = "支出",
        flowType = LedgerFlowType.EXPENSE,
        note = null,
        transactionTimeEpochMillis = 0
    )
}
