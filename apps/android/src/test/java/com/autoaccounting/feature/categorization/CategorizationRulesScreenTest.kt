package com.autoaccounting.feature.categorization

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.autoaccounting.feature.account.AccountSession
import org.junit.Assert.assertEquals
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
        composeRule.onNodeWithText("登录后可以使用智能分类；本地规则照常生效。").assertIsDisplayed()
        composeRule.onNodeWithTag("ai-consent-switch").assertDoesNotExist()
    }

    @Test
    fun ruleRowDoesNotRepeatCategoryAsSubtitle() {
        composeRule.setContent {
            CategorizationRulesScreen(
                rules = listOf(
                    CategorizationRule(
                        id = "refund-rule",
                        merchantContains = "退款",
                        category = "退款"
                    )
                ),
                onRulesChange = {}
            )
        }

        composeRule.onAllNodesWithText("退款").assertCountEquals(1)
        composeRule.onNodeWithText("商户包含“退款”").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("建议为 退款").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun signedInModeShowsOptionalAiConsentAndDisablesEnhancedContextByDefault() {
        composeRule.setContent {
            CategorizationRulesScreen(
                accountSession = AccountSession.SignedIn("13800138000", "token")
            )
        }

        composeRule.onNodeWithText("智能分类").assertIsDisplayed()
        composeRule.onNodeWithText("试用").assertIsDisplayed()
        composeRule.onNodeWithTag("ai-consent-switch").assertIsOff()
        composeRule.onNodeWithTag("enhanced-context-switch").assertIsNotEnabled()
    }


    @Test
    fun userCanCreateAndEditRule() {
        var rules by mutableStateOf(emptyList<CategorizationRule>())
        composeRule.setContent {
            CategorizationRulesScreen(
                rules = rules,
                onRulesChange = { rules = it }
            )
        }

        composeRule.onNodeWithText("分类规则").assertIsDisplayed()
        composeRule.onNodeWithTag("create-rule").performClick()
        composeRule.onNodeWithText("商户包含").performTextInput("星巴克")
        composeRule.onNodeWithText("标题关键词").performTextInput("拿铁")
        composeRule.onNodeWithText("来源").performTextInput("微信")
        composeRule.onNodeWithText("交易类型").performTextInput("支出")
        composeRule.onNodeWithText("分类").performTextInput("餐饮")
        composeRule.onNodeWithText("保存规则").performClick()

        composeRule.onNodeWithText("星巴克").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("餐饮", rules.single().category)
        }

        composeRule.onNodeWithTag("rule-menu-rule-1").performClick()
        composeRule.onNodeWithTag("edit-rule-rule-1").performClick()
        composeRule.onNodeWithText("分类").performTextClearance()
        composeRule.onNodeWithText("分类").performTextInput("工作餐")
        composeRule.onNodeWithText("保存规则").performClick()

        composeRule.runOnIdle {
            assertEquals("工作餐", rules.single().category)
        }
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

        composeRule.onNodeWithTag("rule-menu-rule-delete").performClick()
        composeRule.onNodeWithTag("delete-rule-rule-delete").performClick()

        assertTrue(updatedRules?.isEmpty() == true)
    }

    @Test
    fun signedInUserCanToggleCloudAiConsent() {
        var settings by mutableStateOf(AiCategorizationSettings())
        composeRule.setContent {
            CategorizationRulesScreen(
                aiSettings = settings,
                onAiSettingsChange = { settings = it },
                accountSession = AccountSession.SignedIn("13800138000", "token-1")
            )
        }

        composeRule.onNodeWithTag("ai-consent-switch").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(settings.aiConsentGranted)

        composeRule.onNodeWithTag("enhanced-context-switch").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(settings.enhancedContextGranted)
    }

    @Test
    fun cloudAiSwitchesAreDisabledWhileSettingsSynchronize() {
        composeRule.setContent {
            CategorizationRulesScreen(
                aiSettings = AiCategorizationSettings(aiConsentGranted = true),
                aiSettingsSyncInFlight = true,
                accountSession = AccountSession.SignedIn("13800138000", "token-1")
            )
        }

        composeRule.onNodeWithTag("ai-settings-syncing").assertIsDisplayed()
        composeRule.onNodeWithTag("ai-consent-switch").assertIsNotEnabled()
        composeRule.onNodeWithTag("enhanced-context-switch").assertIsNotEnabled()
    }

    @Test
    fun ruleFiltersShowOnlyMatchingTransactionKinds() {
        composeRule.setContent {
            CategorizationRulesScreen(
                rules = listOf(
                    CategorizationRule(id = "expense", transactionKind = "支出", category = "餐饮"),
                    CategorizationRule(id = "income", transactionKind = "收入", category = "工资"),
                    CategorizationRule(id = "other", transactionKind = "退款", category = "退款")
                ),
                onRulesChange = {}
            )
        }

        composeRule.onNodeWithTag("rule-filter-Expense").performClick()

        composeRule.onNodeWithTag("rule-card-expense").assertIsDisplayed()
        composeRule.onNodeWithTag("rule-card-income").assertDoesNotExist()
        composeRule.onNodeWithTag("rule-card-other").assertDoesNotExist()
    }

}
