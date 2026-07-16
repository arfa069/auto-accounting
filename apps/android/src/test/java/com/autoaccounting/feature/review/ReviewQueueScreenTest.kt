package com.autoaccounting.feature.review

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTextContains
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
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.categorization.AiCategorizationGateway
import com.autoaccounting.feature.categorization.AiCategorizationPayload
import com.autoaccounting.feature.categorization.AiCategorizationResponse
import com.autoaccounting.feature.categorization.AiCategorizationSettings
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

        composeRule.onNodeWithTag("review-header-row").assertHeightIsEqualTo(52.dp)
        composeRule.onNodeWithTag("review-header-row").assertTopPositionInRootIsEqualTo(20.dp)
        composeRule.onNodeWithText("确认后记入「家庭账本」").assertIsDisplayed()
    }

    @Test
    fun shortSlowSwipesInEitherDirectionDoNotResolvePendingEntry() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }
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

        composeRule.onNodeWithText("编辑待确认记录").assertIsDisplayed()
    }

    @Test
    fun confirmShowsUndoAndRestoresEntry() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("confirm-pending-lunch").performClick()

        composeRule.onNodeWithText("已确认 午餐").assertIsDisplayed()
        composeRule.onNodeWithText("撤销").performClick()
        composeRule.onNodeWithText("午餐").assertIsDisplayed()
    }

    @Test
    fun ignoreCanBeRecoveredFromIgnoredList() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("ignore-pending-lunch").performClick()
        composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(0)
        composeRule.onNodeWithText("忽略记录").performClick()
        composeRule.onNodeWithText("午餐").assertIsDisplayed()
        composeRule.onNodeWithTag("recover-ignored-pending-lunch").performClick()

        composeRule.onNodeWithText("1 条待确认").assertIsDisplayed()
    }

    @Test
    fun detailEditUpdatesPendingRow() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.onNodeWithTag("edit-title").performTextClearance()
        composeRule.onNodeWithTag("edit-title").performTextInput("工作餐")
        composeRule.onNodeWithTag("edit-amount").performTextClearance()
        composeRule.onNodeWithTag("edit-amount").performTextInput("45.80")
        composeRule.onNodeWithTag("edit-time").performTextClearance()
        composeRule.onNodeWithTag("edit-time").performTextInput("2026-07-08 12:30")
        composeRule.onNodeWithTag("edit-kind").performTextClearance()
        composeRule.onNodeWithTag("edit-kind").performTextInput("退款")
        composeRule.onNodeWithTag("edit-funding-account").performTextClearance()
        composeRule.onNodeWithTag("edit-funding-account").performTextInput("微信零钱")
        composeRule.onNodeWithTag("edit-note").performTextInput("客户会议")
        composeRule.onNodeWithText("保存").performClick()

        composeRule.onNodeWithText("工作餐").assertIsDisplayed()
        composeRule.onNodeWithText("¥45.80").assertIsDisplayed()
        composeRule.onNodeWithText("客户会议").assertIsDisplayed()
    }

    @Test
    fun invalidAmountKeepsDialogOpenAndDoesNotSilentlyUpdateRow() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.onNodeWithTag("edit-title").performTextClearance()
        composeRule.onNodeWithTag("edit-title").performTextInput("工作餐")
        composeRule.onNodeWithTag("edit-amount").performTextClearance()
        composeRule.onNodeWithTag("edit-amount").performTextInput("abc")
        composeRule.onNodeWithText("保存").performClick()

        composeRule.onNodeWithText("金额格式不正确").assertIsDisplayed()
        composeRule.onNodeWithText("编辑待确认记录").assertIsDisplayed()

        composeRule.onNodeWithText("取消").performClick()
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

        composeRule.onNodeWithText("2 条待确认").assertIsDisplayed()
        composeRule.onNodeWithText("疑似重复 1").assertIsDisplayed()
        composeRule.onNodeWithText("今日待确认 2").assertIsDisplayed()
        composeRule.onAllNodesWithText("已确认 0").assertCountEquals(0)
        composeRule.onNodeWithText("补录账单").performClick()

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

        composeRule.onNodeWithText("待确认记录").assertIsDisplayed()
        composeRule.onAllNodesWithText("快速确认").assertCountEquals(0)
        composeRule.onAllNodesWithText("需细看").assertCountEquals(0)
        composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(4)
        composeRule.onNodeWithTag("detail-duplicate").assertIsDisplayed()
        composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(5)
        composeRule.onNodeWithTag("detail-needs-review").assertIsDisplayed()
        composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(6)
        composeRule.onNodeWithTag("detail-high").assertIsDisplayed()
    }

    @Test
    fun emptyQueueKeepsSummaryAndBillImportEntry() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState())
        }

        composeRule.onNodeWithText("0 条待确认").assertIsDisplayed()
        composeRule.onNodeWithText("补录账单").assertIsDisplayed()
        composeRule.onNodeWithText("待确认记录").assertIsDisplayed()
        composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(4)
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

        composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(23)
        composeRule.onNodeWithTag("detail-pending-20").assertIsDisplayed()
    }

    @Test
    fun rowShowsCaptureReasonAndConfidence() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }
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
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.onNodeWithText("查看证据").performClick()
        composeRule.onNodeWithText("微信支付收款凭证 午餐 35.90")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("商户=午餐")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("确认入账").performClick()
        composeRule.onNodeWithText("已确认 午餐").assertIsDisplayed()

        composeRule.onNodeWithText("撤销").performClick()
        scrollToFirstPendingEntry()
        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.onNodeWithText("忽略此条").performClick()
        composeRule.onNodeWithText("已忽略 午餐").assertIsDisplayed()
    }

    @Test
    fun categoryCorrectionAsksBeforeSavingRule() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.onNodeWithTag("edit-category").performTextClearance()
        composeRule.onNodeWithTag("edit-category").performTextInput("工作餐")
        composeRule.onNodeWithText("保存").performClick()

        composeRule.onNodeWithText("保存为分类规则？").assertIsDisplayed()
        composeRule.onNodeWithText("这次不保存").performClick()
        composeRule.onNodeWithTag("detail-pending-lunch").assertTextContains("工作餐")
    }

    @Test
    fun signedInConsentedUserCanRequestAiCategorySuggestion() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry().copy(category = ""))),
                accountSession = AccountSession.SignedIn(phone = "13800138000", token = "token-1"),
                aiSettings = AiCategorizationSettings(aiConsentGranted = true),
                aiCategorizationGateway = FixedAiCategorizationGateway("交通")
            )
        }
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.onNodeWithText("AI 建议分类").performClick()

        composeRule.onNodeWithTag("edit-category").assertTextContains("交通")
    }

    private fun scrollToFirstPendingEntry() {
        composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(4)
    }

    private fun sampleEntry(
        id: String = "pending-lunch",
        confidence: ConfidenceState = ConfidenceState.NEEDS_REVIEW
    ): ReviewQueueEntry = ReviewQueueEntry(
        id = id,
        title = "午餐",
        amountMinor = 3590,
        transactionTimeText = "2026-07-08 12:20",
        category = "餐饮",
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
