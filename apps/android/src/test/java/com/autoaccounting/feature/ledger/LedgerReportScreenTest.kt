package com.autoaccounting.feature.ledger

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LedgerReportScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reportsShowOverviewDonutRankingAndSevenMonthCashFlow() {
        composeRule.setContent {
            ReportsScreen(entries = sampleEntries())
        }

        composeRule.onNodeWithText("报表").assertIsDisplayed()
        composeRule.onNodeWithText("本月支出 ¥41.90").assertIsDisplayed()
        composeRule.onNodeWithText("本月收入 ¥12.90").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "本月支出分类环形图，总支出 ¥41.90，餐饮 85.7%，交通 14.3%"
        ).assertIsDisplayed()
        composeRule.onNodeWithText("85.7%").assertIsDisplayed()
        composeRule.onNodeWithText("14.3%").assertIsDisplayed()
        composeRule.onNodeWithText("分类排行").assertExists()
        composeRule.onNodeWithText("餐饮 ¥35.90").assertHasNoClickAction()
        composeRule.onAllNodesWithContentDescription("餐饮").assertCountEquals(0)
        composeRule.onNodeWithText("7 个月收支").assertIsDisplayed()
        composeRule.onAllNodesWithText("2026-04").assertCountEquals(1)
        composeRule.onAllNodesWithText("2026-10").assertCountEquals(1)
        composeRule.onNodeWithContentDescription(
            "2026-10，支出 ¥0.00，收入 ¥0.00"
        ).assertExists()
        composeRule.onNodeWithContentDescription(
            "基准月份 2026-07，支出 ¥41.90，收入 ¥12.90"
        ).assertExists()
        composeRule.onNodeWithText("近 6 个月趋势").assertDoesNotExist()
        composeRule.onNodeWithText("图表占位").assertDoesNotExist()
        composeRule.onNodeWithText("当前分类：餐饮").assertDoesNotExist()
    }

    @Test
    fun reportsGroupCategoriesAfterTheTopFourIntoOther() {
        val entries = listOf(
            reportEntry("food", "餐饮", 500),
            reportEntry("ride", "交通", 400),
            reportEntry("home", "住房", 300),
            reportEntry("phone", "通讯", 200),
            reportEntry("shop", "购物", 100),
            reportEntry("health", "医疗", 100)
        )

        composeRule.setContent {
            ReportsScreen(entries = entries)
        }

        composeRule.onNodeWithContentDescription(
            "本月支出分类环形图，总支出 ¥16.00，" +
                "餐饮 31.3%，交通 25.0%，住房 18.7%，通讯 12.5%，其他 12.5%"
        ).assertIsDisplayed()
        composeRule.onNodeWithText("其他").assertIsDisplayed()
        composeRule.onAllNodesWithText("12.5%").assertCountEquals(2)
    }

    @Test
    fun expenseOnlyReportKeepsDonutAndShowsZeroIncome() {
        val expense = reportEntry(
            id = "meal",
            category = "餐饮",
            amountMinor = 3_590
        )

        composeRule.setContent {
            ReportsScreen(entries = listOf(expense))
        }

        composeRule.onNodeWithText("本月支出 ¥35.90").assertIsDisplayed()
        composeRule.onNodeWithText("本月收入 ¥0.00").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "本月支出分类环形图，总支出 ¥35.90，餐饮 100.0%"
        ).assertIsDisplayed()
        composeRule.onAllNodesWithText("本月暂无支出分类").assertCountEquals(0)
    }

    @Test
    fun incomeOnlyReportKeepsCashFlowAndShowsExpenseEmptyStates() {
        val income = reportEntry(
            id = "salary",
            category = "工资",
            amountMinor = 12_900,
            flowType = LedgerFlowType.INCOME
        )

        composeRule.setContent {
            ReportsScreen(entries = listOf(income))
        }

        composeRule.onNodeWithText("本月支出 ¥0.00").assertIsDisplayed()
        composeRule.onNodeWithText("本月收入 ¥129.00").assertIsDisplayed()
        composeRule.onAllNodesWithText("本月暂无支出分类").assertCountEquals(2)
        composeRule.onNodeWithText("7 个月收支").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "基准月份 2026-07，支出 ¥0.00，收入 ¥129.00"
        ).assertExists()
    }

    @Test
    fun emptyReportShowsLedgerLevelEmptyState() {
        composeRule.setContent {
            ReportsScreen(entries = emptyList())
        }

        composeRule.onNodeWithText("当前账本暂无可分析的收支").assertIsDisplayed()
        composeRule.onNodeWithText("本月支出 ¥0.00").assertDoesNotExist()
        composeRule.onNodeWithText("7 个月收支").assertDoesNotExist()
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

    private fun reportEntry(
        id: String,
        category: String,
        amountMinor: Long,
        flowType: LedgerFlowType = LedgerFlowType.EXPENSE
    ): LedgerUiEntry = LedgerUiEntry(
        id = id,
        title = id,
        amountMinor = amountMinor,
        monthKey = "2026-07",
        transactionTimeText = "2026-07-08 12:20",
        category = category,
        sourceLabel = "未指定",
        kindLabel = if (flowType == LedgerFlowType.EXPENSE) "支出" else "收入",
        flowType = flowType
    )
}
