package com.autoaccounting.feature.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountWechatUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun missingGatewayHidesWechatEntry() {
        composeRule.setContent {
            AccountScreen(
                accountRepository = TestAccountRepository(),
                onSessionChange = {}
            )
        }

        composeRule.onNodeWithTag("wechat-login-entry").assertDoesNotExist()
        composeRule.onNodeWithText("登录").assertIsDisplayed()
        composeRule.onNodeWithText("创建账号").assertIsDisplayed()
    }

    @Test
    fun landingWechatEntryPrecedesPhoneActionsAndAgreementGatesGateway() {
        val gateway = RecordingGateway()
        composeRule.setContent {
            AccountScreen(
                accountRepository = TestAccountRepository(),
                wechatAuthGateway = gateway,
                onSessionChange = {}
            )
        }

        val wechatTop = composeRule.onNodeWithTag("wechat-login-entry")
            .fetchSemanticsNode().boundsInRoot.top
        val loginTop = composeRule.onNodeWithText("登录")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(wechatTop < loginTop)

        composeRule.onNodeWithTag("wechat-login-entry").performClick()
        composeRule.waitForIdle()
        assertEquals(0, gateway.calls)
        composeRule.onNodeWithText("请先阅读并同意用户协议和隐私政策").assertIsDisplayed()

        composeRule.onNodeWithTag("agreement-toggle").performClick()
        composeRule.onNodeWithTag("wechat-login-entry").performClick()
        composeRule.waitForIdle()
        assertEquals(1, gateway.calls)
    }

    @Test
    fun authorizedCallbackShowsProfilePreviewAndWechatBackHierarchy() {
        var consumed = 0
        composeRule.setContent {
            AccountScreen(
                accountRepository = TestAccountRepository(),
                wechatAuthGateway = RecordingGateway(),
                wechatAuthCallback = WechatAuthCallback.Authorized(
                    "one-time-code",
                    WechatAuthPurpose.SignInOrRegister
                ),
                onWechatAuthCallbackConsumed = { consumed += 1 },
                onSessionChange = {}
            )
        }
        composeRule.waitForIdle()

        assertEquals(1, consumed)
        composeRule.onNodeWithText("确认微信资料").assertIsDisplayed()
        composeRule.onNodeWithText("微信用户").assertIsDisplayed()
        composeRule.onNodeWithText("创建微信账号").assertIsDisplayed()
        composeRule.onNodeWithText("绑定已有账号").performClick()
        composeRule.onNodeWithText("绑定已有手机号账号").assertIsDisplayed()

        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("确认微信资料").assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("登录").assertIsDisplayed()
    }

    @Test
    fun identityPanelShowsMaskedLoginMethodsAndAvailableActions() {
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn(
                    phone = "13800138000",
                    token = "token",
                    wechatLinked = true,
                    nickname = "微信小张"
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

        composeRule.onNodeWithText("微信小张").assertIsDisplayed()
        composeRule.onNodeWithText("手机号：138****8000").assertIsDisplayed()
        composeRule.onNodeWithText("手机号登录").assertIsDisplayed()
        composeRule.onNodeWithText("微信登录").assertIsDisplayed()
        composeRule.onNodeWithTag("unlink-wechat").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("bind-phone").assertDoesNotExist()
    }

    @Test
    fun pureWechatAccountCanAttachNewPhoneAndSetPassword() {
        val repository = TestAccountRepository()
        var verified: AccountCredentials? = null
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn(null, "token", wechatLinked = true, nickname = "微信用户"),
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.Verified),
                deletionState = AccountDeletionUiState(),
                accountRepository = repository,
                onSignInOrRegister = {},
                onSessionVerified = { verified = it },
                onInvalidSession = {},
                clearPersistedSession = { true },
                onSignedOut = {},
                onDeletionStateChange = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithTag("bind-phone").performScrollTo().performClick()
        composeRule.onNodeWithTag("identity-phone").performTextInput("13800138000")
        composeRule.onNodeWithTag("identity-code").performTextInput("123456")
        composeRule.onNodeWithText("继续").performClick()
        composeRule.waitUntil { repository.preparePhoneLinkCalls == 1 }
        composeRule.onNodeWithText("设置手机号登录密码").assertIsDisplayed()
        composeRule.onNodeWithTag("phone-link-password").performTextInput("Aa123456!")
        composeRule.onNodeWithText("完成绑定").performClick()
        composeRule.waitUntil { repository.completePhoneLinkCalls == 1 }

        assertEquals("token-1", verified?.token)
        composeRule.onNodeWithTag("unlink-wechat").assertDoesNotExist()
    }

    @Test
    fun passwordMergeRequiresExactConfirmationAndExplainsDataRules() {
        val repository = TestAccountRepository()
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn(null, "token", wechatLinked = true, nickname = "当前微信"),
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.Verified),
                deletionState = AccountDeletionUiState(),
                accountRepository = repository,
                onSignInOrRegister = {},
                onSessionVerified = {},
                onInvalidSession = {},
                clearPersistedSession = { true },
                onSignedOut = {},
                onDeletionStateChange = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithTag("bind-phone").performScrollTo().performClick()
        composeRule.onNodeWithText("密码合并").performClick()
        composeRule.onNodeWithTag("identity-phone").performTextInput("13800138000")
        composeRule.onNodeWithTag("identity-password").performTextInput("Aa123456!")
        composeRule.onNodeWithText("继续").performClick()
        composeRule.waitUntil { repository.prepareMergeCalls == 1 }

        composeRule.onNodeWithText("来源 AI 日志将删除", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("本机账本不变", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("confirm-account-merge").assertIsNotEnabled()
        composeRule.onNodeWithTag("merge-confirm-text").performTextInput("合并账号")
        composeRule.onNodeWithTag("confirm-account-merge").performClick()
        composeRule.waitUntil { repository.confirmMergeCalls == 1 }
    }

    @Test
    fun unlinkSupportsPasswordAndSmsVerification() {
        val repository = TestAccountRepository()
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn("13800138000", "token", wechatLinked = true),
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.Verified),
                deletionState = AccountDeletionUiState(),
                accountRepository = repository,
                onSignInOrRegister = {},
                onSessionVerified = {},
                onInvalidSession = {},
                clearPersistedSession = { true },
                onSignedOut = {},
                onDeletionStateChange = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithTag("unlink-wechat").performScrollTo().performClick()
        composeRule.onNodeWithText("仍可使用当前手机号登录", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("unlink-password").performTextInput("Aa123456!")
        composeRule.onNodeWithTag("confirm-unlink-wechat").performClick()
        composeRule.waitUntil { repository.unlinkWechatWithPasswordCalls == 1 }

        composeRule.onNodeWithTag("unlink-wechat").performScrollTo().performClick()
        composeRule.onNodeWithText("短信验证").performClick()
        composeRule.onNodeWithText("获取验证码").performClick()
        composeRule.waitUntil { repository.smsCalls == 1 }
        assertEquals(AccountSmsPurpose.WechatUnlink, repository.lastSmsPurpose)
        composeRule.onNodeWithTag("unlink-code").performTextInput("123456")
        composeRule.onNodeWithTag("confirm-unlink-wechat").performClick()
        composeRule.waitUntil { repository.unlinkWechatWithSmsCalls == 1 }
    }

    @Test
    fun linkCallbackFromAnotherSessionIsRejectedBeforeExchange() {
        val repository = TestAccountRepository()
        var consumed = 0
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn("13800138000", "session-b"),
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.Verified),
                deletionState = AccountDeletionUiState(),
                accountRepository = repository,
                onSignInOrRegister = {},
                onSessionVerified = {},
                onInvalidSession = {},
                clearPersistedSession = { true },
                wechatAuthCallback = WechatAuthCallback.Authorized(
                    code = "one-time-code",
                    purpose = WechatAuthPurpose.LinkCurrentAccount,
                    sessionFingerprint = wechatSessionFingerprint("session-a")
                ),
                onWechatAuthCallbackConsumed = { consumed += 1 },
                onSignedOut = {},
                onDeletionStateChange = {},
                onBack = {}
            )
        }
        composeRule.waitForIdle()

        assertEquals(1, consumed)
        assertEquals(0, repository.exchangeWechatCalls)
        composeRule.onNodeWithText("登录状态已变化", substring = true).assertIsDisplayed()
    }

    @Test
    fun protectedWechatFailureClearsInvalidSession() {
        val repository = TestAccountRepository().apply {
            unlinkWechatWithPasswordResult = AccountRepositoryResult.Failure(
                kind = AccountFailureKind.InvalidSession,
                message = "登录状态已失效"
            )
        }
        var invalidSessionCalls = 0
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn("13800138000", "token", wechatLinked = true),
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.Verified),
                deletionState = AccountDeletionUiState(),
                accountRepository = repository,
                onSignInOrRegister = {},
                onSessionVerified = {},
                onInvalidSession = { invalidSessionCalls += 1 },
                clearPersistedSession = { true },
                onSignedOut = {},
                onDeletionStateChange = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithTag("unlink-wechat").performScrollTo().performClick()
        composeRule.onNodeWithTag("unlink-password").performTextInput("Wrong123!")
        composeRule.onNodeWithTag("confirm-unlink-wechat").performClick()
        composeRule.waitUntil { invalidSessionCalls == 1 }

        assertEquals(1, repository.unlinkWechatWithPasswordCalls)
    }

    private class RecordingGateway : WechatAuthGateway {
        var calls = 0

        override fun startAuthorization(
            purpose: WechatAuthPurpose,
            sessionFingerprint: String?
        ): WechatAuthLaunchResult {
            calls += 1
            return WechatAuthLaunchResult.Started
        }
    }
}
