package com.autoaccounting.feature.ledger

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.autoaccounting.data.local.EntryOrigin
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.LedgerEntryInput
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.TransactionKind
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun manualEntryFormSavesValidatedInput() {
        val savedInput = AtomicReference<LedgerEntryInput?>()
        composeRule.setContent {
            ManualLedgerEntryScreen(
                categories = emptyList(),
                fundingAccounts = emptyList(),
                onExit = {},
                onCreateEntry = { savedInput.set(it) }
            )
        }

        composeRule.onNodeWithText("新增一笔").assertIsDisplayed()
        composeRule.onNodeWithText("金额（CNY）").performTextInput("12.34")
        composeRule.onNodeWithText("商户/标题（可选）").performTextInput("早餐")
        composeRule.onNodeWithText("保存").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { savedInput.get() != null }
        assertNotNull(savedInput.get())
        assertEquals(1_234L, savedInput.get()?.amountMinor)
        assertEquals("早餐", savedInput.get()?.merchantTitle)
        assertEquals(FlowDirection.OUTFLOW, savedInput.get()?.flowDirection)
    }

    @Test
    fun invalidAmountDoesNotSave() {
        val savedInput = AtomicReference<LedgerEntryInput?>()
        composeRule.setContent {
            ManualLedgerEntryScreen(
                categories = emptyList(),
                fundingAccounts = emptyList(),
                onExit = {},
                onCreateEntry = { savedInput.set(it) }
            )
        }

        composeRule.onNodeWithText("金额（CNY）").performTextInput("0")
        composeRule.onNodeWithText("保存").performScrollTo().performClick()

        composeRule.onNodeWithText("金额必须大于 0").assertIsDisplayed()
        assertNull(savedInput.get())
    }

    @Test
    fun dirtyFormRequiresDiscardConfirmation() {
        composeRule.setContent {
            ManualLedgerEntryScreen(
                categories = emptyList(),
                fundingAccounts = emptyList(),
                onExit = {},
                onCreateEntry = {}
            )
        }

        composeRule.onNodeWithText("商户/标题（可选）").performTextInput("未保存")
        composeRule.onNodeWithText("取消").performScrollTo().performClick()

        composeRule.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        composeRule.onNodeWithText("继续编辑").performClick()
        composeRule.onNodeWithText("未保存").assertIsDisplayed()
    }

    @Test
    fun deletedEntryCanBeUndoneFromSnackbar() {
        val deletedId = AtomicReference<String?>()
        val restoredId = AtomicReference<String?>()
        composeRule.setContent {
            LedgerScreen(
                entries = listOf(sampleEntries().first()),
                onDeleteEntry = { deletedId.set(it) },
                onRestoreEntry = { restoredId.set(it) }
            )
        }

        composeRule.onNodeWithText("午餐").performClick()
        composeRule.onNodeWithText("删除").performScrollTo().performClick()
        composeRule.onNodeWithText("移入最近删除").performClick()
        composeRule.onNodeWithText("撤销").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { restoredId.get() != null }
        assertEquals("food", deletedId.get())
        assertEquals("food", restoredId.get())
    }

    @Test
    fun capturedEntryCanBeEditedWithoutHidingItsProvenance() {
        val updatedInput = AtomicReference<LedgerEntryInput?>()
        val captured = sampleEntries().first().copy(
            paymentSource = PaymentSource.WECHAT,
            originalCaptureSource = PaymentSource.WECHAT,
            entryOrigin = EntryOrigin.NOTIFICATION,
            originPendingEntryId = "pending-food",
            flowDirection = FlowDirection.OUTFLOW,
            transactionKind = TransactionKind.EXPENSE,
            transactionTimeEpochMillis = 1_783_513_200_000,
            categoryId = "food",
            evidenceSummary = "微信支付凭证",
            confirmedAtEpochMillis = 1_783_513_260_000,
            updatedAtEpochMillis = 1_783_513_260_000
        )
        composeRule.setContent {
            LedgerScreen(
                entries = listOf(captured),
                onUpdateEntry = { _, input -> updatedInput.set(input) }
            )
        }

        composeRule.onNodeWithText("午餐").performClick()
        composeRule.onNodeWithText("原始采集信息").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("微信支付凭证").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("编辑").performScrollTo().performClick()
        composeRule.onNodeWithText("金额（CNY）").performTextClearance()
        composeRule.onNodeWithText("金额（CNY）").performTextInput("20.00")
        composeRule.onNodeWithText("保存").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { updatedInput.get() != null }
        assertEquals(2_000L, updatedInput.get()?.amountMinor)
    }

    @Test
    fun recentlyDeletedEntryCanBeReachedAndRestored() {
        val restoredId = AtomicReference<String?>()
        val deleted = sampleEntries().first().copy(
            deletedAtEpochMillis = System.currentTimeMillis() - 1_000
        )
        composeRule.setContent {
            LedgerScreen(
                entries = emptyList(),
                deletedEntries = listOf(deleted),
                onRestoreEntry = { restoredId.set(it) }
            )
        }

        composeRule.onNodeWithText("更多").performClick()
        composeRule.onNodeWithText("最近删除").performClick()
        composeRule.onNodeWithText("剩余 30 天").assertIsDisplayed()
        composeRule.onNodeWithText("永久删除").assertIsDisplayed()
        composeRule.onNodeWithText("恢复").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { restoredId.get() != null }
        assertEquals("food", restoredId.get())
    }

    @Test
    fun permanentDeleteRequiresConfirmation() {
        val permanentlyDeletedId = AtomicReference<String?>()
        val deleted = sampleEntries().first().copy(
            deletedAtEpochMillis = System.currentTimeMillis() - 1_000
        )
        composeRule.setContent {
            LedgerScreen(
                entries = emptyList(),
                deletedEntries = listOf(deleted),
                onPermanentlyDeleteEntry = { permanentlyDeletedId.set(it) }
            )
        }

        composeRule.onNodeWithText("更多").performClick()
        composeRule.onNodeWithText("最近删除").performClick()
        composeRule.onNodeWithText("永久删除").performClick()
        assertNull(permanentlyDeletedId.get())
        composeRule.onAllNodesWithText("永久删除")[1].performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { permanentlyDeletedId.get() != null }
        assertEquals("food", permanentlyDeletedId.get())
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
