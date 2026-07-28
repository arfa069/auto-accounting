package com.autoaccounting.feature.account

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class WechatLoginPage {
    Idle,
    Preview,
    BindExisting
}

enum class WechatBindMethod {
    Password,
    Sms
}

data class WechatLoginUiState(
    val page: WechatLoginPage = WechatLoginPage.Idle,
    val wechatTicket: String? = null,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val bindMethod: WechatBindMethod = WechatBindMethod.Password,
    val phone: String = "",
    val password: String = "",
    val code: String = "",
    val operationInProgress: Boolean = false,
    val smsRequested: Boolean = false,
    val errorMessage: String? = null
)

@Stable
class WechatLoginController(
    private val accountRepository: AccountRepository,
    private val authCoordinator: WechatAuthCoordinator,
    private val persistSession: (AccountCredentials) -> Boolean,
    private val clearPersistedSession: () -> Boolean,
    private val onSignedIn: (AccountSession.SignedIn) -> Unit
) {
    var state by mutableStateOf(WechatLoginUiState())
        private set

    fun start(agreementAccepted: Boolean) {
        if (state.operationInProgress) return
        when (
            authCoordinator.startAuthorization(
                agreementAccepted = agreementAccepted,
                purpose = WechatAuthPurpose.SignInOrRegister
            )
        ) {
            WechatAuthLaunchResult.Started -> state = state.copy(
                operationInProgress = true,
                errorMessage = null
            )
            WechatAuthLaunchResult.AgreementRequired -> fail("请先阅读并同意用户协议和隐私政策")
            WechatAuthLaunchResult.NotConfigured -> fail("微信登录暂未配置")
            WechatAuthLaunchResult.NotInstalled -> fail("未检测到微信，请先安装微信")
            WechatAuthLaunchResult.VersionUnsupported -> fail("当前微信版本过低，请升级后重试")
            WechatAuthLaunchResult.SendFailed -> fail("无法启动微信授权，请稍后重试")
        }
    }

    suspend fun handleCallback(callback: WechatAuthCallback) {
        if (callback.purpose() != WechatAuthPurpose.SignInOrRegister) return
        when (callback) {
            is WechatAuthCallback.Authorized -> exchange(callback.code)
            is WechatAuthCallback.Cancelled -> fail("已取消微信授权")
            is WechatAuthCallback.Denied -> fail("微信授权已拒绝")
            is WechatAuthCallback.Failed -> fail("微信授权失败，请重试")
        }
    }

    fun showBinding() {
        if (state.wechatTicket != null) {
            state = state.copy(page = WechatLoginPage.BindExisting, errorMessage = null)
        }
    }

    fun selectBindMethod(method: WechatBindMethod) {
        if (!state.operationInProgress) {
            state = state.copy(bindMethod = method, errorMessage = null)
        }
    }

    fun updatePhone(value: String) {
        state = state.copy(phone = value, errorMessage = null)
    }

    fun updatePassword(value: String) {
        state = state.copy(password = value, errorMessage = null)
    }

    fun updateCode(value: String) {
        state = state.copy(code = value, errorMessage = null)
    }

    suspend fun createWechatAccount() {
        val ticket = state.wechatTicket ?: return fail("微信授权已失效，请重新授权")
        if (state.operationInProgress) return
        state = state.copy(operationInProgress = true, errorMessage = null)
        finishCredentials(accountRepository.registerWithWechat(ticket))
    }

    suspend fun requestBindingSms() {
        val ticket = state.wechatTicket ?: return fail("微信授权已失效，请重新授权")
        if (state.operationInProgress || !validIdentifier(requireContact = true)) return
        state = state.copy(operationInProgress = true, errorMessage = null)
        state = when (
            val result = accountRepository.requestVerificationCode(
                identifier = state.phone,
                purpose = AccountVerificationPurpose.WechatLink,
                contextKey = ticket
            )
        ) {
            is AccountRepositoryResult.Success -> state.copy(
                operationInProgress = false,
                smsRequested = true
            )
            is AccountRepositoryResult.Failure -> state.copy(
                operationInProgress = false,
                errorMessage = result.message
            )
        }
    }

    suspend fun bindExistingAccount() {
        val ticket = state.wechatTicket ?: return fail("微信授权已失效，请重新授权")
        if (state.operationInProgress || !validIdentifier(requireContact = state.bindMethod == WechatBindMethod.Sms)) return
        if (state.bindMethod == WechatBindMethod.Password && state.password.isBlank()) {
            return fail("请输入当前密码")
        }
        if (state.bindMethod == WechatBindMethod.Sms && state.code.length != 6) {
            return fail("请输入 6 位验证码")
        }
        state = state.copy(operationInProgress = true, errorMessage = null)
        val result = when (state.bindMethod) {
            WechatBindMethod.Password -> accountRepository.linkWechatWithPassword(
                ticket,
                state.phone,
                state.password
            )
            WechatBindMethod.Sms -> accountRepository.linkWechatWithCode(
                ticket,
                state.phone,
                state.code
            )
        }
        finishCredentials(result)
    }

    fun back() {
        if (state.operationInProgress) return
        state = when (state.page) {
            WechatLoginPage.BindExisting -> state.copy(page = WechatLoginPage.Preview, errorMessage = null)
            WechatLoginPage.Preview -> WechatLoginUiState()
            WechatLoginPage.Idle -> state
        }
    }

    private suspend fun exchange(code: String) {
        state = state.copy(operationInProgress = true, errorMessage = null)
        when (val result = accountRepository.exchangeWechatCode(code)) {
            is AccountRepositoryResult.Failure -> fail(result.message)
            is AccountRepositoryResult.Success -> when (val auth = result.value) {
                is AccountWechatAuthResult.SignedIn -> finishCredentials(
                    AccountRepositoryResult.Success(auth.credentials)
                )
                is AccountWechatAuthResult.RegistrationRequired -> state = WechatLoginUiState(
                    page = WechatLoginPage.Preview,
                    wechatTicket = auth.wechatTicket,
                    nickname = auth.nickname ?: "微信用户",
                    avatarUrl = auth.avatarUrl
                )
                is AccountWechatAuthResult.MergeRequired -> fail("该微信已绑定其他账号，请先登录后再合并")
            }
        }
    }

    private suspend fun finishCredentials(result: AccountRepositoryResult<AccountCredentials>) {
        when (result) {
            is AccountRepositoryResult.Failure -> fail(result.message)
            is AccountRepositoryResult.Success -> {
                val committed = persistAccountSessionOrRevoke(
                    credentials = result.value,
                    accountRepository = accountRepository,
                    persistSession = persistSession,
                    clearPersistedSession = clearPersistedSession
                )
                if (committed) {
                    state = WechatLoginUiState()
                    onSignedIn(result.value.toSignedInSession())
                } else {
                    fail("无法安全保存登录状态，已撤销新会话并切换到本地模式")
                }
            }
        }
    }

    private fun validIdentifier(requireContact: Boolean): Boolean {
        val parsed = runCatching { com.autoaccounting.api.AccountIdentifierParser.parse(state.phone) }.getOrNull()
        if (parsed != null && (!requireContact || parsed.type != com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME)) {
            return true
        }
        fail(if (requireContact) "请输入有效的手机号或邮箱" else "请输入有效的用户名、邮箱或手机号")
        return false
    }

    private fun fail(message: String) {
        state = state.copy(operationInProgress = false, errorMessage = message)
    }
}

internal fun AccountCredentials.toSignedInSession(): AccountSession.SignedIn = AccountSession.SignedIn(
    accountId = accountId,
    accountUuid = accountUuid,
    primaryIdentifier = primaryIdentifier,
    identifiers = identifiers,
    rawPhone = rawPhone,
    token = token,
    wechatLinked = wechatLinked,
    nickname = nickname,
    avatarUrl = avatarUrl
)

private fun WechatAuthCallback.purpose(): WechatAuthPurpose = when (this) {
    is WechatAuthCallback.Authorized -> purpose
    is WechatAuthCallback.Cancelled -> purpose
    is WechatAuthCallback.Denied -> purpose
    is WechatAuthCallback.Failed -> purpose
}
