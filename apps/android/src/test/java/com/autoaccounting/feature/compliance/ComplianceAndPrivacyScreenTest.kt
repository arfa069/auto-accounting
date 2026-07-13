package com.autoaccounting.feature.compliance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComplianceAndPrivacyScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun localModeCanOpenEachUserFacingMaterialWithoutStoreReviewNotes() {
        composeRule.setContent {
            ComplianceAndPrivacyScreen(isDebugBuild = false, onBack = {})
        }
        composeRule.onAllNodesWithText("进入").assertCountEquals(0)
        ComplianceMaterialPage.entries.forEach { page ->
            composeRule.onNodeWithTag("compliance-entry-${page.name}")
                .performScrollTo()
                .performClick()
            composeRule.onNodeWithText(page.title).assertIsDisplayed()
            composeRule.onAllNodesWithText("商店审核说明").assertCountEquals(0)
            composeRule.onNodeWithText("返回").performClick()
        }
    }

    @Test
    fun releaseModeDoesNotExposeDeveloperTools() {
        composeRule.setContent {
            ComplianceAndPrivacyScreen(isDebugBuild = false, onBack = {})
        }

        composeRule.onAllNodesWithText("开发者工具").assertCountEquals(0)
        composeRule.onAllNodesWithText("设备矩阵测试").assertCountEquals(0)
    }

    @Test
    fun debugModeExposesDeveloperToolsWithoutHiddenGesture() {
        composeRule.setContent {
            ComplianceAndPrivacyScreen(isDebugBuild = true, onBack = {})
        }

        composeRule.onNodeWithTag("developer-tools-entry").performScrollTo().performClick()
        composeRule.onNodeWithText("内测准备检查").assertIsDisplayed()
        composeRule.onAllNodesWithText("设备矩阵测试")[0].assertIsDisplayed()
    }

    @Test
    fun systemBackFromMaterialReturnsToComplianceOverview() {
        composeRule.setContent {
            ComplianceAndPrivacyScreen(isDebugBuild = false, onBack = {})
        }
        composeRule.onNodeWithTag("compliance-entry-PrivacyPolicy").performClick()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("compliance-entry-PrivacyPolicy").assertIsDisplayed()
    }
}
