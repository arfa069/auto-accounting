package com.autoaccounting

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    private fun clearPersistedSession() {
        context.getSharedPreferences(
            LOCAL_MODE_SESSION_PREFERENCES,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }
}
