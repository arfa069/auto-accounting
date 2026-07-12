package com.autoaccounting.feature.monitoring

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.autoaccounting.feature.billsync.BillSyncSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutomaticBookkeepingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readyStatusDoesNotRequireBookkeepingResultNotificationPermission() {
        composeRule.setContent {
            AutomaticBookkeepingScreen(
                notificationListenerAccessGranted = true,
                billSyncAccessibilityAccessGranted = true,
                resultNotificationPermissionGranted = false,
                continuousMonitoringState = ContinuousMonitoringState(enabled = true),
                continuousMonitoringPermissionHealth = ContinuousMonitoringPermissionHealth(
                    billSyncAccessibilityGranted = true
                )
            )
        }

        composeRule.onNodeWithText("状态：已就绪").assertIsDisplayed()
        composeRule.onNodeWithText("记账结果通知").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("未授权不会影响本地采集和待确认入队").performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("云端 AI 分类").assertCountEquals(0)
        composeRule.onAllNodesWithText("数据与备份").assertCountEquals(0)
    }

    @Test
    fun manualBillSyncStartsTheSelectedPaymentSource() {
        var startedSource: BillSyncSource? = null
        composeRule.setContent {
            AutomaticBookkeepingScreen(
                billSyncAccessibilityAccessGranted = true,
                continuousMonitoringPermissionHealth = ContinuousMonitoringPermissionHealth(
                    billSyncAccessibilityGranted = true
                ),
                onStartManualBillSync = { startedSource = it }
            )
        }

        composeRule.onNodeWithTag("manual-bill-sync-WeChat")
            .performScrollTo()
            .performClick()

        assertEquals(BillSyncSource.WeChat, startedSource)
    }

    @Test
    fun permissionActionsOpenTheExpectedSystemEntryPoints() {
        var notificationSettingsOpened = false
        var accessibilitySettingsOpened = false
        var resultNotificationRequested = false
        composeRule.setContent {
            AutomaticBookkeepingScreen(
                onOpenNotificationListenerSettings = { notificationSettingsOpened = true },
                onOpenBillSyncAccessibilitySettings = { accessibilitySettingsOpened = true },
                onRequestResultNotificationPermission = { resultNotificationRequested = true }
            )
        }

        composeRule.onNodeWithTag("notification-listener-settings")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("bill-sync-accessibility-settings")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("result-notification-permission")
            .performScrollTo()
            .performClick()

        assertEquals(true, notificationSettingsOpened)
        assertEquals(true, accessibilitySettingsOpened)
        assertEquals(true, resultNotificationRequested)
    }

    @Test
    fun userCanEnableAndDisableAutomaticBookkeepingWithHealthyAccessibility() {
        composeRule.setContent {
            var state by remember { mutableStateOf(ContinuousMonitoringState()) }
            AutomaticBookkeepingScreen(
                billSyncAccessibilityAccessGranted = true,
                continuousMonitoringState = state,
                continuousMonitoringPermissionHealth = ContinuousMonitoringPermissionHealth(
                    billSyncAccessibilityGranted = true
                ),
                onContinuousMonitoringStateChange = { state = it }
            )
        }

        composeRule.onNodeWithText("开启自动记账").performClick()
        composeRule.onNodeWithText("关闭自动记账").assertIsDisplayed()
        composeRule.onNodeWithText("关闭自动记账").performClick()
        composeRule.onNodeWithText("状态：已关闭").assertIsDisplayed()
    }
}
