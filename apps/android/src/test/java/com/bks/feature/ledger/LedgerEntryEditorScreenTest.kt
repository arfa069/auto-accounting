package com.bks.feature.ledger

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import com.bks.data.local.EntryOrigin
import com.bks.data.local.FlowDirection
import com.bks.data.local.LedgerEntryInput
import com.bks.data.local.PaymentSource
import com.bks.data.local.TransactionKind
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LedgerEntryEditorScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

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
        composeRule.onNodeWithText("新建资金账户").assertDoesNotExist()
        composeRule.onNodeWithText("不计收支").assertDoesNotExist()
        composeRule.onNodeWithTag("manual-direction-OUTFLOW").assertIsDisplayed()
        composeRule.onNodeWithTag("manual-direction-INFLOW").assertIsDisplayed()
        composeRule.onNodeWithText("交易信息").assertIsDisplayed()
        composeRule.onNodeWithText("商户（可选）").assertIsDisplayed()
        composeRule.onNodeWithText("商户/标题（可选）").assertDoesNotExist()
        composeRule.onNodeWithTag("manual-entry-actions").assertIsDisplayed()
        composeRule.onNodeWithTag("manual-entry-amount").performTextInput("12.34")
        composeRule.onNodeWithTag("manual-entry-merchant").performScrollTo().performTextInput("早餐")
        composeRule.onNodeWithText("账户与备注").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("保存账目").performClick()

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

        composeRule.onNodeWithTag("manual-entry-amount").performTextInput("0")
        composeRule.onNodeWithText("保存账目").performClick()

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

        composeRule.onNodeWithTag("manual-entry-merchant").performTextInput("未保存")
        composeRule.onNodeWithText("取消").performClick()

        composeRule.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        composeRule.onNodeWithText("继续编辑").performClick()
        composeRule.onNodeWithText("未保存").assertIsDisplayed()
    }

    @Test
    fun manualEntryDraftIsRestoredAfterRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            ManualLedgerEntryScreen(
                categories = emptyList(),
                fundingAccounts = emptyList(),
                onExit = {},
                onCreateEntry = {}
            )
        }

        composeRule.onNodeWithTag("manual-entry-amount").performTextInput("12.34")
        composeRule.onNodeWithTag("manual-entry-merchant")
            .performScrollTo()
            .performTextInput("未保存早餐")
        composeRule.onNodeWithTag("manual-entry-back").performClick()
        composeRule.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("放弃未保存的修改？").assertDoesNotExist()
        composeRule.onNodeWithTag("manual-entry-amount").assertTextContains("12.34")
        composeRule.onNodeWithTag("manual-entry-merchant").assertTextContains("未保存早餐")
        composeRule.onNodeWithTag("manual-entry-back").performClick()
        composeRule.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
    }

    @Test
    fun ledgerEditDraftIsRestoredAfterRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            LedgerScreen(entries = listOf(sampleEntries().first()))
        }

        composeRule.onNodeWithText("午餐").performClick()
        composeRule.onNodeWithTag("manual-entry-merchant").performTextClearance()
        composeRule.onNodeWithTag("manual-entry-merchant").performTextInput("未保存工作餐")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("编辑账目").assertIsDisplayed()
        composeRule.onNodeWithTag("manual-entry-merchant").assertTextContains("未保存工作餐")
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
        composeRule.onNodeWithText("编辑账目").assertIsDisplayed()
        composeRule.onNodeWithTag("edit-entry-delete").performScrollTo().performClick()
        composeRule.onNodeWithText("移入最近删除").performClick()
        composeRule.onNodeWithText("撤销").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { restoredId.get() != null }
        assertEquals("food", deletedId.get())
        assertEquals("food", restoredId.get())
    }

    @Test
    fun capturedEntryOpensEditorAndCanBeUpdated() {
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
        composeRule.onNodeWithText("编辑账目").assertIsDisplayed()
        composeRule.onNodeWithText("交易信息").assertIsDisplayed()
        composeRule.onNodeWithTag("manual-direction-OUTFLOW").assertIsDisplayed()
        composeRule.onNodeWithTag("manual-direction-INFLOW").assertIsDisplayed()
        composeRule.onNodeWithTag("manual-direction-NEUTRAL").assertDoesNotExist()
        composeRule.onNodeWithText("账户与备注").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("manual-entry-actions").assertIsDisplayed()
        composeRule.onNodeWithText("新建资金账户").assertDoesNotExist()
        composeRule.onNodeWithTag("manual-entry-amount").performTextClearance()
        composeRule.onNodeWithTag("manual-entry-amount").performTextInput("20.00")
        composeRule.onNodeWithText("保存修改").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { updatedInput.get() != null }
        assertEquals(2_000L, updatedInput.get()?.amountMinor)
    }

    @Test
    fun longMerchantTitleCanBeEditedFromTheBeginning() {
        val updatedInput = AtomicReference<LedgerEntryInput?>()
        composeRule.setContent {
            LedgerScreen(
                entries = listOf(sampleEntries().first()),
                onUpdateEntry = { _, input -> updatedInput.set(input) }
            )
        }

        composeRule.onNodeWithText("午餐").performClick()
        val merchantField = composeRule.onNodeWithTag("manual-entry-merchant")
        composeRule.onNodeWithText("商户（可选）").assertIsDisplayed()
        composeRule.onNodeWithText("商户/标题（可选）").assertDoesNotExist()
        val shortFieldHeight = merchantField.fetchSemanticsNode().boundsInRoot.height
        merchantField.performTextClearance()
        merchantField.performTextInput("这是一个超过输入框宽度的商户名称")
        composeRule.waitForIdle()
        val wrappedFieldHeight = merchantField.fetchSemanticsNode().boundsInRoot.height
        assertTrue(wrappedFieldHeight > shortFieldHeight)
        merchantField.performTextInputSelection(TextRange.Zero)
        merchantField.performTextInput("新")
        composeRule.onNodeWithText("保存修改").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { updatedInput.get() != null }
        assertEquals(
            "新这是一个超过输入框宽度的商户名称",
            updatedInput.get()?.merchantTitle
        )
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
