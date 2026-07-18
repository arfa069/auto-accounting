package com.autoaccounting.feature.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountManagementScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun signOutNetworkFailureKeepsLocalSessionAndAllowsRetry() {
        val repository = TestAccountRepository().apply {
            signOutResult = AccountRepositoryResult.Failure(
                AccountFailureKind.Network,
                "网络连接失败，请检查网络后重试"
            )
        }
        var cleared = false
        var signedOut = false
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn("13800138000", "token-1"),
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.OfflineUnverified),
                deletionState = AccountDeletionUiState(),
                accountRepository = repository,
                onSignInOrRegister = {},
                onSessionVerified = {},
                onInvalidSession = {},
                clearPersistedSession = { cleared = true; true },
                onSignedOut = { signedOut = true },
                onDeletionStateChange = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithTag("account-sign-out").performClick()

        composeRule.onNodeWithText("网络连接失败", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        assertFalse(cleared)
        assertFalse(signedOut)
        composeRule.onNodeWithTag("account-sign-out").assertIsDisplayed()
    }

    @Test
    fun deletionRequiresConfirmationAndUsesServerDeadline() {
        val repository = TestAccountRepository()
        var deletionState = AccountDeletionUiState()
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn("13800138000", "token-1"),
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.Verified),
                deletionState = deletionState,
                accountRepository = repository,
                onSignInOrRegister = {},
                onSessionVerified = {},
                onInvalidSession = {},
                clearPersistedSession = { true },
                onSignedOut = {},
                onDeletionStateChange = { deletionState = it },
                onBack = {}
            )
        }

        composeRule.onNodeWithTag("request-account-deletion").performScrollTo().performClick()
        composeRule.onNodeWithText("七天冷静期", substring = true).assertIsDisplayed()
        assertTrue(repository.requestDeletionCalls == 0)
        composeRule.onNodeWithTag("confirm-account-deletion").performClick()

        composeRule.waitUntil { repository.requestDeletionCalls == 1 }
        assertTrue(deletionState.isPending)
        assertTrue(deletionState.finalDeletionAtEpochMillis == 604_801_000L)
    }
}
