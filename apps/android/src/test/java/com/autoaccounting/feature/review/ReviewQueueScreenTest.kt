package com.autoaccounting.feature.review

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.categorization.AiCategorizationGateway
import com.autoaccounting.feature.categorization.AiCategorizationPayload
import com.autoaccounting.feature.categorization.AiCategorizationResponse
import com.autoaccounting.feature.categorization.AiCategorizationSettings
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
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
    fun confirmShowsUndoAndRestoresEntry() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }

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

        composeRule.onNodeWithTag("ignore-pending-lunch").performClick()
        composeRule.onNodeWithText("忽略列表").performClick()
        composeRule.onNodeWithText("午餐").assertIsDisplayed()
        composeRule.onNodeWithTag("recover-ignored-pending-lunch").performClick()

        composeRule.onNodeWithText("待确认 1").assertIsDisplayed()
    }

    @Test
    fun detailEditUpdatesPendingRow() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }

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
    fun summaryShowsSpecCountsAndSyncAction() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(
                    pendingEntries = listOf(
                        sampleEntry(id = "duplicate", confidence = ConfidenceState.DUPLICATE_SUSPECT),
                        sampleEntry(id = "quick", confidence = ConfidenceState.HIGH)
                    ),
                    todayStartEpochMillis = NOW - 1
                )
            )
        }

        composeRule.onNodeWithText("待确认 2").assertIsDisplayed()
        composeRule.onNodeWithText("疑似重复 1").assertIsDisplayed()
        composeRule.onNodeWithText("今日新增 2").assertIsDisplayed()
        composeRule.onNodeWithText("账单同步").performClick()
        composeRule.onNodeWithText("选择同步来源").assertIsDisplayed()
        composeRule.onNodeWithText("微信").performClick()
        composeRule.onNodeWithText("打开来源").assertIsDisplayed()
        composeRule.onNodeWithText("读取账单").assertIsDisplayed()
        composeRule.onNodeWithText("已创建 1 条，已去重 0 条").assertIsDisplayed()
        composeRule.onNodeWithText("完成").performClick()
        composeRule.onNodeWithText("待确认 3").assertIsDisplayed()
    }

    @Test
    fun rowShowsCaptureReasonAndConfidence() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }

        composeRule.onNodeWithText("通知捕获").assertIsDisplayed()
        composeRule.onNodeWithText("需复核").assertIsDisplayed()
    }

    @Test
    fun detailShowsEvidenceAndCanConfirmOrIgnore() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }

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
        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.onNodeWithText("忽略此条").performClick()
        composeRule.onNodeWithText("已忽略 午餐").assertIsDisplayed()
    }

    @Test
    fun categoryCorrectionAsksBeforeSavingRule() {
        composeRule.setContent {
            ReviewQueueScreen(initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())))
        }

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.onNodeWithTag("edit-category").performTextClearance()
        composeRule.onNodeWithTag("edit-category").performTextInput("工作餐")
        composeRule.onNodeWithText("保存").performClick()

        composeRule.onNodeWithText("保存为分类规则？").assertIsDisplayed()
        composeRule.onNodeWithText("这次不保存").performClick()
        composeRule.onNodeWithText("工作餐").assertIsDisplayed()
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

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.onNodeWithText("AI 建议分类").performClick()

        composeRule.onNodeWithTag("edit-category").assertTextContains("交通")
    }

    @Test
    fun billSyncCompletionCanPromptAdvancedMonitoringButFirstScreenDoesNotShowIt() {
        var monitoringState = ContinuousMonitoringState()
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry())),
                continuousMonitoringState = monitoringState,
                onContinuousMonitoringStateChange = { monitoringState = it }
            )
        }

        composeRule.onAllNodesWithText("连续监控").assertCountEquals(0)
        composeRule.onNodeWithText("账单同步").performClick()
        composeRule.onNodeWithText("微信").performClick()
        composeRule.onNodeWithText("完成").performClick()

        composeRule.onNodeWithText("试试连续监控").assertIsDisplayed()
        composeRule.onNodeWithText("只观察支付相关页面，可随时关闭。").assertIsDisplayed()
        composeRule.onNodeWithText("开启连续监控").performClick()

        assertTrue(monitoringState.enabled)
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
