package com.autoaccounting.feature.account

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.autoaccounting.api.AccountIdentifierContract
import com.autoaccounting.api.AccountIdentifierTypeContract
import org.junit.Assert.assertEquals
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
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("account-sign-out").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("网络连接失败", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        assertFalse(cleared)
        assertFalse(signedOut)
        composeRule.onNodeWithTag("account-sign-out").assertIsDisplayed()
        composeRule.onNodeWithText("重新验证").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun verifiedUsernameAccountShowsUsernameOnceWithoutConnectionCard() {
        val username = AccountIdentifierContract(
            type = AccountIdentifierTypeContract.USERNAME,
            value = "admin069"
        )
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn(
                    primaryIdentifier = username,
                    identifiers = listOf(username),
                    token = "token-1"
                ),
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.Verified),
                deletionState = AccountDeletionUiState(),
                accountRepository = TestAccountRepository(),
                onSignInOrRegister = {},
                onSessionVerified = {},
                onInvalidSession = {},
                clearPersistedSession = { true },
                onSignedOut = {},
                onDeletionStateChange = {},
                onBack = {}
            )
        }

        composeRule.onAllNodesWithText("admin069", substring = true).assertCountEquals(1)
        composeRule.onNodeWithText("用户名登录").assertDoesNotExist()
        composeRule.onNodeWithTag("account-connection-status").assertDoesNotExist()
        composeRule.onNodeWithTag("bind-phone").assertIsDisplayed()
    }

    @Test
    fun successfulRetryPersistsLatestAvatarForOfflineRestore() {
        val refreshed = AccountCredentials(
            phone = "13800138000",
            token = "token-1",
            nickname = "本机头像用户",
            avatarUrl = "data:image/jpeg;base64,/9j/"
        )
        val repository = TestAccountRepository().apply {
            verificationResult = AccountRepositoryResult.Success(refreshed)
        }
        var persisted: AccountCredentials? = null
        var verified: AccountCredentials? = null
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn("13800138000", "token-1"),
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.OfflineUnverified),
                deletionState = AccountDeletionUiState(),
                accountRepository = repository,
                onSignInOrRegister = {},
                onSessionVerified = { verified = it },
                onInvalidSession = {},
                persistSession = {
                    persisted = it
                    true
                },
                clearPersistedSession = { true },
                onSignedOut = {},
                onDeletionStateChange = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithText("重新验证").performClick()
        composeRule.waitUntil { persisted != null && verified != null }

        assertEquals(refreshed, persisted)
        assertEquals(refreshed, verified)
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
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("request-account-deletion").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("七天冷静期", substring = true).assertIsDisplayed()
        assertTrue(repository.requestDeletionCalls == 0)
        composeRule.onNodeWithTag("confirm-account-deletion").performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil { repository.requestDeletionCalls == 1 }
        assertTrue(deletionState.isPending)
        assertTrue(deletionState.finalDeletionAtEpochMillis == 604_801_000L)
    }
}
