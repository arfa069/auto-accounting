package com.bks.feature.account

internal fun AccountAction.isNetworkAction(): Boolean = when (this) {
    AccountAction.RequestSmsCode,
    AccountAction.SubmitLogin,
    AccountAction.SubmitRegister,
    AccountAction.SubmitRecovery -> true

    else -> false
}

internal fun AccountUiState.isReadyFor(action: AccountAction): Boolean = when (action) {
    AccountAction.RequestSmsCode -> phoneError == null
    AccountAction.SubmitLogin -> phoneError == null && errorMessage == null
    AccountAction.SubmitRegister,
    AccountAction.SubmitRecovery -> hasNoFieldErrors && errorMessage == null

    else -> true
}

internal suspend fun AccountUiState.runNetworkAction(
    action: AccountAction,
    accountRepository: AccountRepository,
    persistSession: (AccountCredentials) -> Boolean,
    clearPersistedSession: () -> Boolean
): AccountUiState = when (action) {
    AccountAction.RequestSmsCode -> submitSmsCodeRequest(accountRepository)
    AccountAction.SubmitLogin -> submitLogin(
        accountRepository,
        persistSession,
        clearPersistedSession
    )
    AccountAction.SubmitRegister -> submitRegister(
        accountRepository,
        persistSession,
        clearPersistedSession
    )
    AccountAction.SubmitRecovery -> submitRecovery(
        accountRepository,
        persistSession,
        clearPersistedSession
    )
    else -> this
}

private suspend fun AccountUiState.submitSmsCodeRequest(
    accountRepository: AccountRepository
): AccountUiState {
    val purpose = when (flow) {
        AccountFlow.Register -> AccountVerificationPurpose.Register
        AccountFlow.Recovery -> AccountVerificationPurpose.Recovery
        else -> return copy(errorMessage = "当前页面不能获取验证码")
    }
    return when (val result = accountRepository.requestVerificationCode(phone, purpose)) {
        is AccountRepositoryResult.Success -> copy(smsCountdownSeconds = 60)
        is AccountRepositoryResult.Failure -> copy(
            smsCountdownSeconds = 0,
            errorMessage = result.message
        )
    }
}

private suspend fun AccountUiState.submitLogin(
    accountRepository: AccountRepository,
    persistSession: (AccountCredentials) -> Boolean,
    clearPersistedSession: () -> Boolean
): AccountUiState {
    return completeAuthentication(
        result = accountRepository.login(phone, password),
        accountRepository = accountRepository,
        persistSession = persistSession,
        clearPersistedSession = clearPersistedSession
    )
}

private suspend fun AccountUiState.submitRegister(
    accountRepository: AccountRepository,
    persistSession: (AccountCredentials) -> Boolean,
    clearPersistedSession: () -> Boolean
): AccountUiState {
    return completeAuthentication(
        result = accountRepository.register(phone, verificationCode, password),
        accountRepository = accountRepository,
        persistSession = persistSession,
        clearPersistedSession = clearPersistedSession
    )
}

private suspend fun AccountUiState.submitRecovery(
    accountRepository: AccountRepository,
    persistSession: (AccountCredentials) -> Boolean,
    clearPersistedSession: () -> Boolean
): AccountUiState {
    return completeAuthentication(
        result = accountRepository.recoverPassword(phone, verificationCode, password),
        accountRepository = accountRepository,
        persistSession = persistSession,
        clearPersistedSession = clearPersistedSession
    )
}

private suspend fun AccountUiState.completeAuthentication(
    result: AccountRepositoryResult<AccountCredentials>,
    accountRepository: AccountRepository,
    persistSession: (AccountCredentials) -> Boolean,
    clearPersistedSession: () -> Boolean
): AccountUiState = when (result) {
    is AccountRepositoryResult.Success -> {
        if (
            persistAccountSessionOrRevoke(
                credentials = result.value,
                accountRepository = accountRepository,
                persistSession = persistSession,
                clearPersistedSession = clearPersistedSession
            )
        ) {
            copy(
                session = result.value.toSignedInSession()
            )
        } else {
            copy(errorMessage = "无法安全保存登录状态，已尝试撤销新会话，请重试")
        }
    }
    is AccountRepositoryResult.Failure -> copy(errorMessage = result.message)
}
