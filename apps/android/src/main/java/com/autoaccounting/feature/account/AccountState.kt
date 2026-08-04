package com.autoaccounting.feature.account

enum class AccountFlow {
    Landing,
    Login,
    Register,
    Recovery,
    LocalModeExplanation
}

data class AccountUiState(
    val flow: AccountFlow = AccountFlow.Landing,
    val phone: String = "",
    val verificationCode: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val agreementAccepted: Boolean = false,
    val errorMessage: String? = null,
    val phoneError: String? = null,
    val verificationCodeError: String? = null,
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val smsCountdownSeconds: Int = 0,
    val operationInProgress: Boolean = false,
    val session: AccountSession? = null
) {
    val hasNoFieldErrors: Boolean
        get() = listOf(
            phoneError,
            verificationCodeError,
            passwordError,
            confirmPasswordError
        ).all { it == null }
}

sealed interface AccountAction {
    data object ShowLogin : AccountAction
    data object ShowRegister : AccountAction
    data object ShowRecovery : AccountAction
    data object BackToLanding : AccountAction
    data object StartLocalMode : AccountAction
    data object ConfirmLocalMode : AccountAction
    data class SetAgreementAccepted(val accepted: Boolean) : AccountAction
    data class UpdatePhone(val phone: String) : AccountAction
    data class UpdateVerificationCode(val code: String) : AccountAction
    data class UpdatePassword(val password: String) : AccountAction
    data class UpdateConfirmPassword(val password: String) : AccountAction
    data object RequestSmsCode : AccountAction
    data object TickSmsCountdown : AccountAction
    data object SubmitLogin : AccountAction
    data object SubmitRegister : AccountAction
    data object SubmitRecovery : AccountAction
}

fun reduceAccountState(
    state: AccountUiState,
    action: AccountAction
): AccountUiState = when (action) {
    AccountAction.ShowLogin -> state.switchFlow(AccountFlow.Login)
    AccountAction.ShowRegister -> state.switchFlow(AccountFlow.Register)
    AccountAction.ShowRecovery -> state.switchFlow(AccountFlow.Recovery)
    AccountAction.BackToLanding -> state.switchFlow(AccountFlow.Landing)
    is AccountAction.SetAgreementAccepted -> state.copy(
        agreementAccepted = action.accepted,
        errorMessage = null
    )
    is AccountAction.UpdatePhone -> state.copy(
        phone = action.phone,
        verificationCode = "",
        verificationCodeError = null,
        smsCountdownSeconds = 0,
        phoneError = null,
        errorMessage = null
    )
    is AccountAction.UpdateVerificationCode -> state.copy(
        verificationCode = action.code,
        verificationCodeError = null,
        errorMessage = null
    )
    is AccountAction.UpdatePassword -> state.copy(
        password = action.password,
        passwordError = null,
        errorMessage = null
    )
    is AccountAction.UpdateConfirmPassword -> state.copy(
        confirmPassword = action.password,
        confirmPasswordError = null,
        errorMessage = null
    )
    AccountAction.StartLocalMode -> if (!state.agreementAccepted) {
        state.withAgreementError()
    } else {
        state.clearErrors().copy(flow = AccountFlow.LocalModeExplanation)
    }
    AccountAction.ConfirmLocalMode -> state.copy(session = AccountSession.LocalMode)
    AccountAction.RequestSmsCode -> {
        if (!state.requiresVerificationCode) {
            state.copy(phoneError = "用户名不支持获取验证码，请使用手机号或邮箱")
        } else {
            val phoneError = validateIdentifier(state.phone, state.flow)
            if (phoneError != null) {
                state.copy(phoneError = phoneError)
            } else {
                state.clearErrors()
            }
        }
    }
    AccountAction.TickSmsCountdown -> state.copy(
        smsCountdownSeconds = (state.smsCountdownSeconds - 1).coerceAtLeast(0)
    )
    AccountAction.SubmitLogin -> state.submitLogin()
    AccountAction.SubmitRegister -> state.submitRegister()
    AccountAction.SubmitRecovery -> state.submitRecovery()
}

private fun AccountUiState.switchFlow(flow: AccountFlow): AccountUiState {
    return clearErrors().copy(
        flow = flow,
        verificationCode = "",
        password = "",
        confirmPassword = ""
    )
}

private fun AccountUiState.submitLogin(): AccountUiState {
    if (!agreementAccepted) return withAgreementError()
    val phoneError = validateIdentifier(phone, flow)
    if (phoneError != null) return copy(phoneError = phoneError)
    if (password.isBlank()) return copy(errorMessage = "账号或密码不正确")
    return clearErrors()
}

private fun AccountUiState.submitRegister(): AccountUiState {
    if (!agreementAccepted) return withAgreementError()
    val next = withRegistrationFieldErrors()
    return if (next.hasNoFieldErrors) next.copy(errorMessage = null) else next
}

private fun AccountUiState.submitRecovery(): AccountUiState {
    val next = withRegistrationFieldErrors()
    return if (next.hasNoFieldErrors) next.copy(errorMessage = null) else next
}

private fun AccountUiState.withRegistrationFieldErrors(): AccountUiState {
    val phoneError = validateIdentifier(phone, flow)
    val verificationCodeError = if (requiresVerificationCode && verificationCode.isBlank()) "请输入验证码" else null
    val passwordError = validatePassword(password)
    val confirmPasswordError = if (
        confirmPassword.isNotBlank() &&
        password != confirmPassword
    ) {
        "两次输入的密码不一致"
    } else if (confirmPassword.isBlank()) {
        "请再次输入密码"
    } else {
        null
    }
    return copy(
        phoneError = phoneError,
        verificationCodeError = verificationCodeError,
        passwordError = passwordError,
        confirmPasswordError = confirmPasswordError
    )
}

private fun AccountUiState.withAgreementError(): AccountUiState {
    return copy(errorMessage = "请先阅读并同意用户协议和隐私政策")
}

private fun AccountUiState.clearErrors(): AccountUiState {
    return copy(
        errorMessage = null,
        phoneError = null,
        verificationCodeError = null,
        passwordError = null,
        confirmPasswordError = null
    )
}
