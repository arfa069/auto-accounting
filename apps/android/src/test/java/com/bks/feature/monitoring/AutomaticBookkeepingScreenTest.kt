package com.bks.feature.monitoring

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
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
    fun enabledWithoutPermissionKeepsIntentAndOffersSettings() {
        var disabled = false
        var settingsOpened = false
        composeRule.setContent {
            AutomaticBookkeepingScreen(
                enabled = true,
                accessibilityAccessGranted = false,
                onEnabledChange = { disabled = !it },
                onOpenAccessibilitySettings = { settingsOpened = true }
            )
        }

        composeRule.onNodeWithText("等待无障碍授权").assertIsDisplayed()
        composeRule.onNodeWithText("打开无障碍设置").performClick()
        composeRule.onNodeWithTag("automatic-bookkeeping-toggle").performClick()

        assertTrue(settingsOpened)
        assertTrue(disabled)
    }

    @Test
    fun connectedStateShowsListeningAndPrivacyBoundary() {
        composeRule.setContent {
            AutomaticBookkeepingScreen(
                enabled = true,
                accessibilityAccessGranted = true,
                accessibilityServiceConnected = true
            )
        }

        composeRule.onNodeWithText("正在监听").assertIsDisplayed()
        composeRule.onNodeWithText("不会截图、不会操作其他应用、不会保存或上传原始页面文字。")
            .assertIsDisplayed()
    }

    @Test
    fun disabledAndDisconnectedStatesRemainDistinct() {
        val enabled = mutableStateOf(false)
        composeRule.setContent {
            AutomaticBookkeepingScreen(
                enabled = enabled.value,
                accessibilityAccessGranted = true,
                accessibilityServiceConnected = false
            )
        }
        composeRule.onNodeWithText("已关闭").assertIsDisplayed()
        composeRule.onNodeWithText("管理无障碍授权").assertIsDisplayed()

        composeRule.runOnIdle { enabled.value = true }
        composeRule.onNodeWithText("等待服务连接").assertIsDisplayed()
    }
}
