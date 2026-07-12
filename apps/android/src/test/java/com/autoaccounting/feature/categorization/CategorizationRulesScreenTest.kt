package com.autoaccounting.feature.categorization

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.autoaccounting.feature.account.AccountSession
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CategorizationRulesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun localModeShowsRulesAndLoginHintWithoutAiControls() {
        composeRule.setContent {
            CategorizationRulesScreen(accountSession = AccountSession.LocalMode)
        }

        composeRule.onNodeWithText("分类规则").assertIsDisplayed()
        composeRule.onNodeWithText("智能分类登录后可用；本地分类规则不受影响。").assertIsDisplayed()
        composeRule.onAllNodesWithText("开启云端 AI").assertCountEquals(0)
    }

    @Test
    fun signedInModeShowsOptionalAiConsentAndDisablesEnhancedContextByDefault() {
        composeRule.setContent {
            CategorizationRulesScreen(
                accountSession = AccountSession.SignedIn("13800138000", "token")
            )
        }

        composeRule.onNodeWithText("云端 AI 分类").assertIsDisplayed()
        composeRule.onNodeWithText("开启云端 AI").assertIsDisplayed()
        composeRule.onNodeWithText("提供更多上下文").assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun userCanCreateAndEditRule() {
        composeRule.setContent {
            CategorizationRulesScreen()
        }

        composeRule.onNodeWithText("分类规则").assertIsDisplayed()
        composeRule.onNodeWithText("新建规则").performClick()
        composeRule.onNodeWithText("商户包含").performTextInput("星巴克")
        composeRule.onNodeWithText("标题关键词").performTextInput("拿铁")
        composeRule.onNodeWithText("来源").performTextInput("微信")
        composeRule.onNodeWithText("交易类型").performTextInput("支出")
        composeRule.onNodeWithText("分类").performTextInput("餐饮")
        composeRule.onNodeWithText("保存规则").performClick()

        composeRule.onNodeWithText("星巴克").assertIsDisplayed()
        composeRule.onNodeWithText("餐饮").assertIsDisplayed()

        composeRule.onNodeWithText("编辑").performClick()
        composeRule.onNodeWithText("分类").performTextClearance()
        composeRule.onNodeWithText("分类").performTextInput("工作餐")
        composeRule.onNodeWithText("保存规则").performClick()

        composeRule.onNodeWithText("工作餐").assertIsDisplayed()
    }

    @Test
    fun userCanDeleteRule() {
        var updatedRules: List<CategorizationRule>? = null
        composeRule.setContent {
            CategorizationRulesScreen(
                rules = listOf(
                    CategorizationRule(
                        id = "rule-delete",
                        merchantContains = "coffee",
                        category = "food"
                    )
                ),
                onRulesChange = { updatedRules = it }
            )
        }

        composeRule.onNodeWithTag("delete-rule-rule-delete").performClick()

        assertTrue(updatedRules?.isEmpty() == true)
    }

    @Test
    fun signedInUserCanToggleCloudAiConsent() {
        var settings = AiCategorizationSettings()
        composeRule.setContent {
            CategorizationRulesScreen(
                aiSettings = settings,
                onAiSettingsChange = { settings = it },
                accountSession = AccountSession.SignedIn("13800138000", "token-1")
            )
        }

        composeRule.onNodeWithText("云端 AI 分类").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("开启云端 AI").performScrollTo().performClick()
        assertTrue(settings.aiConsentGranted)

        composeRule.onNodeWithText("提供更多上下文").performScrollTo().performClick()
        assertTrue(settings.enhancedContextGranted)
    }

}
