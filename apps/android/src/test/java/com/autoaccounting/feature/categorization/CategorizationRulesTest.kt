package com.autoaccounting.feature.categorization

import com.autoaccounting.feature.review.ReviewQueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategorizationRulesTest {
    @Test
    fun merchantTitleSourceAndKindCanMatchRule() {
        val rules = listOf(
            sampleRule(
                id = "coffee",
                merchantContains = "星巴克",
                titleContains = "拿铁",
                sourceLabel = "微信",
                transactionKind = "支出",
                category = "餐饮"
            )
        )

        val suggestion = suggestCategory(
            rules = rules,
            transaction = sampleTransaction(
                merchantTitle = "星巴克拿铁",
                sourceLabel = "微信",
                transactionKind = "支出"
            )
        )

        assertEquals("餐饮", suggestion?.category)
        assertEquals("coffee", suggestion?.ruleId)
    }

    @Test
    fun higherPriorityRuleWinsConflict() {
        val rules = listOf(
            sampleRule(id = "general-food", merchantContains = "星巴克", category = "餐饮", priority = 10),
            sampleRule(id = "work", merchantContains = "星巴克", category = "工作餐", priority = 100)
        )

        val suggestion = suggestCategory(rules, sampleTransaction(merchantTitle = "星巴克"))

        assertEquals("工作餐", suggestion?.category)
        assertEquals("work", suggestion?.ruleId)
    }

    @Test
    fun disabledRuleIsIgnoredAndTieUsesNewestRule() {
        val rules = listOf(
            sampleRule(id = "disabled", merchantContains = "地铁", category = "交通", priority = 100, enabled = false),
            sampleRule(id = "old", merchantContains = "地铁", category = "交通", priority = 10, updatedAtEpochMillis = 1),
            sampleRule(id = "new", merchantContains = "地铁", category = "通勤", priority = 10, updatedAtEpochMillis = 2)
        )

        val suggestion = suggestCategory(rules, sampleTransaction(merchantTitle = "地铁出行"))

        assertEquals("通勤", suggestion?.category)
        assertEquals("new", suggestion?.ruleId)
    }

    @Test
    fun unmatchedRuleReturnsNull() {
        val rules = listOf(sampleRule(merchantContains = "便利店", category = "购物"))

        assertNull(suggestCategory(rules, sampleTransaction(merchantTitle = "地铁")))
    }

    @Test
    fun reviewQueueEntryCanApplyMatchingSuggestion() {
        val rules = listOf(
            sampleRule(
                merchantContains = "coffee",
                sourceLabel = "wechat",
                transactionKind = "expense",
                category = "food"
            )
        )
        val entry = ReviewQueueEntry(
            id = "pending-coffee",
            title = "Coffee Shop",
            category = "",
            sourceLabel = "wechat",
            kindLabel = "expense"
        )

        val suggestedEntry = entry.applyCategorizationSuggestion(rules)

        assertEquals("food", suggestedEntry.category)
    }

    private fun sampleRule(
        id: String = "rule-1",
        merchantContains: String = "",
        titleContains: String = "",
        sourceLabel: String = "",
        transactionKind: String = "",
        category: String,
        priority: Int = 0,
        enabled: Boolean = true,
        updatedAtEpochMillis: Long = 0
    ): CategorizationRule = CategorizationRule(
        id = id,
        merchantContains = merchantContains,
        titleContains = titleContains,
        sourceLabel = sourceLabel,
        transactionKind = transactionKind,
        category = category,
        priority = priority,
        enabled = enabled,
        updatedAtEpochMillis = updatedAtEpochMillis
    )

    private fun sampleTransaction(
        merchantTitle: String,
        sourceLabel: String = "支付宝",
        transactionKind: String = "支出"
    ): CategorizationTransaction = CategorizationTransaction(
        merchantTitle = merchantTitle,
        sourceLabel = sourceLabel,
        transactionKind = transactionKind
    )
}
