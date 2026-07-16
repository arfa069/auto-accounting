package com.autoaccounting.feature.billsync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManualBillImportHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun missingAccessibilityPermissionShowsPurposeAndNeverLaunchesSource() {
        var settingsOpened = false
        var sourceLaunched = false
        composeRule.setContent {
            ManualBillImportHost(
                openRequestId = 1,
                accessibilityAccessGranted = false,
                accessibilityServiceConnected = false,
                onOpenAccessibilitySettings = { settingsOpened = true },
                onLaunchSource = {
                    sourceLaunched = true
                    true
                }
            )
        }

        composeRule.onNodeWithText("需要无障碍权限").assertIsDisplayed()
        composeRule.onNodeWithText("仅在你主动补录时读取当前可见的微信或支付宝账单页。")
            .assertIsDisplayed()
        composeRule.onNodeWithText("去设置").performClick()

        assertTrue(settingsOpened)
        assertFalse(sourceLaunched)
    }

    @Test
    fun disconnectedAccessibilityServiceCanBeRecheckedAndNeverLaunchesSource() {
        var serviceConnected by mutableStateOf(false)
        var sourceLaunched = false
        composeRule.setContent {
            ManualBillImportHost(
                openRequestId = 1,
                accessibilityAccessGranted = true,
                accessibilityServiceConnected = serviceConnected,
                onLaunchSource = {
                    sourceLaunched = true
                    true
                }
            )
        }

        composeRule.onNodeWithText("无障碍服务未连接").assertIsDisplayed()
        composeRule.runOnIdle { serviceConnected = true }
        composeRule.onNodeWithText("重新检查").performClick()
        composeRule.onNodeWithText("选择账单来源").assertIsDisplayed()

        assertFalse(sourceLaunched)
    }

    @Test
    fun healthyFlowShowsGuidanceAndCompletionActions() {
        val controller = BillSyncSessionController()
        var launchedSource: BillSyncSource? = null
        var navigatedToReview = false
        var monitoringState = ContinuousMonitoringState()
        composeRule.setContent {
            ManualBillImportHost(
                openRequestId = 1,
                accessibilityAccessGranted = true,
                accessibilityServiceConnected = true,
                onLaunchSource = {
                    launchedSource = it
                    true
                },
                onNavigateToReview = { navigatedToReview = true },
                continuousMonitoringState = monitoringState,
                continuousMonitoringPermissionHealth = ContinuousMonitoringPermissionHealth(
                    billSyncAccessibilityGranted = true,
                    billSyncAccessibilityServiceConnected = true
                ),
                onContinuousMonitoringStateChange = { monitoringState = it },
                sessionController = controller
            )
        }

        composeRule.onNodeWithText("微信").performClick()
        composeRule.onNodeWithText("已打开微信").assertIsDisplayed()
        composeRule.onNodeWithText("请在 90 秒内进入账单、交易详情或支付结果页面。")
            .assertIsDisplayed()
        assertEquals(BillSyncSource.WeChat, launchedSource)

        runBlocking {
            controller.submitBillPage(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "bill page"
            ) { _, _ ->
                BillSyncResult(
                    steps = listOf(
                        BillSyncStep.OpenSource,
                        BillSyncStep.ReadBills,
                        BillSyncStep.Parse,
                        BillSyncStep.Deduplicate,
                        BillSyncStep.CreatePendingEntries,
                        BillSyncStep.Completed
                    ),
                    createdEntries = emptyList(),
                    duplicateSkippedCount = 2,
                    summary = "done"
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("新增 0 条").assertIsDisplayed()
        composeRule.onNodeWithText("去重 2 条").assertIsDisplayed()
        composeRule.onNodeWithText("开启自动记账").performClick()
        assertTrue(monitoringState.enabled)
        composeRule.onNodeWithText("查看待确认").performClick()
        assertTrue(navigatedToReview)
    }

    @Test
    fun ocrConsentIsExplicitAndPassedOnlyToTheCurrentWechatSession() {
        val controller = BillSyncSessionController()
        composeRule.setContent {
            ManualBillImportHost(
                openRequestId = 1,
                accessibilityAccessGranted = true,
                accessibilityServiceConnected = true,
                onLaunchSource = { true },
                sessionController = controller
            )
        }

        assertFalse(controller.state.value.manualOcrAllowed)
        composeRule.onNodeWithText("本次允许本机 OCR 识别微信历史账单详情页")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("manual-bill-import-ocr-consent").performClick()
        composeRule.onNodeWithText("微信").performClick()

        assertTrue(controller.state.value.manualOcrAllowed)
        composeRule.onNodeWithText("本次已允许本机 OCR；离开此次补录后自动失效。")
            .assertIsDisplayed()
    }

    @Test
    fun launchFailureCanReturnToSourceSelection() {
        val controller = BillSyncSessionController()
        composeRule.setContent {
            ManualBillImportHost(
                openRequestId = 1,
                accessibilityAccessGranted = true,
                accessibilityServiceConnected = true,
                onLaunchSource = { false },
                sessionController = controller
            )
        }

        composeRule.onNodeWithText("支付宝").performClick()
        composeRule.onNodeWithText("未找到支付宝，无法打开账单页面").assertIsDisplayed()
        composeRule.onNodeWithText("重试").performClick()
        composeRule.onNodeWithText("选择账单来源").assertIsDisplayed()
    }

    @Test
    fun waitingSessionTimesOutThroughTheHost() {
        val controller = BillSyncSessionController()
        composeRule.setContent {
            ManualBillImportHost(
                openRequestId = 1,
                accessibilityAccessGranted = true,
                accessibilityServiceConnected = true,
                onLaunchSource = { true },
                sessionController = controller,
                waitingTimeoutMillis = 1L
            )
        }

        composeRule.onNodeWithText("微信").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            controller.state.value.phase == BillSyncSessionPhase.Failed
        }

        composeRule.onNodeWithText("未识别到账单页，请重新补录").assertIsDisplayed()
        composeRule.onNodeWithText("重试").assertIsDisplayed()
    }
}
