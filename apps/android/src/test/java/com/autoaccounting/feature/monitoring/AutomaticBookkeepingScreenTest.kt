package com.autoaccounting.feature.monitoring

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
    fun pageIsReachableButContainsNoAutomaticControls() {
        var backPressed = false
        composeRule.setContent {
            AutomaticBookkeepingScreen(onBack = { backPressed = true })
        }

        composeRule.onNodeWithTag("automatic-bookkeeping-page").assertIsDisplayed()
        composeRule.onNodeWithText("自动记账").assertIsDisplayed()
        composeRule.onNodeWithText("自动记账功能已移除").assertIsDisplayed()
        composeRule.onAllNodesWithText("开启自动记账").assertCountEquals(0)
        composeRule.onNodeWithText("返回").performClick()

        assertTrue(backPressed)
    }
}
