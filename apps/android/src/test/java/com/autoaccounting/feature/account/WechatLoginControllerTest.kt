package com.autoaccounting.feature.account

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatLoginControllerTest {
    @Test
    fun agreementGateBlocksGatewayAndDuplicateStartIsLocked() {
        val repository = TestAccountRepository()
        val gateway = RecordingWechatGateway()
        val controller = controller(repository, gateway)

        controller.start(agreementAccepted = false)

        assertEquals(0, gateway.calls)
        assertEquals("请先阅读并同意用户协议和隐私政策", controller.state.errorMessage)

        controller.start(agreementAccepted = true)
        controller.start(agreementAccepted = true)

        assertEquals(1, gateway.calls)
        assertTrue(controller.state.operationInProgress)
        assertEquals(WechatAuthPurpose.SignInOrRegister, gateway.lastPurpose)
    }

    @Test
    fun callbackShowsProfilePreviewAndCreatesWechatAccount() = runTest {
        val repository = TestAccountRepository().apply {
            authenticationResult = AccountRepositoryResult.Success(
                AccountCredentials(
                    phone = null,
                    token = "wechat-token",
                    wechatLinked = true,
                    nickname = "微信小张",
                    avatarUrl = "https://example.com/avatar.jpg"
                )
            )
        }
        var persisted: AccountCredentials? = null
        var signedIn: AccountSession.SignedIn? = null
        val controller = controller(
            repository = repository,
            persistSession = { credentials -> persisted = credentials; true },
            onSignedIn = { signedIn = it }
        )

        controller.handleCallback(
            WechatAuthCallback.Authorized("one-time-code", WechatAuthPurpose.SignInOrRegister)
        )

        assertEquals(WechatLoginPage.Preview, controller.state.page)
        assertEquals("微信用户", controller.state.nickname)
        assertEquals(1, repository.exchangeWechatCalls)

        controller.createWechatAccount()

        assertEquals(1, repository.registerWithWechatCalls)
        assertNotNull(persisted)
        assertEquals("wechat-token", signedIn?.token)
        assertEquals(WechatLoginPage.Idle, controller.state.page)
    }

    @Test
    fun passwordAndSmsBindingUseTheirDedicatedRepositoryPaths() = runTest {
        val passwordRepository = TestAccountRepository()
        val passwordController = previewController(passwordRepository)
        passwordController.showBinding()
        passwordController.updatePhone("13800138000")
        passwordController.updatePassword("Aa123456!")

        passwordController.bindExistingAccount()

        assertEquals(1, passwordRepository.linkWechatWithPasswordCalls)
        assertEquals(0, passwordRepository.linkWechatWithSmsCalls)

        val smsRepository = TestAccountRepository()
        val smsController = previewController(smsRepository)
        smsController.showBinding()
        smsController.selectBindMethod(WechatBindMethod.Sms)
        smsController.updatePhone("13800138000")

        smsController.requestBindingSms()

        assertEquals(1, smsRepository.smsCalls)
        assertEquals(AccountSmsPurpose.WechatLink, smsRepository.lastSmsPurpose)
        assertEquals("ticket", smsRepository.lastSmsContextKey)
        assertTrue(smsController.state.smsRequested)

        smsController.updateCode("123456")
        smsController.bindExistingAccount()

        assertEquals(1, smsRepository.linkWechatWithSmsCalls)
        assertEquals(0, smsRepository.linkWechatWithPasswordCalls)
    }

    @Test
    fun persistenceFailureClearsLocalSessionAndDoesNotReportSignedIn() = runTest {
        val repository = TestAccountRepository().apply {
            authenticationResult = AccountRepositoryResult.Success(
                AccountCredentials(null, "new-token", wechatLinked = true)
            )
        }
        var cleared = false
        var signedIn = false
        val controller = controller(
            repository = repository,
            persistSession = { false },
            clearPersistedSession = { cleared = true; true },
            onSignedIn = { signedIn = true }
        )
        controller.handleCallback(
            WechatAuthCallback.Authorized("one-time-code", WechatAuthPurpose.SignInOrRegister)
        )

        controller.createWechatAccount()

        assertTrue(cleared)
        assertFalse(signedIn)
        assertEquals(1, repository.signOutCalls)
        assertTrue(controller.state.errorMessage?.contains("无法安全保存登录状态") == true)
    }

    private suspend fun previewController(repository: TestAccountRepository): WechatLoginController {
        val controller = controller(repository)
        controller.handleCallback(
            WechatAuthCallback.Authorized("one-time-code", WechatAuthPurpose.SignInOrRegister)
        )
        return controller
    }

    private fun controller(
        repository: TestAccountRepository,
        gateway: WechatAuthGateway = RecordingWechatGateway(),
        persistSession: (AccountCredentials) -> Boolean = { true },
        clearPersistedSession: () -> Boolean = { true },
        onSignedIn: (AccountSession.SignedIn) -> Unit = {}
    ): WechatLoginController = WechatLoginController(
        accountRepository = repository,
        authCoordinator = WechatAuthCoordinator(gateway),
        persistSession = persistSession,
        clearPersistedSession = clearPersistedSession,
        onSignedIn = onSignedIn
    )

    private class RecordingWechatGateway : WechatAuthGateway {
        var calls = 0
        var lastPurpose: WechatAuthPurpose? = null

        override fun startAuthorization(purpose: WechatAuthPurpose): WechatAuthLaunchResult {
            calls += 1
            lastPurpose = purpose
            return WechatAuthLaunchResult.Started
        }
    }
}
