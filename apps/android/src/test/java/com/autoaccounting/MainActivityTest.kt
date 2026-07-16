package com.autoaccounting

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.autoaccounting.feature.account.LOCAL_MODE_SESSION_PREFERENCES
import com.autoaccounting.feature.account.LocalModeSessionStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun enterLocalMode() {
        clearPersistedSession()
        LocalModeSessionStore(context).confirmLocalMode()
    }

    @After
    fun clearSession() {
        clearPersistedSession()
    }

    @Test
    fun restoredSessionStartsOnHome() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
        composeRule.onNodeWithText("主页").assertIsDisplayed()
    }

    @Test
    fun centeredAddActionOpensManualEntryAndCancelReturnsHome() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        composeRule.onNodeWithTag("app-add-entry").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("新增一笔").assertIsDisplayed()
        composeRule.onNodeWithTag("app-bottom-navigation").assertIsDisplayed()

        composeRule.onNodeWithText("取消").performScrollTo().performClick()

        composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("app-bottom-navigation").assertIsDisplayed()
    }

    @Test
    fun firstLocalModeSelectionNavigatesToHome() {
        clearPersistedSession()
        composeRule.setContent {
            AutoAccountingApp()
        }

        composeRule.onNodeWithTag("agreement-toggle").performScrollTo().performClick()
        composeRule.onNodeWithText("继续使用本地模式").performClick()
        composeRule.onNodeWithText("进入本地模式").performClick()

        composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
    }

    @Test
    fun firstSignInNavigatesToHome() {
        clearPersistedSession()
        composeRule.setContent {
            AutoAccountingApp()
        }

        composeRule.onNodeWithText("登录").performClick()
        composeRule.onNodeWithTag("account-phone").performTextInput("13800138000")
        composeRule.onNodeWithTag("account-password").performTextInput("Aa123456!")
        composeRule.onNodeWithTag("agreement-toggle").performScrollTo().performClick()
        composeRule.onNodeWithText("登录").performScrollTo().performClick()

        composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
    }

    @Test
    fun eachPrimaryPageHidesBottomNavigationAndCanReturnHome() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        composeRule.onNodeWithTag("app-bottom-navigation").assertIsDisplayed()
        listOf("Review", "Ledger", "Reports", "Profile").forEach { tab ->
            composeRule.onNodeWithTag("app-tab-$tab").performClick()
            composeRule.onNodeWithTag("app-bottom-navigation").assertDoesNotExist()
            composeRule.onNodeWithTag("return-home").assertIsDisplayed().performClick()
            composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
            composeRule.onNodeWithTag("app-bottom-navigation").assertIsDisplayed()
        }
    }

    @Test
    fun systemBackFromEachPrimaryPageReturnsHome() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        listOf("Review", "Ledger", "Reports", "Profile").forEach { tab ->
            composeRule.onNodeWithTag("app-tab-$tab").performClick()

            composeRule.runOnIdle {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }

            composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
        }
    }

    @Test
    fun reviewNavigationRequestStillOpensReviewQueue() {
        composeRule.setContent {
            AutoAccountingApp(reviewNavigationRequest = 1L)
        }

        composeRule.onNodeWithText("待确认队列").assertIsDisplayed()
    }

    @Test
    fun systemBackFromAccountManagementReturnsToProfileOverview() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        composeRule.onNodeWithTag("app-tab-Profile").performClick()
        composeRule.onNodeWithTag("profile-account-status-card").performClick()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("profile-account-status-card").assertIsDisplayed()
    }

    @Test
    fun automaticBookkeepingEntryOpensItsDedicatedPage() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        composeRule.onNodeWithTag("app-tab-Profile").performClick()
        composeRule.onNodeWithTag("profile-entry-AutomaticBookkeeping").performClick()

        composeRule.onNodeWithText("自动记账状态")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun systemBackFromAutomaticBookkeepingReturnsToProfileOverview() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        composeRule.onNodeWithTag("app-tab-Profile").performClick()
        composeRule.onNodeWithTag("profile-entry-AutomaticBookkeeping").performClick()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("profile-entry-AutomaticBookkeeping").assertIsDisplayed()
    }

    @Test
    fun categorizationRulesEntryOpensDedicatedPage() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        composeRule.onNodeWithTag("app-tab-Profile").performClick()
        composeRule.onNodeWithTag("profile-entry-CategorizationRules")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText("新建规则").assertExists()
    }

    @Test
    fun systemBackFromCategorizationRulesReturnsToProfileOverview() {
        composeRule.setContent {
            AutoAccountingApp()
        }

        composeRule.onNodeWithTag("app-tab-Profile").performClick()
        composeRule.onNodeWithTag("profile-entry-CategorizationRules")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("profile-entry-CategorizationRules")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun dataAndBackupEntryOpensDedicatedPage() {
        composeRule.setContent { AutoAccountingApp() }

        composeRule.onNodeWithTag("app-tab-Profile").performClick()
        composeRule.onNodeWithTag("profile-entry-DataAndBackup")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText("导出与恢复").assertIsDisplayed()
        composeRule.onNodeWithText("危险区").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun systemBackFromDataAndBackupReturnsToProfileOverview() {
        composeRule.setContent { AutoAccountingApp() }

        composeRule.onNodeWithTag("app-tab-Profile").performClick()
        composeRule.onNodeWithTag("profile-entry-DataAndBackup")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("profile-entry-DataAndBackup")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun complianceAndPrivacyEntryOpensDedicatedPageWithDebugTools() {
        composeRule.setContent { AutoAccountingApp() }

        composeRule.onNodeWithTag("app-tab-Profile").performClick()
        composeRule.onNodeWithTag("profile-entry-ComplianceAndPrivacy")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText("合规与隐私").assertIsDisplayed()
        composeRule.onNodeWithTag("compliance-entry-PrivacyPolicy").assertIsDisplayed()
        composeRule.onNodeWithTag("developer-tools-entry").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun systemBackFromComplianceAndPrivacyReturnsToProfileOverview() {
        composeRule.setContent { AutoAccountingApp() }

        composeRule.onNodeWithTag("app-tab-Profile").performClick()
        composeRule.onNodeWithTag("profile-entry-ComplianceAndPrivacy")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("profile-entry-ComplianceAndPrivacy")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun clearPersistedSession() {
        context.getSharedPreferences(
            LOCAL_MODE_SESSION_PREFERENCES,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }
}
