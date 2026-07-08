package com.autoaccounting.feature.ledger

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LedgerReportsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ledgerShowsMonthlySummarySearchFilterAndEntries() {
        composeRule.setContent {
            LedgerScreen(entries = sampleEntries())
        }

        composeRule.onNodeWithText("本地账本").assertIsDisplayed()
        composeRule.onNodeWithText("本月支出 ¥41.90").assertIsDisplayed()
        composeRule.onNodeWithText("本月收入 ¥12.90").assertIsDisplayed()
        composeRule.onNodeWithText("净额 -¥29.00").assertIsDisplayed()
        composeRule.onNodeWithText("午餐").assertIsDisplayed()

        composeRule.onNodeWithText("搜索商户或备注").performTextInput("地铁")
        composeRule.onNodeWithText("地铁出行").assertIsDisplayed()
        composeRule.onNodeWithText("筛选").performClick()
        composeRule.onNodeWithText("来源").assertIsDisplayed()
        composeRule.onNodeWithText("分类").assertIsDisplayed()
    }

    @Test
    fun reportsShowOverviewCategoryRankingAndTrend() {
        composeRule.setContent {
            ReportsScreen(entries = sampleEntries())
        }

        composeRule.onNodeWithText("报表").assertIsDisplayed()
        composeRule.onNodeWithText("本月支出 ¥41.90").assertIsDisplayed()
        composeRule.onNodeWithText("本月收入 ¥12.90").assertIsDisplayed()
        composeRule.onNodeWithText("分类排行").assertIsDisplayed()
        composeRule.onNodeWithText("餐饮 ¥35.90").assertIsDisplayed()
        composeRule.onNodeWithText("近 6 个月趋势").assertIsDisplayed()
        composeRule.onNodeWithText("图表占位").assertIsDisplayed()
    }

    private fun sampleEntries(): List<LedgerUiEntry> = listOf(
        LedgerUiEntry(
            id = "food",
            title = "午餐",
            amountMinor = 3590,
            monthKey = "2026-07",
            transactionTimeText = "2026-07-08 12:20",
            category = "餐饮",
            sourceLabel = "微信",
            kindLabel = "支出",
            flowType = LedgerFlowType.EXPENSE,
            note = "客户会议"
        ),
        LedgerUiEntry(
            id = "ride",
            title = "地铁出行",
            amountMinor = 600,
            monthKey = "2026-07",
            transactionTimeText = "2026-07-08 08:10",
            category = "交通",
            sourceLabel = "支付宝",
            kindLabel = "支出",
            flowType = LedgerFlowType.EXPENSE
        ),
        LedgerUiEntry(
            id = "refund",
            title = "退款到账",
            amountMinor = 1290,
            monthKey = "2026-07",
            transactionTimeText = "2026-07-07 21:10",
            category = "退款",
            sourceLabel = "微信",
            kindLabel = "退款",
            flowType = LedgerFlowType.INCOME
        )
    )
}
