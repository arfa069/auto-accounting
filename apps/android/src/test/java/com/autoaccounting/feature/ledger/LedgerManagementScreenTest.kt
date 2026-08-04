package com.autoaccounting.feature.ledger

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.FundingAccountSourceScope
import com.autoaccounting.data.local.PaymentSource
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LedgerManagementScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

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
        composeRule.onNodeWithText("已删除账本「旅行账本」").assertIsDisplayed()
    }

    @Test
    fun ledgerDeleteFailureShowsSnackbarAfterDialogCloses() {
        composeRule.setContent {
            LedgerScreen(
                entries = emptyList(),
                ledgerBooks = listOf(
                    LedgerBookUiModel("default", "默认账本", isActive = true),
                    LedgerBookUiModel("travel", "旅行账本")
                ),
                onDeleteLedger = { throw IllegalStateException("账本删除失败") }
            )
        }

        openLedgerManagement()
        composeRule.onNodeWithTag(LedgerTestTags.deleteLedger("travel"))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(LedgerTestTags.CONFIRM_DELETE_LEDGER).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("账本删除失败").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("账本删除失败").assertIsDisplayed()
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
        composeRule.onNodeWithText("已删除资金账户「零钱」").assertIsDisplayed()
    }

    @Test
    fun fundingAccountDeleteFailureShowsSnackbarAfterDialogCloses() {
        val account = fundingAccount(id = 7, label = "零钱", source = PaymentSource.WECHAT)
        composeRule.setContent {
            LedgerScreen(
                entries = emptyList(),
                fundingAccounts = listOf(account),
                onDeleteFundingAccount = { throw IllegalStateException("资金账户删除失败") }
            )
        }

        openFundingAccountManagement()
        composeRule.onNodeWithTag(LedgerTestTags.deleteFundingAccount(7)).performClick()
        composeRule.onNodeWithTag(LedgerTestTags.CONFIRM_DELETE_FUNDING_ACCOUNT).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("资金账户删除失败").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("资金账户删除失败").assertIsDisplayed()
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
}
