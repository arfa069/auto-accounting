package com.autoaccounting.feature.review

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.FundingAccountSourceScope
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.categorization.AiCategorizationGateway
import com.autoaccounting.feature.categorization.AiCategorizationPayload
import com.autoaccounting.feature.categorization.AiCategorizationResponse
import com.autoaccounting.feature.categorization.AiCategorizationSettings
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewQueueScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun headerShowsCurrentLedgerAsConfirmationTarget() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(),
                targetLedgerName = "家庭账本"
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("review-header-row").assertHeightIsEqualTo(52.dp)
        composeRule.onNodeWithTag("review-header-row").assertTopPositionInRootIsEqualTo(20.dp)
        composeRule.onNodeWithText("确认后记入「家庭账本」").assertIsDisplayed()
    }

    @Test
    fun shortSlowSwipesInEitherDirectionDoNotResolvePendingEntry() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performTouchInput {
            down(center)
            advanceEventTime(500)
            moveBy(Offset(72.dp.toPx(), 0f))
            advanceEventTime(500)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-pending-lunch").assertIsDisplayed()

        composeRule.onNodeWithTag("detail-pending-lunch").performTouchInput {
            down(center)
            advanceEventTime(500)
            moveBy(Offset(-72.dp.toPx(), 0f))
            advanceEventTime(500)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-pending-lunch").assertIsDisplayed()
    }

    @Test
    fun shortFastSwipeDoesNotResolvePendingEntry() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performTouchInput {
            swipeWithVelocity(
                start = center,
                end = center + Offset(-48.dp.toPx(), 0f),
                endVelocity = 2_000f
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("detail-pending-lunch").assertIsDisplayed()
    }

    @Test
    fun longSwipeFromStartToEndConfirmsPendingEntry() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performTouchInput {
            down(center)
            advanceEventTime(500)
            moveBy(Offset(160.dp.toPx(), 0f))
            advanceEventTime(500)
            up()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("已确认 午餐").assertIsDisplayed()
    }

    @Test
    fun longSwipeFromEndToStartIgnoresPendingEntry() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performTouchInput {
            down(center)
            advanceEventTime(500)
            moveBy(Offset(-160.dp.toPx(), 0f))
            advanceEventTime(500)
            up()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("已忽略 午餐").assertIsDisplayed()
    }

    @Test
    fun pendingNotificationNavigationOpensMatchingEntry() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())),
                openPendingEntryId = "pending-lunch",
                openPendingEntryRequestId = 1
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("编辑待确认账目").assertIsDisplayed()
    }

    @Test
    fun confirmShowsUndoAndRestoresEntry() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("confirm-pending-lunch").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("已确认 午餐").assertIsDisplayed()
        composeRule.onNodeWithText("撤销").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("午餐").assertIsDisplayed()
    }

    @Test
    fun ignoreCanBeRecoveredFromIgnoredList() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("ignore-pending-lunch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(0)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("忽略记录").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("午餐").assertIsDisplayed()
        composeRule.onNodeWithTag("recover-ignored-pending-lunch").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("1 条待确认").assertIsDisplayed()
    }

    @Test
    fun sharedEditorConfirmsSelectedCategoryAndFundingAccount() {
        val nextState = AtomicReference<ReviewQueueState?>()
        composeRule.setContent {
            ReviewQueueScreen(
                state = ReviewQueueState(pendingEntries = listOf(sampleEntry())),
                onStateChange = { nextState.set(it) },
                categories = listOf(
                    category("food", "餐饮", TransactionKind.EXPENSE, 10),
                    category("shopping", "购物", TransactionKind.EXPENSE, 20)
                ),
                fundingAccounts = listOf(
                    fundingAccount(42, "微信零钱", PaymentSource.WECHAT),
                    fundingAccount(84, "支付宝余额", PaymentSource.ALIPAY)
                )
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("编辑待确认账目").assertIsDisplayed()
        composeRule.onNodeWithTag("manual-direction-OUTFLOW").assertIsDisplayed()
        composeRule.onNodeWithTag("manual-direction-INFLOW").assertIsDisplayed()
        composeRule.onNodeWithTag("manual-direction-NEUTRAL").assertDoesNotExist()
        composeRule.onNodeWithTag("manual-entry-merchant").performScrollTo()
        composeRule.onNodeWithText("商户（可选）").assertIsDisplayed()
        composeRule.onNodeWithText("商户/标题（可选）").assertDoesNotExist()
        composeRule.onNodeWithTag("manual-entry-merchant").performTextClearance()
        composeRule.onNodeWithTag("manual-entry-merchant").performTextInput("工作餐")
        composeRule.onNodeWithTag("manual-entry-amount").performTextClearance()
        composeRule.onNodeWithTag("manual-entry-amount").performTextInput("45.80")
        composeRule.onNodeWithTag("manual-entry-category").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("购物").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("manual-entry-funding-account").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("支付宝余额").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("manual-entry-note").performTextInput("客户会议")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认入账").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("这次不保存").performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 5_000) { nextState.get() != null }
        val confirmed = requireNotNull(nextState.get()).confirmedEntries.single().entry
        assertEquals("工作餐", confirmed.title)
        assertEquals(4_580L, confirmed.amountMinor)
        assertEquals("shopping", confirmed.categoryId)
        assertEquals("购物", confirmed.category)
        assertEquals(84L, confirmed.fundingAccountId)
        assertEquals("支付宝余额", confirmed.fundingAccountLabel)
        assertEquals("客户会议", confirmed.note)
    }

    @Test
    fun invalidAmountKeepsDialogOpenAndDoesNotSilentlyUpdateRow() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("manual-entry-merchant").performTextClearance()
        composeRule.onNodeWithTag("manual-entry-merchant").performTextInput("工作餐")
        composeRule.onNodeWithTag("manual-entry-amount").performTextClearance()
        composeRule.onNodeWithTag("manual-entry-amount").performTextInput("abc")
        composeRule.onNodeWithText("确认入账").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("金额必须大于 0，且最多保留两位小数").assertIsDisplayed()
        composeRule.onNodeWithText("编辑待确认账目").assertIsDisplayed()

        composeRule.onNodeWithText("取消").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        composeRule.onNodeWithText("放弃修改").performClick()
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()
        composeRule.onNodeWithText("午餐").assertIsDisplayed()
        composeRule.onNodeWithText("¥35.90").assertIsDisplayed()
        composeRule.onAllNodesWithText("工作餐").assertCountEquals(0)
    }

    @Test
    fun summaryShowsApprovedCountsAndBillImportAction() {
        var billImportOpened = false
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(
                    pendingEntries = listOf(
                        sampleEntry(id = "duplicate", confidence = ConfidenceState.DUPLICATE_SUSPECT),
                        sampleEntry(id = "quick", confidence = ConfidenceState.HIGH)
                    ),
                    todayStartEpochMillis = NOW - 1
                ),
                onOpenBillImport = { billImportOpened = true }
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("2 条待确认").assertIsDisplayed()
        composeRule.onNodeWithText("疑似重复 1").assertIsDisplayed()
        composeRule.onNodeWithText("今日待确认 2").assertIsDisplayed()
        composeRule.onAllNodesWithText("已确认 0").assertCountEquals(0)
        composeRule.onNodeWithText("补录账单").performClick()
        composeRule.waitForIdle()

        assertTrue(billImportOpened)
        composeRule.onAllNodesWithText("选择账单来源").assertCountEquals(0)
    }

    @Test
    fun allPendingEntriesAppearInSingleReviewList() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(
                    pendingEntries = listOf(
                        sampleEntry(id = "high", confidence = ConfidenceState.HIGH),
                        sampleEntry(id = "needs-review", confidence = ConfidenceState.NEEDS_REVIEW),
                        sampleEntry(id = "duplicate", confidence = ConfidenceState.DUPLICATE_SUSPECT)
                    )
                )
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("待确认记录").assertIsDisplayed()
        composeRule.onAllNodesWithText("快速确认").assertCountEquals(0)
        composeRule.onAllNodesWithText("需细看").assertCountEquals(0)
        composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(4)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-duplicate").assertIsDisplayed()
        composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(5)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-needs-review").assertIsDisplayed()
        composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(6)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-high").assertIsDisplayed()
    }

    @Test
    fun emptyQueueKeepsSummaryAndBillImportEntry() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState())
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("0 条待确认").assertIsDisplayed()
        composeRule.onNodeWithText("补录账单").assertIsDisplayed()
        composeRule.onNodeWithText("待确认记录").assertIsDisplayed()
        composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(4)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("暂无待确认记录").assertIsDisplayed()
        composeRule.onAllNodesWithText("需细看").assertCountEquals(0)
    }

    @Test
    fun largeQueueCanScrollToLastPendingEntry() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(
                    pendingEntries = (1..20).map { index ->
                        sampleEntry(id = "pending-$index").copy(title = "记录 $index")
                    }
                )
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(23)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-pending-20").assertIsDisplayed()
    }

    @Test
    fun rowShowsCaptureReasonAndConfidence() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithText("通知捕获").assertIsDisplayed()
        composeRule.onNodeWithText("需复核").assertIsDisplayed()
        composeRule.onNodeWithText("2026-07-08 12:20 · 微信 · 微信零钱").assertIsDisplayed()
    }

    @Test
    fun detailShowsEvidenceAndCanConfirmOrIgnore() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("查看证据").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("微信支付收款凭证 午餐 35.90")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("商户=午餐")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("确认入账").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已确认 午餐").assertIsDisplayed()
    }

    @Test
    fun categoryCorrectionAsksBeforeSavingRule() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())),
                categories = listOf(
                    category("food", "餐饮", TransactionKind.EXPENSE, 10),
                    category("shopping", "购物", TransactionKind.EXPENSE, 20)
                )
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("manual-entry-category").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("购物").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确认入账").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("保存为分类规则？").assertIsDisplayed()
        composeRule.onNodeWithText("这次不保存").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已确认 午餐").assertIsDisplayed()
    }

    @Test
    fun duplicateReviewUsesCategoryPickerAndStartsWithEmptyNote() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(
                    pendingEntries = listOf(
                        sampleEntry(confidence = ConfidenceState.DUPLICATE_SUSPECT)
                    )
                ),
                categories = listOf(
                    category("food", "餐饮", TransactionKind.EXPENSE, 10),
                    category("shopping", "购物", TransactionKind.EXPENSE, 20)
                )
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("manual-entry-note").assertTextEquals("备注（可选）", "")
        composeRule.onNodeWithTag("manual-entry-category").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("购物").assertIsDisplayed()
    }

    @Test
    fun signedInConsentedUserCanRequestAiCategorySuggestion() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry().copy(category = ""))),
                categories = listOf(
                    category("food", "餐饮", TransactionKind.EXPENSE, 10),
                    category("transport", "交通", TransactionKind.EXPENSE, 20)
                ),
                accountSession = AccountSession.SignedIn(phone = "13800138000", token = "token-1"),
                aiSettings = AiCategorizationSettings(aiConsentGranted = true),
                aiCategorizationGateway = FixedAiCategorizationGateway("交通")
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AI 建议分类").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("manual-entry-category")
            .performScrollTo()
            .assertTextContains("交通")
    }

    @Test
    fun sharedEditorListsExistingFundingAccounts() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())),
                fundingAccounts = listOf(
                    fundingAccount(42, "微信零钱", PaymentSource.WECHAT),
                    fundingAccount(84, "支付宝余额", PaymentSource.ALIPAY)
                )
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("manual-entry-funding-account")
            .performScrollTo()
            .assertTextContains("微信零钱")
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("支付宝余额").assertIsDisplayed()
    }

    private fun category(
        id: String,
        name: String,
        kind: TransactionKind,
        sortOrder: Int
    ): CategoryEntity = CategoryEntity(
        id = id,
        name = name,
        kind = kind,
        sortOrder = sortOrder,
        isSystem = true,
        createdAtEpochMillis = NOW
    )

    private fun fundingAccount(
        id: Long,
        label: String,
        source: PaymentSource
    ): FundingAccountEntity = FundingAccountEntity(
        id = id,
        sourceScope = when (source) {
            PaymentSource.WECHAT -> FundingAccountSourceScope.WECHAT
            PaymentSource.ALIPAY -> FundingAccountSourceScope.ALIPAY
        },
        paymentSource = source,
        label = label,
        createdAtEpochMillis = NOW
    )

    private fun scrollToFirstPendingEntry() {
        composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(4)
        composeRule.waitForIdle()
    }

    private fun sampleEntry(
        id: String = "pending-lunch",
        confidence: ConfidenceState = ConfidenceState.NEEDS_REVIEW
    ): ReviewQueueEntry = ReviewQueueEntry(
        id = id,
        title = "午餐",
        amountMinor = 3590,
        transactionTimeText = "2026-07-08 12:20",
        categoryId = "food",
        category = "餐饮",
        fundingAccountId = 42L,
        fundingAccountLabel = "微信零钱",
        sourceLabel = "微信",
        kindLabel = "支出",
        captureReasonLabel = "通知捕获",
        confidence = confidence,
        capturedAtEpochMillis = NOW,
        captureTimeText = "2026-07-08 12:21",
        note = null,
        rawEvidenceText = "微信支付收款凭证 午餐 35.90",
        parsedFields = listOf("商户=午餐", "金额=35.90")
    )

    private companion object {
        const val NOW = 1_783_468_800_000L
    }

    private class FixedAiCategorizationGateway(
        private val category: String
    ) : AiCategorizationGateway {
        override fun suggestCategory(
            token: String,
            payload: AiCategorizationPayload
        ): AiCategorizationResponse = AiCategorizationResponse(
            category = category,
            confidenceLabel = "中",
            explanation = "测试建议"
        )
    }
}
