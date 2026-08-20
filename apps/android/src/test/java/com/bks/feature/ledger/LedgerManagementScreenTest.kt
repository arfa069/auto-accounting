package com.bks.feature.ledger

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
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
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.bks.data.local.FundingAccountEntity
import com.bks.data.local.FundingAccountSourceScope
import com.bks.data.local.PaymentSource
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        composeRule.onNodeWithTag(LedgerTestTags.editFundingAccount(7)).performScrollTo().performClick()
        composeRule.onNodeWithTag(LedgerTestTags.FUNDING_ACCOUNT_LABEL).performTextClearance()
        composeRule.onNodeWithTag(LedgerTestTags.FUNDING_ACCOUNT_LABEL).performTextInput("微信零钱")
        composeRule.onNodeWithTag(LedgerTestTags.SAVE_FUNDING_ACCOUNT).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { updated.get() != null }
        assertEquals(7L, updated.get()?.first)
        assertEquals("微信零钱", updated.get()?.second)
        assertEquals(PaymentSource.WECHAT, updated.get()?.third)

        composeRule.onNodeWithTag(LedgerTestTags.deleteFundingAccount(7)).performScrollTo().performClick()
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
        composeRule.onNodeWithTag(LedgerTestTags.deleteFundingAccount(7)).performScrollTo().performClick()
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
        composeRule.onNodeWithTag(LedgerTestTags.deleteFundingAccount(7)).performScrollTo().performClick()
        composeRule.onNodeWithTag(LedgerTestTags.CONFIRM_DELETE_FUNDING_ACCOUNT).performClick()
        composeRule.onNodeWithText("无法删除资金账户").assertIsDisplayed()
        composeRule.onNodeWithText(
            "该账户仍被 1 笔当前账目、2 笔最近删除账目、3 条待确认记录和 4 条忽略记录引用。"
        ).assertIsDisplayed()
    }

    @Test
    fun ledgerCreateDraftIsRestoredAfterRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            LedgerScreen(entries = emptyList())
        }

        openLedgerManagement()
        composeRule.onNodeWithTag(LedgerTestTags.ADD_LEDGER).performClick()
        composeRule.onNodeWithTag(LedgerTestTags.LEDGER_NAME).performTextInput("工作账本")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(LedgerTestTags.LEDGER_NAME).assertTextContains("工作账本")
    }

    @Test
    fun fundingAccountEditDraftIsRestoredAfterRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            LedgerScreen(
                entries = emptyList(),
                fundingAccounts = listOf(
                    fundingAccount(id = 7, label = "零钱", source = PaymentSource.WECHAT)
                )
            )
        }

        openFundingAccountManagement()
        composeRule.onNodeWithTag(LedgerTestTags.editFundingAccount(7)).performScrollTo().performClick()
        composeRule.onNodeWithTag(LedgerTestTags.FUNDING_ACCOUNT_LABEL).performTextClearance()
        composeRule.onNodeWithTag(LedgerTestTags.FUNDING_ACCOUNT_LABEL).performTextInput("微信零钱")
        composeRule.onNodeWithTag(LedgerTestTags.FUNDING_ACCOUNT_SOURCE).performClick()
        composeRule.onNodeWithText("支付宝").performClick()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("编辑资金账户").assertIsDisplayed()
        composeRule.onNodeWithTag(LedgerTestTags.FUNDING_ACCOUNT_LABEL)
            .assertTextContains("微信零钱")
        composeRule.onNodeWithText("支付来源：支付宝").assertIsDisplayed()
    }

    @Test
    fun fundingAccountLayoutAdaptsAndDefaultCanBeCleared() {
        var forcedSize by mutableStateOf(DpSize(400.dp, 1_400.dp))
        var fontScale by mutableFloatStateOf(1f)
        val selectedDefaultId = AtomicReference<Long?>(Long.MIN_VALUE)
        val accounts = listOf(
            fundingAccount(id = 7, label = "零钱", source = PaymentSource.WECHAT),
            fundingAccount(id = 8, label = "工资卡", source = PaymentSource.ALIPAY)
        )
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(forcedSize) then
                    DeviceConfigurationOverride.FontScale(fontScale)
            ) {
                FundingAccountManagementContent(
                    fundingAccounts = accounts,
                    defaultFundingAccountSyncId = "funding-7",
                    snackbarHostState = remember { SnackbarHostState() },
                    actions = FundingAccountManagementActions(
                        onBack = {},
                        onCreateFundingAccount = { _, _ -> },
                        onUpdateFundingAccount = { _, _, _ -> },
                        onSetDefaultFundingAccount = { selectedDefaultId.set(it) },
                        onDeleteFundingAccount = { FundingAccountDeleteResult.Deleted }
                    )
                )
            }
        }

        listOf(
            Triple(DpSize(400.dp, 1_400.dp), 1f, false),
            Triple(DpSize(610.dp, 1_400.dp), 1f, false),
            Triple(DpSize(900.dp, 1_400.dp), 1f, true),
            Triple(DpSize(400.dp, 1_400.dp), 1.5f, false)
        ).forEach { (size, scale, sideBySide) ->
            composeRule.runOnIdle {
                forcedSize = size
                fontScale = scale
            }
            composeRule.waitForIdle()
            val first = composeRule.onNodeWithTag(LedgerTestTags.fundingAccount(7))
                .assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            val second = composeRule.onNodeWithTag(LedgerTestTags.fundingAccount(8))
                .assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            if (sideBySide) {
                assertTrue(second.left > first.left)
                assertTrue(kotlin.math.abs(first.top - second.top) < 1f)
            } else {
                assertTrue(kotlin.math.abs(first.left - second.left) < 1f)
                assertTrue(second.top > first.top)
            }
        }

        composeRule.onNodeWithText("跨账本共享 · 2 个账户").assertIsDisplayed()
        composeRule.onNodeWithText("取消默认").performClick()
        composeRule.waitUntil { selectedDefaultId.get() != Long.MIN_VALUE }
        assertNull(selectedDefaultId.get())
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
        syncId = "funding-$id",
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
