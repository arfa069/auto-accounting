package com.autoaccounting.feature.account

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun systemBackFollowsAccountPageHierarchy() {
        composeRule.setContent {
            AccountScreen(accountRepository = FakeAccountRepository())
        }

        composeRule.onNodeWithText("登录").performClick()
        composeRule.onNodeWithText("登录账号").assertIsDisplayed()
        pressSystemBack()
        composeRule.onNodeWithText("创建账号").assertIsDisplayed()

        composeRule.onNodeWithText("创建账号").performClick()
        composeRule.onNodeWithText("完成注册").performScrollTo().assertIsDisplayed()
        pressSystemBack()
        composeRule.onNodeWithText("继续使用本地模式").assertIsDisplayed()

        composeRule.onNodeWithText("登录").performClick()
        composeRule.onNodeWithText("忘记密码").performScrollTo().performClick()
        composeRule.onNodeWithText("重置密码").performScrollTo().assertIsDisplayed()
        pressSystemBack()
        composeRule.onNodeWithText("登录账号").assertIsDisplayed()
        pressSystemBack()
        composeRule.onNodeWithText("隐私与合规材料").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("隐私与合规材料").performScrollTo().performClick()
        composeRule.onNodeWithText("隐私政策").assertIsDisplayed()
        pressSystemBack()
        composeRule.onNodeWithText("继续使用本地模式").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("agreement-toggle").performScrollTo().performClick()
        composeRule.onNodeWithText("继续使用本地模式").performClick()
        composeRule.onNodeWithText("进入本地模式").assertIsDisplayed()
        pressSystemBack()
        composeRule.onNodeWithText("登录").assertIsDisplayed()
    }

    @Test
    fun agreementBlocksLocalModeEntryUntilChecked() {
        var session: AccountSession? = null
        composeRule.setContent {
            AccountScreen(
                accountRepository = FakeAccountRepository(),
                onSessionChange = { session = it }
            )
        }

        composeRule.onNodeWithText("继续使用本地模式").performClick()
        composeRule.onAllNodesWithText("请先阅读并同意用户协议和隐私政策").assertCountEquals(1)

        composeRule.onNodeWithTag("agreement-toggle").performScrollTo().performClick()
        composeRule.onNodeWithText("继续使用本地模式").performClick()
        composeRule.onNodeWithText("本地记账可用，但云端 AI、注册设备配置和未来同步不可用。").assertIsDisplayed()
        composeRule.onNodeWithText("进入本地模式").performClick()

        assert(session == AccountSession.LocalMode)
    }

    @Test
    fun registrationShowsErrorsAndSmsCountdown() {
        composeRule.setContent {
            AccountScreen(accountRepository = FakeAccountRepository())
        }

        composeRule.onNodeWithText("创建账号").performClick()
        composeRule.onNodeWithTag("account-phone").performTextInput("123")
        composeRule.onNodeWithText("获取验证码").performClick()
        composeRule.onNodeWithText("请输入 11 位手机号").assertIsDisplayed()

        composeRule.onNodeWithTag("account-phone").performTextClearance()
        composeRule.onNodeWithTag("account-phone").performTextInput("13800138000")
        composeRule.onNodeWithText("获取验证码").performClick()
        composeRule.onNodeWithText("60 秒后重试").assertIsDisplayed()

        composeRule.onNodeWithText("完成注册").performScrollTo().performClick()
        composeRule.onAllNodesWithText("请先阅读并同意用户协议和隐私政策").assertCountEquals(1)
    }

    @Test
    fun forgotPasswordFlowCanResetAgainstInjectedFakeRepository() {
        var session: AccountSession? = null
        composeRule.setContent {
            AccountScreen(
                accountRepository = FakeAccountRepository(),
                onSessionChange = { session = it }
            )
        }

        composeRule.onNodeWithText("登录").performClick()
        composeRule.onNodeWithText("忘记密码").performScrollTo().performClick()
        composeRule.onAllNodesWithText("找回密码").assertCountEquals(1)
        composeRule.onNodeWithTag("account-phone").performScrollTo().performTextInput("13800138000")
        composeRule.onNodeWithTag("account-code").performScrollTo().performTextInput("123456")
        composeRule.onNodeWithTag("account-password").performScrollTo().performTextInput("Aa123456!")
        composeRule.onNodeWithTag("account-confirm-password").performScrollTo().performTextInput("Aa123456!")
        composeRule.onNodeWithText("重置密码").performScrollTo().performClick()
        composeRule.waitForIdle()

        assert(session == AccountSession.SignedIn("13800138000", "mock-token"))
    }

    @Test
    fun complianceMaterialsAreReachableBeforeLogin() {
        composeRule.setContent {
            AccountScreen(accountRepository = FakeAccountRepository())
        }

        composeRule.onNodeWithText("隐私与合规材料").performScrollTo().performClick()

        composeRule.onNodeWithText("隐私政策").assertIsDisplayed()
        composeRule.onNodeWithText("个人信息收集清单").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sessionPersistenceFailureRevokesServerSessionAndDoesNotSignIn() {
        val repository = TestAccountRepository()
        var session: AccountSession? = null
        composeRule.setContent {
            AccountScreen(
                accountRepository = repository,
                persistSession = { false },
                onSessionChange = { session = it }
            )
        }

        composeRule.onNodeWithText("登录").performClick()
        composeRule.onNodeWithTag("account-phone").performTextInput("13800138000")
        composeRule.onNodeWithTag("account-password").performTextInput("Aa123456!")
        composeRule.onNodeWithTag("agreement-toggle").performScrollTo().performClick()
        composeRule.onNodeWithText("登录").performScrollTo().performClick()

        composeRule.waitUntil { repository.signOutCalls == 1 }
        composeRule.onNodeWithText("无法安全保存登录状态", substring = true).assertIsDisplayed()
        assert(session == null)
    }

    @Test
    fun failedSmsRequestDoesNotStartCountdown() {
        val repository = TestAccountRepository().apply {
            smsResult = AccountRepositoryResult.Failure(
                kind = AccountFailureKind.Network,
                message = "网络连接失败，请检查网络后重试"
            )
        }
        composeRule.setContent { AccountScreen(accountRepository = repository) }

        composeRule.onNodeWithText("创建账号").performClick()
        composeRule.onNodeWithTag("account-phone").performTextInput("13800138000")
        composeRule.onNodeWithText("获取验证码").performClick()

        composeRule.onNodeWithText("网络连接失败", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("60 秒后重试").assertCountEquals(0)
    }

    private fun pressSystemBack() {
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
    }
}
