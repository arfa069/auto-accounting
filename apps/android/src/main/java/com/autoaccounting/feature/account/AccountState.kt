package com.autoaccounting.feature.account

enum class AccountFlow {
    Landing,
    Login,
    Register,
    Recovery,
    LocalModeExplanation
}

sealed interface AccountSession {
    data object LocalMode : AccountSession
    data class SignedIn(
        val phone: String,
        val token: String = ""
    ) : AccountSession
}

enum class AccountRuntimeStatus {
    LocalMode,
    Validating,
    Verified,
    OfflineUnverified,
    DeletionCoolingOff
}

data class AccountRuntimeState(
    val status: AccountRuntimeStatus = AccountRuntimeStatus.LocalMode
) {
    val cloudWritesAllowed: Boolean
        get() = status == AccountRuntimeStatus.Verified

    val accountOperationsAllowed: Boolean
        get() = status == AccountRuntimeStatus.Verified ||
            status == AccountRuntimeStatus.DeletionCoolingOff
}

sealed interface AccountSessionVerificationDecision {
    data class Verified(val credentials: AccountCredentials) : AccountSessionVerificationDecision
    data object KeepOfflineSession : AccountSessionVerificationDecision
    data object ClearInvalidSession : AccountSessionVerificationDecision
}

fun resolveAccountSessionVerification(
    result: AccountRepositoryResult<AccountCredentials>
): AccountSessionVerificationDecision = when (result) {
    is AccountRepositoryResult.Success ->
        AccountSessionVerificationDecision.Verified(result.value)
    is AccountRepositoryResult.Failure ->
        if (result.kind == AccountFailureKind.InvalidSession) {
            AccountSessionVerificationDecision.ClearInvalidSession
        } else {
            AccountSessionVerificationDecision.KeepOfflineSession
        }
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
        val phoneError = validatePhone(state.phone)
        if (phoneError != null) {
            state.copy(phoneError = phoneError)
        } else {
            state.clearErrors()
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
    val phoneError = validatePhone(phone)
    if (phoneError != null) return copy(phoneError = phoneError)
    if (password.isBlank()) return copy(errorMessage = "手机号或密码不正确")
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
    val phoneError = validatePhone(phone)
    val verificationCodeError = if (verificationCode.isBlank()) "请输入验证码" else null
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

private fun validatePhone(phone: String): String? {
    return if (Regex("^\\d{11}$").matches(phone)) null else "请输入 11 位手机号"
}

private fun validatePassword(password: String): String? {
    val valid = password.length in 8..32 &&
        password.any { it.isUpperCase() } &&
        password.any { it.isLowerCase() } &&
        password.any { it.isDigit() } &&
        password.any { !it.isLetterOrDigit() }
    return if (valid) null else "密码需 8-32 位，包含大小写字母、数字和符号"
}
