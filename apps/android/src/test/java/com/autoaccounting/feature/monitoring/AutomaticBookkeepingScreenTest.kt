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
        composeRule.onAllNodesWithText("记账结果通知").assertCountEquals(0)
        composeRule.onNodeWithText("持续监控正常").performScrollTo().assertIsDisplayed()
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
        var backgroundSettingsOpened = false
        var autoStartSettingsOpened = false
        var batteryOptimizationSettingsOpened = false
        var batterySaverSettingsOpened = false
        composeRule.setContent {
            AutomaticBookkeepingScreen(
                onOpenNotificationListenerSettings = { notificationSettingsOpened = true },
                onOpenBillSyncAccessibilitySettings = { accessibilitySettingsOpened = true },
                onOpenBackgroundRunningSettings = { backgroundSettingsOpened = true },
                onOpenAutoStartSettings = { autoStartSettingsOpened = true },
                onOpenBatteryOptimizationSettings = { batteryOptimizationSettingsOpened = true },
                onOpenBatterySaverSettings = { batterySaverSettingsOpened = true }
            )
        }

        composeRule.onNodeWithTag("notification-listener-settings")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("bill-sync-accessibility-settings")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("background-running-settings")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("auto-start-settings")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("battery-optimization-settings")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("battery-saver-settings")
            .performScrollTo()
            .performClick()

        assertEquals(true, notificationSettingsOpened)
        assertEquals(true, accessibilitySettingsOpened)
        assertEquals(true, backgroundSettingsOpened)
        assertEquals(true, autoStartSettingsOpened)
        assertEquals(true, batteryOptimizationSettingsOpened)
        assertEquals(true, batterySaverSettingsOpened)
    }

    @Test
    fun compactPermissionListShowsRequiredAndReliabilityItems() {
        composeRule.setContent {
            AutomaticBookkeepingScreen(
                backgroundReliabilityState = BackgroundReliabilityState(
                    batteryOptimizationIgnored = true,
                    powerSaveModeEnabled = false,
                    manufacturer = DeviceManufacturer.Xiaomi
                )
            )
        }

        listOf(
            "通知监听（重要）",
            "自动记账无障碍权限（重要）",
            "允许后台运行（建议）",
            "允许应用自启动（建议）",
            "忽略电池优化（建议）",
            "关闭省电模式（建议）"
        ).forEach { title ->
            composeRule.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithText("用于识别微信、支付宝支付通知").performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("用于识别支付结果页和支付记录").performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("避免系统关闭后台导致自动记账失效").performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "允许手机重启后恢复自动记账服务\n设置 → 应用设置 → 应用管理 → 自动记账"
        )
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("避免系统休眠导致自动记账中断").performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("避免省电策略限制后台自动记账").performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("后台保活和自启动受手机系统限制，本应用只提示你检查，不保证一定可靠。")
            .assertCountEquals(0)
    }

    @Test
    fun userCanEnableAndDisableAutomaticBookkeepingWithHealthyAccessibility() {
        var resultNotificationRequested = false
        composeRule.setContent {
            var state by remember { mutableStateOf(ContinuousMonitoringState()) }
            AutomaticBookkeepingScreen(
                billSyncAccessibilityAccessGranted = true,
                continuousMonitoringState = state,
                continuousMonitoringPermissionHealth = ContinuousMonitoringPermissionHealth(
                    billSyncAccessibilityGranted = true
                ),
                onRequestResultNotificationPermission = {
                    resultNotificationRequested = true
                },
                onContinuousMonitoringStateChange = { state = it }
            )
        }

        composeRule.onNodeWithText("开启自动记账").performClick()
        composeRule.onNodeWithText("关闭自动记账").assertIsDisplayed()
        assertEquals(true, resultNotificationRequested)
        composeRule.onNodeWithText("关闭自动记账").performClick()
        composeRule.onNodeWithText("状态：已关闭").assertIsDisplayed()
    }

    @Test
    fun enablingDoesNotRequestResultNotificationWhenAlreadyGranted() {
        var resultNotificationRequested = false
        composeRule.setContent {
            var state by remember { mutableStateOf(ContinuousMonitoringState()) }
            AutomaticBookkeepingScreen(
                resultNotificationPermissionGranted = true,
                billSyncAccessibilityAccessGranted = true,
                continuousMonitoringState = state,
                continuousMonitoringPermissionHealth = ContinuousMonitoringPermissionHealth(
                    billSyncAccessibilityGranted = true
                ),
                onRequestResultNotificationPermission = {
                    resultNotificationRequested = true
                },
                onContinuousMonitoringStateChange = { state = it }
            )
        }

        composeRule.onNodeWithText("开启自动记账").performClick()

        assertEquals(false, resultNotificationRequested)
    }

    @Test
    fun healthSummaryShowsDisconnectedAccessibilityService() {
        composeRule.setContent {
            AutomaticBookkeepingScreen(
                billSyncAccessibilityAccessGranted = true,
                continuousMonitoringState = ContinuousMonitoringState(enabled = true),
                continuousMonitoringPermissionHealth = ContinuousMonitoringPermissionHealth(
                    billSyncAccessibilityGranted = true,
                    billSyncAccessibilityServiceConnected = false
                )
            )
        }

        composeRule.onNodeWithText("持续监控需要处理").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("无障碍服务未连接").performScrollTo().assertIsDisplayed()
    }
}
