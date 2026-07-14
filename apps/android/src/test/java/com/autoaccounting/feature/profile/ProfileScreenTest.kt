package com.autoaccounting.feature.profile

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.autoaccounting.feature.account.AccountDeletionUiState
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
class ProfileScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun overviewShowsAccountCardAndFeatureEntries() {
        composeRule.setContent {
            ProfileOverviewScreen(
                session = AccountSession.LocalMode,
                onDestinationSelected = {}
            )
        }

        composeRule.onNodeWithTag("profile-account-status-card").assertIsDisplayed()
        composeRule.onNodeWithTag("profile-entry-AccountManagement").assertDoesNotExist()
        composeRule.onNodeWithTag("profile-entry-AutomaticBookkeeping").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("profile-entry-CategorizationRules").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("profile-entry-DataAndBackup").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("profile-entry-ComplianceAndPrivacy").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("进入").assertCountEquals(0)
    }

    @Test
    fun accountStatusCardOpensAccountManagement() {
        var destination: ProfileDestination? = null
        composeRule.setContent {
            ProfileOverviewScreen(
                session = AccountSession.SignedIn(phone = "13800138000"),
                onDestinationSelected = { destination = it }
            )
        }

        composeRule.onNodeWithTag("profile-account-status-card").performClick()

        assertEquals(ProfileDestination.AccountManagement, destination)
    }

    @Test
    fun signedInAccountManagementKeepsSignOutSeparateFromAccountDeletion() {
        var signedOut = false
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn(phone = "13800138000"),
                deletionState = AccountDeletionUiState(),
                onSignInOrRegister = {},
                onSignOut = { signedOut = true },
                onDeletionStateChange = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithText("退出登录").performClick()

        assertTrue(signedOut)
        composeRule.onNodeWithText("申请注销账号").assertIsDisplayed()
    }
}
