package com.autoaccounting.feature.review

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.autoaccounting.data.local.ConfidenceState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewQueueContentTest {
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
        
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            composeRule.onAllNodesWithText("确认后记入「家庭账本」")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
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
        composeRule.onNodeWithText("通知捕获").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("微信支付收款凭证 午餐 35.90")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("无障碍节点").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("支付成功 午餐").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("ML Kit OCR").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("支付成功 午餐 ¥35.90").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("商户=午餐")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("确认入账").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已确认 午餐").assertIsDisplayed()
    }

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
        rawEvidenceText = """
            [通知捕获]
            微信支付收款凭证 午餐 35.90

            [无障碍节点]
            支付成功 午餐

            [ML Kit OCR]
            支付成功 午餐 ¥35.90
        """.trimIndent(),
        parsedFields = listOf("商户=午餐", "金额=35.90")
    )

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
