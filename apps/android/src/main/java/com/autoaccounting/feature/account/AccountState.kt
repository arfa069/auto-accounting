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
        val accountId: Long? = null,
        val primaryIdentifier: com.autoaccounting.api.AccountIdentifierContract? = null,
        val identifiers: List<com.autoaccounting.api.AccountIdentifierContract> = emptyList(),
        val rawPhone: String? = null,
        val token: String = "",
        val accountUuid: String? = null,
        val wechatLinked: Boolean = false,
        val nickname: String? = null,
        val avatarUrl: String? = null
    ) : AccountSession {
        constructor(
            phone: String?,
            token: String = "",
            wechatLinked: Boolean = false,
            nickname: String? = null,
            avatarUrl: String? = null
        ) : this(
            accountId = null,
            accountUuid = null,
            primaryIdentifier = phone?.let {
                com.autoaccounting.api.AccountIdentifierContract(
                    type = com.autoaccounting.api.AccountIdentifierTypeContract.PHONE,
                    value = it
                )
            },
            identifiers = phone?.let {
                listOf(
                    com.autoaccounting.api.AccountIdentifierContract(
                        type = com.autoaccounting.api.AccountIdentifierTypeContract.PHONE,
                        value = it
                    )
                )
            } ?: emptyList(),
            rawPhone = phone,
            token = token,
            wechatLinked = wechatLinked,
            nickname = nickname,
            avatarUrl = avatarUrl
        )

        val phone: String?
            get() = identifiers.find { it.type == com.autoaccounting.api.AccountIdentifierTypeContract.PHONE }?.value ?: rawPhone

        val email: String?
            get() = identifiers.find { it.type == com.autoaccounting.api.AccountIdentifierTypeContract.EMAIL }?.value

        val username: String?
            get() = identifiers.find { it.type == com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME }?.value
    }
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

val AccountUiState.identifierType: com.autoaccounting.api.AccountIdentifierTypeContract
    get() = try {
        com.autoaccounting.api.AccountIdentifierParser.parse(phone).type
    } catch (e: Exception) {
        if (phone.contains("@")) com.autoaccounting.api.AccountIdentifierTypeContract.EMAIL
        else if (phone.all { it.isDigit() }) com.autoaccounting.api.AccountIdentifierTypeContract.PHONE
        else com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME
    }

val AccountUiState.requiresVerificationCode: Boolean
    get() = identifierType != com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME

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

private fun validateIdentifier(identifier: String, flow: AccountFlow = AccountFlow.Landing): String? {
    if (identifier.isBlank()) return "请输入手机号、邮箱或用户名"
    val parseResult = try {
        com.autoaccounting.api.AccountIdentifierParser.parse(identifier)
    } catch (e: Exception) {
        return "标识格式不正确"
    }
    if (flow == AccountFlow.Recovery && parseResult.type == com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME) {
        return "用户名不支持找回密码，请使用已绑定的手机号或邮箱"
    }
    return null
}

private fun validatePassword(password: String): String? {
    val valid = password.length in 8..32 &&
        password.any { it.isUpperCase() } &&
        password.any { it.isLowerCase() } &&
        password.any { it.isDigit() } &&
        password.any { !it.isLetterOrDigit() }
    return if (valid) null else "密码需 8-32 位，包含大小写字母、数字和符号"
}
