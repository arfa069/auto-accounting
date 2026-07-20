package com.autoaccounting

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.autoaccounting.data.local.AutoAccountingDatabaseProvider
import com.autoaccounting.data.local.CaptureReason
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.LedgerEntryInput
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.PendingEntryEntity
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.feature.account.LOCAL_MODE_SESSION_PREFERENCES
import com.autoaccounting.feature.account.FakeAccountRepository
import com.autoaccounting.feature.account.LocalModeSessionStore
import com.autoaccounting.feature.ledger.LedgerTestTags
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun enterLocalMode() {
        clearPersistedSession()
        LocalModeSessionStore(context).confirmLocalMode()
    }

    @After
    fun clearSession() {
        clearPersistedSession()
    }

    @Test
    fun restoredSessionStartsOnHome() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
        composeRule.onNodeWithText("主页").assertIsDisplayed()
    }

    @Test
    fun centeredAddActionOpensManualEntryAndCancelReturnsHome() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        composeRule.onNodeWithTag("app-add-entry").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("新增一笔").assertIsDisplayed()
        composeRule.onNodeWithTag("app-bottom-navigation").assertDoesNotExist()

        composeRule.onNodeWithText("取消").performClick()

        composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("app-bottom-navigation").assertIsDisplayed()
    }

    @Test
    fun firstLocalModeSelectionNavigatesToHome() {
        clearPersistedSession()
        composeRule.setContent {
            AutoAccountingApp()
        }

        composeRule.onNodeWithTag("agreement-toggle").performScrollTo().performClick()
        composeRule.onNodeWithText("继续使用本地模式").performClick()
        composeRule.onNodeWithText("进入本地模式").performClick()

        composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
    }

    @Test
    fun firstSignInNavigatesToHome() {
        clearPersistedSession()
        composeRule.setContent {
            AutoAccountingApp(
                accountRepositoryOverride = FakeAccountRepository(),
                persistAccountSessionOverride = { true }
            )
        }

        composeRule.onNodeWithText("登录").performClick()
        composeRule.onNodeWithTag("account-phone").performTextInput("13800138000")
        composeRule.onNodeWithTag("account-password").performTextInput("Aa123456!")
        composeRule.onNodeWithTag("agreement-toggle").performScrollTo().performClick()
        composeRule.onNodeWithText("登录").performScrollTo().performClick()

        composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
    }

    @Test
    fun eachPrimaryPageHidesBottomNavigationAndCanReturnHome() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        composeRule.onNodeWithTag("app-bottom-navigation").assertIsDisplayed()
        listOf("Review", "Ledger", "Reports", "Profile").forEach { tab ->
            composeRule.onNodeWithTag("app-tab-$tab").performClick()
            composeRule.onNodeWithTag("app-bottom-navigation").assertDoesNotExist()
            composeRule.onNodeWithTag("return-home").assertIsDisplayed().performClick()
            composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
            composeRule.onNodeWithTag("app-bottom-navigation").assertIsDisplayed()
        }
    }

    @Test
    fun reviewTitleAlignsVerticallyWithLedgerTitle() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        composeRule.onNodeWithTag("app-tab-Review").performClick()
        val reviewTitleTop = composeRule.onNodeWithText("待确认")
            .fetchSemanticsNode()
            .boundsInRoot
            .top

        composeRule.onNodeWithTag("return-home").performClick()
        composeRule.onNodeWithTag("app-tab-Ledger").performClick()
        val ledgerTitleTop = composeRule.onNodeWithText("默认账本")
            .fetchSemanticsNode()
            .boundsInRoot
            .top

        assertEquals(ledgerTitleTop, reviewTitleTop, 0.5f)
    }

    @Test
    fun systemBackFromEachPrimaryPageReturnsHome() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        listOf("Review", "Ledger", "Reports", "Profile").forEach { tab ->
            composeRule.onNodeWithTag("app-tab-$tab").performClick()

            composeRule.runOnIdle {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }

            composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
        }
    }

    @Test
    fun reportsFollowTheSelectedLedgerBook() {
        val repository = LocalLedgerRepository(AutoAccountingDatabaseProvider.get(context))
        val travelLedgerId = runBlocking {
            repository.clearLocalData()
            val defaultLedger = repository.ensureDefaultLedgerBook()
            repository.createManualEntry(
                defaultLedger.id,
                reportEntryInput(amountMinor = 100, merchantTitle = "默认账本支出")
            )
            val travelLedger = repository.createLedgerBook("旅行账本")
            repository.createManualEntry(
                travelLedger.id,
                reportEntryInput(amountMinor = 200, merchantTitle = "旅行账本支出")
            )
            repository.selectLedgerBook(defaultLedger.id)
            travelLedger.id
        }

        try {
            composeRule.setContent {
                AutoAccountingApp()
            }

            composeRule.onNodeWithTag("app-tab-Reports").performClick()
            composeRule.onNodeWithText("本月支出 ¥1.00").assertIsDisplayed()

            composeRule.onNodeWithTag("return-home").performClick()
            composeRule.onNodeWithTag("app-tab-Ledger").performClick()
            composeRule.onNodeWithTag(LedgerTestTags.MORE_MENU).performClick()
            composeRule.onNodeWithTag(LedgerTestTags.MANAGE_LEDGERS).performClick()
            composeRule.onNodeWithTag(LedgerTestTags.selectLedger(travelLedgerId))
                .performScrollTo()
                .performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("return-home")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeRule.onNodeWithText("旅行账本").assertIsDisplayed()

            composeRule.onNodeWithTag("return-home").performClick()
            composeRule.onNodeWithTag("app-tab-Reports").performClick()
            composeRule.onNodeWithText("本月支出 ¥2.00").assertIsDisplayed()
            composeRule.onNodeWithText("本月支出 ¥1.00").assertDoesNotExist()
        } finally {
            runBlocking {
                repository.clearLocalData()
            }
        }
    }

    @Test
    fun reviewNavigationRequestStillOpensReviewQueue() {
        composeRule.setContent {
            AutoAccountingApp(reviewNavigationRequest = 1L)
        }

        composeRule.onNodeWithText("待确认").assertIsDisplayed()
    }

    @Test
    fun editedReviewMerchantAndCategoryReachLedger() {
        val repository = LocalLedgerRepository(AutoAccountingDatabaseProvider.get(context))
        val now = System.currentTimeMillis()
        runBlocking {
            repository.clearLocalData()
            val defaultLedger = repository.ensureDefaultLedgerBook()
            repository.seedSystemCategories()
            repository.createManualEntry(
                defaultLedger.id,
                LedgerEntryInput(
                    flowDirection = FlowDirection.OUTFLOW,
                    transactionKind = TransactionKind.EXPENSE,
                    amountMinor = 3_590,
                    transactionTimeEpochMillis = now,
                    merchantTitle = "原始商户",
                    categoryId = "food",
                    fundingAccountId = null,
                    newFundingAccountLabel = null,
                    note = null,
                    paymentSource = PaymentSource.ALIPAY
                )
            )
            repository.upsertPending(
                PendingEntryEntity(
                    id = "pending-edited-review",
                    source = PaymentSource.ALIPAY,
                    captureReason = CaptureReason.BILL_SYNC,
                    confidence = ConfidenceState.DUPLICATE_SUSPECT,
                    transactionKind = TransactionKind.EXPENSE,
                    amountMinor = 3_590,
                    currency = "CNY",
                    merchantTitle = "原始商户",
                    transactionTimeEpochMillis = now,
                    capturedAtEpochMillis = now,
                    suggestedCategoryId = "food",
                    fundingAccountId = null,
                    fundingAccountLabel = null,
                    note = null,
                    evidenceSummary = null,
                    parsedFieldsText = null
                )
            )
        }

        try {
            composeRule.setContent { AutoAccountingApp() }
            composeRule.onNodeWithTag("app-tab-Review").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("review-queue-list")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(4)
            composeRule.onNodeWithTag("detail-pending-edited-review").performClick()
            composeRule.onNodeWithTag("manual-entry-merchant").performTextClearance()
            composeRule.onNodeWithTag("manual-entry-merchant").performTextInput("修改后商户")
            composeRule.onNodeWithTag("manual-entry-category").performScrollTo().performClick()
            composeRule.onNodeWithText("购物").performClick()
            composeRule.onNodeWithText("确认入账").performClick()
            composeRule.onNodeWithText("这次不保存").performClick()

            composeRule.waitUntil(timeoutMillis = 5_000) {
                runBlocking {
                    repository.listLedgerEntries().any {
                        it.originPendingEntryId == "pending-edited-review"
                    }
                }
            }
            val ledgerEntry = runBlocking {
                repository.listLedgerEntries().single {
                    it.originPendingEntryId == "pending-edited-review"
                }
            }
            assertEquals("修改后商户", ledgerEntry.merchantTitle)
            assertEquals("shopping", ledgerEntry.categoryId)

            composeRule.onNodeWithTag("return-home").performClick()
            composeRule.onNodeWithTag("app-tab-Ledger").performClick()
            composeRule.onNodeWithTag(LedgerTestTags.ENTRY_LIST).performScrollToIndex(1)
            composeRule.onNodeWithText("修改后商户").assertIsDisplayed()
            composeRule.onNodeWithText("购物 · 支付宝", substring = true).assertIsDisplayed()
        } finally {
            runBlocking { repository.clearLocalData() }
        }
    }

    @Test
    fun systemBackFromAccountManagementReturnsToProfileOverview() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        openProfileTab()
        composeRule.onNodeWithTag("profile-account-status-card").performClick()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("profile-account-status-card").assertIsDisplayed()
    }

    @Test
    fun systemBackFromAccountEntryReturnsToAccountManagement() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        openProfileTab()
        composeRule.onNodeWithTag("profile-account-status-card").performClick()
        composeRule.onNodeWithText("登录或注册").performClick()
        composeRule.onNodeWithText("登录").performClick()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithText("创建账号").assertIsDisplayed()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithText("账户管理").assertIsDisplayed()
        composeRule.onNodeWithText("登录或注册").assertIsDisplayed()
    }

    @Test
    fun automaticBookkeepingEntryOpensItsDedicatedPage() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        openProfileTab()
        composeRule.onNodeWithTag("profile-entry-AutomaticBookkeeping").performClick()

        composeRule.onNodeWithText("自动记账状态")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun billImportRemainsOnReviewAndIsAbsentFromAutomaticBookkeeping() {
        composeRule.setContent {
            AutoAccountingApp(
                billSyncAccessibilityAccessGranted = true,
                billSyncAccessibilityServiceConnected = true,
                onLaunchBillSyncSource = { true }
            )
        }

        composeRule.onNodeWithTag("app-tab-Review").performClick()
        composeRule.onNodeWithText("补录账单").performClick()
        composeRule.onNodeWithTag("manual-bill-import-host").assertIsDisplayed()
        composeRule.onNodeWithText("选择账单来源").assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()

        composeRule.onNodeWithTag("return-home").performClick()
        openProfileTab()
        composeRule.onNodeWithTag("profile-entry-AutomaticBookkeeping").performClick()

        composeRule.onNodeWithTag("manual-bill-import").assertDoesNotExist()
        composeRule.onNodeWithText("补录账单").assertDoesNotExist()
        composeRule.onNodeWithTag("manual-bill-import-host").assertDoesNotExist()
    }

    @Test
    fun disconnectedAccessibilityKeepsPersistedAutomaticBookkeepingIntentEnabled() {
        val preferencesRepository = LocalPreferencesRepository(
            AutoAccountingDatabaseProvider.get(context)
        )
        runBlocking {
            preferencesRepository.updateContinuousMonitoringState(
                ContinuousMonitoringState(enabled = true)
            )
        }

        try {
            composeRule.setContent {
                AutoAccountingApp(
                    billSyncAccessibilityAccessGranted = true,
                    billSyncAccessibilityServiceConnected = false,
                    permissionStateLoaded = true
                )
            }

            openProfileTab()
            composeRule.onNodeWithTag("profile-entry-AutomaticBookkeeping").performClick()

            composeRule.onNodeWithText("状态：需要处理").assertIsDisplayed()
            composeRule.onNodeWithText("关闭自动记账").assertIsDisplayed()
            assertTrue(
                runBlocking {
                    preferencesRepository.userPreferences.first()
                        .continuousMonitoringState.enabled
                }
            )
        } finally {
            runBlocking {
                preferencesRepository.updateContinuousMonitoringState(
                    ContinuousMonitoringState()
                )
            }
        }
    }

    @Test
    fun systemBackFromAutomaticBookkeepingReturnsToProfileOverview() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        openProfileTab()
        composeRule.onNodeWithTag("profile-entry-AutomaticBookkeeping").performClick()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("profile-entry-AutomaticBookkeeping").assertIsDisplayed()
    }

    @Test
    fun categorizationRulesEntryOpensDedicatedPage() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        openProfileTab()
        composeRule.onNodeWithTag("profile-entry-CategorizationRules")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText("新建规则").assertExists()
    }

    @Test
    fun systemBackFromCategorizationRulesReturnsToProfileOverview() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        openProfileTab()
        composeRule.onNodeWithTag("profile-entry-CategorizationRules")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("profile-entry-CategorizationRules")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun dataAndBackupEntryOpensDedicatedPage() {
        composeRule.setContent { AutoAccountingApp() }

        openProfileTab()
        composeRule.onNodeWithTag("profile-entry-DataAndBackup")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText("导出与恢复").assertIsDisplayed()
        composeRule.onNodeWithText("危险区").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun systemBackFromDataAndBackupReturnsToProfileOverview() {
        composeRule.setContent { AutoAccountingApp() }

        openProfileTab()
        composeRule.onNodeWithTag("profile-entry-DataAndBackup")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("profile-entry-DataAndBackup")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun complianceAndPrivacyEntryOpensDedicatedPageWithDebugTools() {
        composeRule.setContent { AutoAccountingApp() }

        openProfileTab()
        composeRule.onNodeWithTag("profile-entry-ComplianceAndPrivacy")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText("合规与隐私").assertIsDisplayed()
        composeRule.onNodeWithTag("compliance-entry-PrivacyPolicy").assertIsDisplayed()
        composeRule.onNodeWithTag("developer-tools-entry").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun systemBackFromComplianceAndPrivacyReturnsToProfileOverview() {
        composeRule.setContent { AutoAccountingApp() }

        openProfileTab()
        composeRule.onNodeWithTag("profile-entry-ComplianceAndPrivacy")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("profile-entry-ComplianceAndPrivacy")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun clearPersistedSession() {
        context.getSharedPreferences(
            LOCAL_MODE_SESSION_PREFERENCES,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }

    private fun reportEntryInput(
        amountMinor: Long,
        merchantTitle: String
    ): LedgerEntryInput = LedgerEntryInput(
        flowDirection = FlowDirection.OUTFLOW,
        transactionKind = TransactionKind.EXPENSE,
        amountMinor = amountMinor,
        transactionTimeEpochMillis = 1_783_513_200_000,
        merchantTitle = merchantTitle,
        categoryId = "food",
        fundingAccountId = null,
        newFundingAccountLabel = null,
        note = null,
        paymentSource = null
    )

    private fun openProfileTab() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("app-tab-Profile")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("app-tab-Profile").performClick()
    }
}
