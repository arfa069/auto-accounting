package com.bks.feature.account

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
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
    fun missingGatewayDisablesWechatBindingAction() {
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn("13800138000", "token"),
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

        composeRule.onNodeWithTag("bind-wechat").performScrollTo().assertIsNotEnabled()
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
        composeRule.onNodeWithText("绑定已有账号").assertIsDisplayed()

        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("确认微信资料").assertIsDisplayed()
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("登录").assertIsDisplayed()
    }

    @Test
    fun identityPanelShowsSingleDisplayNameAndAvailableActions() {
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn(
                    accountId = 42,
                    rawPhone = "13800138000",
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
        composeRule.onNodeWithText("手机号：138****8000").assertDoesNotExist()
        composeRule.onNodeWithText("手机号登录").assertDoesNotExist()
        composeRule.onNodeWithText("微信登录").assertDoesNotExist()
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
        composeRule.onNodeWithText("获取验证码").performClick()
        composeRule.waitUntil { repository.prepareIdentifierLinkCalls == 1 }
        composeRule.onNodeWithTag("identity-code").performTextInput("123456")
        composeRule.onNodeWithText("继续").performClick()
        composeRule.onNodeWithText("设置登录密码").assertIsDisplayed()
        composeRule.onNodeWithTag("phone-link-password").performTextInput("Aa123456!")
        composeRule.onNodeWithText("完成绑定").performClick()
        composeRule.waitUntil { repository.completeIdentifierLinkCalls == 1 }

        assertEquals("token-1", verified?.token)
        assertEquals("Aa123456!", repository.lastCompleteIdentifierPassword)
        composeRule.onNodeWithTag("unlink-wechat").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun delayedIdentifierSmsRequestKeepsLatestIdentifierAndDropsOldTicket() {
        val repository = TestAccountRepository().apply {
            prepareIdentifierLinkGate = CompletableDeferred()
        }
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn(null, "token", wechatLinked = true),
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
        composeRule.onNodeWithTag("identity-phone").performTextInput("13800138000")
        composeRule.onNodeWithText("获取验证码").performClick()
        composeRule.waitUntil { repository.prepareIdentifierLinkCalls == 1 }

        composeRule.onNodeWithTag("identity-phone").performTextReplacement("13900139000")
        repository.prepareIdentifierLinkGate!!.complete(Unit)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("identity-phone").assertTextContains("13900139000")
        composeRule.onNodeWithText("继续").performClick()
        composeRule.waitForIdle()
        assertEquals(0, repository.completeIdentifierLinkCalls)
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
        composeRule.onNodeWithText("绑定已有账号").performClick()
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
        composeRule.onNodeWithText("仍可使用已绑定账号登录", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("unlink-password").performTextInput("Aa123456!")
        composeRule.onNodeWithTag("confirm-unlink-wechat").performClick()
        composeRule.waitUntil { repository.unlinkWechatWithPasswordCalls == 1 }

        composeRule.onNodeWithTag("unlink-wechat").performScrollTo().performClick()
        composeRule.onNodeWithText("验证码验证").performClick()
        composeRule.onNodeWithText("获取验证码").performClick()
        composeRule.waitUntil { repository.smsCalls == 1 }
        assertEquals(AccountVerificationPurpose.WechatUnlink, repository.lastSmsPurpose)
        composeRule.onNodeWithTag("unlink-code").performTextInput("123456")
        composeRule.onNodeWithTag("confirm-unlink-wechat").performClick()
        composeRule.waitUntil { repository.unlinkWechatWithCodeCalls == 1 }
        assertEquals("13800138000", repository.lastUnlinkWechatIdentifier)
    }

    @Test
    fun unlinkCodeCanSelectEmailWhenPhoneAndEmailAreBound() {
        val repository = TestAccountRepository()
        val phone = com.bks.api.AccountIdentifierContract(
            com.bks.api.AccountIdentifierTypeContract.PHONE,
            "13800138000"
        )
        val email = com.bks.api.AccountIdentifierContract(
            com.bks.api.AccountIdentifierTypeContract.EMAIL,
            "user@example.com"
        )
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn(
                    primaryIdentifier = phone,
                    identifiers = listOf(phone, email),
                    token = "token",
                    wechatLinked = true
                ),
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
        composeRule.onNodeWithText("验证码验证").performClick()
        composeRule.onNodeWithText("邮箱验证码").performClick()
        composeRule.onNodeWithText("获取验证码").performClick()
        composeRule.waitUntil { repository.smsCalls == 1 }
        assertEquals("user@example.com", repository.lastSmsIdentifier)
        composeRule.onNodeWithTag("unlink-code").performTextInput("123456")
        composeRule.onNodeWithTag("confirm-unlink-wechat").performClick()
        composeRule.waitUntil { repository.unlinkWechatWithCodeCalls == 1 }
        assertEquals("user@example.com", repository.lastUnlinkWechatIdentifier)
    }

    @Test
    fun delayedUnlinkSmsRequestKeepsLatestIdentifierAndCode() {
        val phone = com.bks.api.AccountIdentifierContract(
            com.bks.api.AccountIdentifierTypeContract.PHONE,
            "13800138000"
        )
        val email = com.bks.api.AccountIdentifierContract(
            com.bks.api.AccountIdentifierTypeContract.EMAIL,
            "user@example.com"
        )
        val repository = TestAccountRepository().apply {
            smsGate = CompletableDeferred()
        }
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn(
                    primaryIdentifier = phone,
                    identifiers = listOf(phone, email),
                    token = "token",
                    wechatLinked = true
                ),
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
        composeRule.onNodeWithText("验证码验证").performClick()
        composeRule.onNodeWithText("获取验证码").performClick()
        composeRule.waitUntil { repository.smsCalls == 1 }

        composeRule.onNodeWithText("邮箱验证码").performClick()
        composeRule.onNodeWithTag("unlink-code").performTextInput("654321")
        repository.smsGate!!.complete(AccountRepositoryResult.Success(Unit))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("获取验证码").assertIsEnabled()

        composeRule.onNodeWithTag("confirm-unlink-wechat").performClick()
        composeRule.waitUntil { repository.unlinkWechatWithCodeCalls == 1 }

        assertEquals("user@example.com", repository.lastUnlinkWechatIdentifier)
        assertEquals("654321", repository.lastUnlinkWechatCode)
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
        composeRule.onNodeWithText("登录状态已变化", substring = true).performScrollTo().assertIsDisplayed()
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

    @Test
    fun editNicknameUpdatesSession() {
        val repository = TestAccountRepository()
        val deletionState = AccountDeletionUiState(1_000, 604_801_000)
        var updatedCredentials: AccountCredentials? = null
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn(
                    accountId = 42,
                    rawPhone = "13800138000",
                    token = "token",
                    wechatLinked = false,
                    nickname = "旧昵称"
                ),
                runtimeState = AccountRuntimeState(AccountRuntimeStatus.DeletionCoolingOff),
                deletionState = deletionState,
                accountRepository = repository,
                onSignInOrRegister = {},
                onSessionVerified = { credentials -> updatedCredentials = credentials },
                onInvalidSession = {},
                clearPersistedSession = { true },
                onSignedOut = {},
                onDeletionStateChange = {},
                onBack = {}
            )
        }

        composeRule.onNodeWithTag("btn-edit-nickname").performScrollTo().performClick()
        composeRule.onNodeWithText("修改昵称").assertIsDisplayed()
        composeRule.onNodeWithTag("input-edit-nickname").performTextReplacement("新极客酷昵称")
        composeRule.onNodeWithTag("confirm-edit-nickname").performClick()
        composeRule.waitUntil { updatedCredentials != null }

        assertEquals(1, repository.updateNicknameCalls)
        assertEquals("新极客酷昵称", repository.lastNickname)
        assertEquals("新极客酷昵称", updatedCredentials?.nickname)
        assertEquals(42L, updatedCredentials?.accountId)
        assertEquals(deletionState, updatedCredentials?.deletionState)
    }

    @Test
    fun stableAccountIdAndSupportedProfileAndReplacementActionsAreShown() {
        val phone = com.bks.api.AccountIdentifierContract(
            com.bks.api.AccountIdentifierTypeContract.PHONE,
            "13800138000"
        )
        val email = com.bks.api.AccountIdentifierContract(
            com.bks.api.AccountIdentifierTypeContract.EMAIL,
            "user@example.com"
        )
        val repository = TestAccountRepository()
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn(
                    accountId = 42,
                    accountUuid = "d061c044-86c0-4673-8b07-3bd605ced1bc",
                    primaryIdentifier = phone,
                    identifiers = listOf(phone, email),
                    token = "secret-session-token"
                ),
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

        composeRule.onNodeWithText("d061c044****d1bc").assertIsDisplayed()
        composeRule.onNodeWithText("d061c044-86c0-4673-8b07-3bd605ced1bc").assertDoesNotExist()
        composeRule.onNodeWithText("42").assertDoesNotExist()
        composeRule.onNodeWithText("secret-session-token").assertDoesNotExist()
        composeRule.onNodeWithTag("copy-account-id").assertIsDisplayed().performClick()
        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(
            "d061c044-86c0-4673-8b07-3bd605ced1bc",
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        )
        composeRule.onNodeWithTag("btn-edit-avatar").assertIsDisplayed()
        composeRule.onNodeWithTag("btn-edit-nickname").assertIsDisplayed()
        composeRule.onNodeWithTag("bind-phone").assertDoesNotExist()
        composeRule.onNodeWithTag("replace-phone").assertIsDisplayed()
        composeRule.onNodeWithTag("replace-email").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("replace-phone").performScrollTo().performClick()
        composeRule.onNodeWithTag("identity-phone").performTextInput("13900139000")
        composeRule.onNodeWithText("获取验证码").performClick()
        composeRule.waitUntil { repository.prepareIdentifierLinkCalls == 1 }
        assertTrue(repository.lastReplaceExisting)
    }

    @Test
    fun avatarSourceDialogOffersCameraAndGallery() {
        composeRule.setContent {
            AccountManagementScreen(
                session = AccountSession.SignedIn(phone = "13800138000", token = "token"),
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

        composeRule.onNodeWithTag("btn-edit-avatar").performClick()

        composeRule.onNodeWithText("修改头像").assertIsDisplayed()
        composeRule.onNodeWithTag("take-avatar-photo").assertIsDisplayed()
        composeRule.onNodeWithTag("pick-avatar-gallery").assertIsDisplayed()
    }

    @Test
    fun galleryAvatarResultUploadsAndRefreshesSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://test/avatar.jpg")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        bitmap.recycle()
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) {
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, uri as O)
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        val repository = TestAccountRepository()
        val session = mutableStateOf<AccountSession>(
            AccountSession.SignedIn(phone = "13800138000", token = "token")
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                AccountManagementScreen(
                    session = session.value,
                    runtimeState = AccountRuntimeState(AccountRuntimeStatus.Verified),
                    deletionState = AccountDeletionUiState(),
                    accountRepository = repository,
                    onSignInOrRegister = {},
                    onSessionVerified = { session.value = it.toSignedInSession() },
                    onInvalidSession = {},
                    clearPersistedSession = { true },
                    onSignedOut = {},
                    onDeletionStateChange = {},
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithTag("btn-edit-avatar").performClick()
        composeRule.onNodeWithTag("pick-avatar-gallery").performClick()
        composeRule.waitUntil {
            repository.updateAvatarCalls == 1 &&
                (session.value as? AccountSession.SignedIn)?.avatarUrl == repository.lastAvatarDataUrl
        }

        val updated = session.value as AccountSession.SignedIn
        assertTrue(repository.lastAvatarDataUrl.orEmpty().startsWith("data:image/jpeg;base64,"))
        assertEquals(repository.lastAvatarDataUrl, updated.avatarUrl)
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
