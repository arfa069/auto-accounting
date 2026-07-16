package com.autoaccounting.feature.ledger

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.autoaccounting.data.local.EntryOrigin
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.FundingAccountSourceScope
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
            LedgerScreen(
                entries = sampleEntries(),
                activeLedgerName = "日常账本"
            )
        }

        composeRule.onNodeWithText("日常账本").assertIsDisplayed()
        composeRule.onNodeWithText("本月支出 ¥41.90").assertIsDisplayed()
        composeRule.onNodeWithText("本月收入 ¥12.90").assertIsDisplayed()
        composeRule.onNodeWithText("净额\n-¥29.00").assertIsDisplayed()
        composeRule.onNodeWithText("午餐").assertIsDisplayed()

        composeRule.onNodeWithText("搜索商户或备注").performTextInput("地铁")
        composeRule.onNodeWithText("地铁出行").assertIsDisplayed()
        composeRule.onNodeWithText("筛选").performClick()
        composeRule.onNodeWithText("来源").assertIsDisplayed()
        composeRule.onNodeWithText("分类").assertIsDisplayed()
    }

    @Test
    fun ledgerKeepsHeaderVisibleWhenEntryListScrolls() {
        val entries = List(20) { index ->
            sampleEntries().first().copy(
                id = "entry-$index",
                title = "账目 $index",
                transactionTimeEpochMillis = index.toLong()
            )
        }
        composeRule.setContent {
            LedgerScreen(
                entries = entries,
                activeLedgerName = "日常账本"
            )
        }

        composeRule.onNodeWithTag(LedgerTestTags.ENTRY_LIST).performScrollToIndex(19)
        composeRule.onNodeWithText("账目 0").assertIsDisplayed()
        composeRule.onNodeWithText("日常账本").assertIsDisplayed()
        composeRule.onNodeWithText("本月支出 ¥718.00").assertIsDisplayed()
        composeRule.onNodeWithText("2026-07 明细").assertIsDisplayed()
    }

    @Test
    fun moreMenuProvidesLedgerFundingAccountAndRecentlyDeletedManagement() {
        composeRule.setContent {
            LedgerScreen(entries = emptyList())
        }

        composeRule.onNodeWithTag(LedgerTestTags.MORE_MENU).performClick()

        composeRule.onNodeWithTag(LedgerTestTags.MANAGE_LEDGERS).assertIsDisplayed()
        composeRule.onNodeWithTag(LedgerTestTags.MANAGE_FUNDING_ACCOUNTS).assertIsDisplayed()
        composeRule.onNodeWithTag(LedgerTestTags.RECENTLY_DELETED).assertIsDisplayed()
    }

    @Test
    fun ledgerManagementCreatesSwitchesAndBlocksUnsafeDelete() {
        val createdName = AtomicReference<String?>()
        val selectedId = AtomicReference<String?>()
        val books = listOf(
            LedgerBookUiModel(
                id = "default",
                name = "默认账本",
                activeEntryCount = 1,
                deletedEntryCount = 2,
                isActive = true
            ),
            LedgerBookUiModel(
                id = "travel",
                name = "旅行账本"
            )
        )
        composeRule.setContent {
            LedgerScreen(
                entries = emptyList(),
                ledgerBooks = books,
                activeLedgerName = "默认账本",
                onCreateLedger = { createdName.set(it) },
                onSelectLedger = { selectedId.set(it) }
            )
        }

        openLedgerManagement()
        composeRule.onNodeWithText("当前账本").assertIsDisplayed()
        composeRule.onNodeWithText("当前 1 笔，最近删除 2 笔").assertIsDisplayed()

        composeRule.onNodeWithTag(LedgerTestTags.ADD_LEDGER).performClick()
        composeRule.onNodeWithTag(LedgerTestTags.LEDGER_NAME).performTextInput(" 工作 ")
        composeRule.onNodeWithTag(LedgerTestTags.CONFIRM_ADD_LEDGER).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { createdName.get() != null }
        assertEquals("工作", createdName.get())

        openLedgerManagement()
        composeRule.onNodeWithTag(LedgerTestTags.selectLedger("travel"))
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { selectedId.get() != null }
        assertEquals("travel", selectedId.get())

        openLedgerManagement()
        composeRule.onNodeWithTag(LedgerTestTags.deleteLedger("default")).performClick()
        composeRule.onNodeWithText("无法删除账本").assertIsDisplayed()
        composeRule.onNodeWithText(
            "该账本仍有 1 笔当前账目和 2 笔最近删除账目，清空后才能删除。"
        ).assertIsDisplayed()
    }

    @Test
    fun emptyLedgerDeleteRequiresConfirmationAndUsesResultCallback() {
        val deletedId = AtomicReference<String?>()
        composeRule.setContent {
            LedgerScreen(
                entries = emptyList(),
                ledgerBooks = listOf(
                    LedgerBookUiModel("default", "默认账本", isActive = true),
                    LedgerBookUiModel("travel", "旅行账本")
                ),
                onDeleteLedger = {
                    deletedId.set(it)
                    LedgerBookDeleteResult.Deleted
                }
            )
        }

        openLedgerManagement()
        composeRule.onNodeWithTag(LedgerTestTags.deleteLedger("travel"))
            .performScrollTo()
            .performClick()
        assertNull(deletedId.get())
        composeRule.onNodeWithTag(LedgerTestTags.CONFIRM_DELETE_LEDGER).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { deletedId.get() != null }
        assertEquals("travel", deletedId.get())
    }

    @Test
    fun lastLedgerCannotBeDeleted() {
        val deletedId = AtomicReference<String?>()
        composeRule.setContent {
            LedgerScreen(
                entries = emptyList(),
                ledgerBooks = listOf(
                    LedgerBookUiModel("default", "默认账本", isActive = true)
                ),
                onDeleteLedger = {
                    deletedId.set(it)
                    LedgerBookDeleteResult.Deleted
                }
            )
        }

        openLedgerManagement()
        composeRule.onNodeWithTag(LedgerTestTags.deleteLedger("default")).performClick()

        composeRule.onNodeWithText("无法删除账本").assertIsDisplayed()
        composeRule.onNodeWithText("至少需要保留一个账本。").assertIsDisplayed()
        assertNull(deletedId.get())
    }

    @Test
    fun fundingAccountManagementCreatesAndEditsAccounts() {
        val created = AtomicReference<Pair<String, PaymentSource?>?>()
        val updated = AtomicReference<Triple<Long, String, PaymentSource?>?>()
        val deletedId = AtomicReference<Long?>()
        val account = fundingAccount(id = 7, label = "零钱", source = PaymentSource.WECHAT)
        composeRule.setContent {
            LedgerScreen(
                entries = emptyList(),
                fundingAccounts = listOf(account),
                onCreateFundingAccount = { label, source -> created.set(label to source) },
                onUpdateFundingAccount = { id, label, source ->
                    updated.set(Triple(id, label, source))
                },
                onDeleteFundingAccount = {
                    deletedId.set(it)
                    FundingAccountDeleteResult.Deleted
                }
            )
        }

        openFundingAccountManagement()
        composeRule.onNodeWithTag(LedgerTestTags.ADD_FUNDING_ACCOUNT).performClick()
        composeRule.onNodeWithTag(LedgerTestTags.FUNDING_ACCOUNT_LABEL).performTextInput(" 工资卡 ")
        composeRule.onNodeWithTag(LedgerTestTags.FUNDING_ACCOUNT_SOURCE).performClick()
        composeRule.onNodeWithText("支付宝").performClick()
        composeRule.onNodeWithTag(LedgerTestTags.SAVE_FUNDING_ACCOUNT).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { created.get() != null }
        assertEquals("工资卡", created.get()?.first)
        assertEquals(PaymentSource.ALIPAY, created.get()?.second)

        composeRule.onNodeWithTag(LedgerTestTags.editFundingAccount(7)).performClick()
        composeRule.onNodeWithTag(LedgerTestTags.FUNDING_ACCOUNT_LABEL).performTextClearance()
        composeRule.onNodeWithTag(LedgerTestTags.FUNDING_ACCOUNT_LABEL).performTextInput("微信零钱")
        composeRule.onNodeWithTag(LedgerTestTags.SAVE_FUNDING_ACCOUNT).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { updated.get() != null }
        assertEquals(7L, updated.get()?.first)
        assertEquals("微信零钱", updated.get()?.second)
        assertEquals(PaymentSource.WECHAT, updated.get()?.third)

        composeRule.onNodeWithTag(LedgerTestTags.deleteFundingAccount(7)).performClick()
        assertNull(deletedId.get())
        composeRule.onNodeWithTag(LedgerTestTags.CONFIRM_DELETE_FUNDING_ACCOUNT).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { deletedId.get() != null }
        assertEquals(7L, deletedId.get())
    }

    @Test
    fun fundingAccountDuplicateAndReferencesProvideClearBlockingMessages() {
        val created = AtomicReference<Pair<String, PaymentSource?>?>()
        val account = fundingAccount(id = 7, label = "零钱", source = PaymentSource.WECHAT)
        composeRule.setContent {
            LedgerScreen(
                entries = emptyList(),
                fundingAccounts = listOf(account),
                onCreateFundingAccount = { label, source -> created.set(label to source) },
                onDeleteFundingAccount = {
                    FundingAccountDeleteResult.Referenced(
                        activeLedgerEntryCount = 1,
                        deletedLedgerEntryCount = 2,
                        pendingEntryCount = 3,
                        ignoredEntryCount = 4
                    )
                }
            )
        }

        openFundingAccountManagement()
        composeRule.onNodeWithTag(LedgerTestTags.ADD_FUNDING_ACCOUNT).performClick()
        composeRule.onNodeWithTag(LedgerTestTags.FUNDING_ACCOUNT_LABEL).performTextInput("零钱")
        composeRule.onNodeWithTag(LedgerTestTags.FUNDING_ACCOUNT_SOURCE).performClick()
        composeRule.onNodeWithText("微信").performClick()
        composeRule.onNodeWithTag(LedgerTestTags.SAVE_FUNDING_ACCOUNT).performClick()
        composeRule.onNodeWithText("同一支付来源下已存在同名资金账户").assertIsDisplayed()
        assertNull(created.get())

        composeRule.onNodeWithText("取消").performClick()
        composeRule.onNodeWithTag(LedgerTestTags.deleteFundingAccount(7)).performClick()
        composeRule.onNodeWithTag(LedgerTestTags.CONFIRM_DELETE_FUNDING_ACCOUNT).performClick()
        composeRule.onNodeWithText("无法删除资金账户").assertIsDisplayed()
        composeRule.onNodeWithText(
            "该账户仍被 1 笔当前账目、2 笔最近删除账目、3 条待确认记录和 4 条忽略记录引用。"
        ).assertIsDisplayed()
    }

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
        composeRule.onNodeWithText("分类排行").assertIsDisplayed()
        composeRule.onNodeWithText("餐饮 ¥35.90").assertHasNoClickAction()
        composeRule.onAllNodesWithContentDescription("餐饮").assertCountEquals(0)
        composeRule.onNodeWithText("7 个月收支").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("2026-04").assertCountEquals(1)
        composeRule.onAllNodesWithText("2026-10").assertCountEquals(1)
        composeRule.onNodeWithContentDescription(
            "2026-10，支出 ¥0.00，收入 ¥0.00"
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "基准月份 2026-07，支出 ¥41.90，收入 ¥12.90"
        ).performScrollTo().assertIsDisplayed()
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
        composeRule.onNodeWithText("7 个月收支").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "基准月份 2026-07，支出 ¥0.00，收入 ¥129.00"
        ).performScrollTo().assertIsDisplayed()
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
                showDebugMetadata = true,
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
    fun debugMetadataIsAbsentByDefault() {
        val captured = sampleEntries().first().copy(
            originalCaptureSource = PaymentSource.WECHAT,
            entryOrigin = EntryOrigin.NOTIFICATION,
            originPendingEntryId = "pending-food",
            evidenceSummary = "微信支付凭证",
            confirmedAtEpochMillis = 1_783_513_260_000,
            updatedAtEpochMillis = 1_783_513_260_000
        )
        composeRule.setContent {
            LedgerScreen(entries = listOf(captured))
        }

        composeRule.onNodeWithText("午餐").performClick()
        composeRule.onNodeWithText("录入方式").assertDoesNotExist()
        composeRule.onNodeWithText("创建/首次确认").assertDoesNotExist()
        composeRule.onNodeWithText("最后修改").assertDoesNotExist()
        composeRule.onNodeWithText("原始采集信息").assertDoesNotExist()
        composeRule.onNodeWithText("编辑").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("删除").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun debugMetadataIsVisibleWhenEnabled() {
        val captured = sampleEntries().first().copy(
            originalCaptureSource = PaymentSource.WECHAT,
            entryOrigin = EntryOrigin.NOTIFICATION,
            originPendingEntryId = "pending-food",
            evidenceSummary = "微信支付凭证",
            confirmedAtEpochMillis = 1_783_513_260_000,
            updatedAtEpochMillis = 1_783_513_260_000
        )
        composeRule.setContent {
            LedgerScreen(
                entries = listOf(captured),
                showDebugMetadata = true
            )
        }
        composeRule.onNodeWithText("午餐").performClick()
        composeRule.onNodeWithText("录入方式").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("创建/首次确认").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("最后修改").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("原始采集信息").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("原待确认 ID").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("微信支付凭证").performScrollTo().assertIsDisplayed()
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

    private fun openLedgerManagement() {
        composeRule.onNodeWithTag(LedgerTestTags.MORE_MENU).performClick()
        composeRule.onNodeWithTag(LedgerTestTags.MANAGE_LEDGERS).performClick()
        composeRule.onNodeWithText("账本管理").assertIsDisplayed()
    }

    private fun openFundingAccountManagement() {
        composeRule.onNodeWithTag(LedgerTestTags.MORE_MENU).performClick()
        composeRule.onNodeWithTag(LedgerTestTags.MANAGE_FUNDING_ACCOUNTS).performClick()
        composeRule.onNodeWithText("资金账户").assertIsDisplayed()
    }

    private fun fundingAccount(
        id: Long,
        label: String,
        source: PaymentSource?
    ): FundingAccountEntity = FundingAccountEntity(
        id = id,
        sourceScope = when (source) {
            PaymentSource.WECHAT -> FundingAccountSourceScope.WECHAT
            PaymentSource.ALIPAY -> FundingAccountSourceScope.ALIPAY
            null -> FundingAccountSourceScope.USER
        },
        paymentSource = source,
        label = label,
        createdAtEpochMillis = 1
    )

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
