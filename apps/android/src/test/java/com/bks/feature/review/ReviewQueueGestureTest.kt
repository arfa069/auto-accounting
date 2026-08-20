package com.bks.feature.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.dp
import com.bks.data.local.ConfidenceState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewQueueGestureTest {
    @get:Rule
    val composeRule = createComposeRule()

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
}
