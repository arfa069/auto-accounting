package com.autoaccounting.feature.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.autoaccounting.feature.account.AccountDeletionUiState
import com.autoaccounting.feature.account.AccountManagementScreen
import com.autoaccounting.feature.account.AccountRuntimeState
import com.autoaccounting.feature.account.AccountRuntimeStatus
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.account.FakeAccountRepository
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
        composeRule.waitForIdle()

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
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("profile-account-status-card").performClick()
        composeRule.waitForIdle()

        assertEquals(ProfileDestination.AccountManagement, destination)
    }

    @Test
    fun overviewRemainsReachableAcrossWindowWidthsAndLargeFonts() {
        var forcedSize by mutableStateOf(DpSize(400.dp, 500.dp))
        var fontScale by mutableFloatStateOf(1f)
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(forcedSize) then
                    DeviceConfigurationOverride.FontScale(fontScale)
            ) {
                ProfileOverviewScreen(
                    session = AccountSession.LocalMode,
                    onDestinationSelected = {}
                )
            }
        }
        composeRule.waitForIdle()

        listOf(
            TestConfiguration(DpSize(400.dp, 500.dp)),
            TestConfiguration(DpSize(610.dp, 500.dp)),
            TestConfiguration(DpSize(900.dp, 1_000.dp)),
            TestConfiguration(DpSize(400.dp, 500.dp), fontScale = 1.5f)
        ).forEach { configuration ->
            composeRule.runOnIdle {
                forcedSize = configuration.size
                fontScale = configuration.fontScale
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("profile-account-status-card")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("profile-entry-ComplianceAndPrivacy")
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun signedInAccountManagementKeepsSignOutSeparateFromAccountDeletion() {
        var signedOut = false
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn(phone = "13800138000"),
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.Verified),
                deletionState = AccountDeletionUiState(),
                accountRepository = FakeAccountRepository(),
                onSignInOrRegister = {},
                onSessionVerified = {},
                onInvalidSession = {},
                clearPersistedSession = { true },
                onSignedOut = { signedOut = true },
                onDeletionStateChange = {},
                onBack = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("退出登录").performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil { signedOut }
        assertTrue(signedOut)
        composeRule.onNodeWithText("申请注销账号").performScrollTo().assertIsDisplayed()
    }

    private data class TestConfiguration(
        val size: DpSize,
        val fontScale: Float = 1f
    )
}
