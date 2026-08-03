package com.autoaccounting.feature.account

import com.autoaccounting.api.AccountIdentifierParser
import com.autoaccounting.api.AccountIdentifierTypeContract
import com.autoaccounting.api.IdentifierLinkPrepareResponseContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class WechatAccountIdentityDialogOperations(
    val scope: CoroutineScope,
    val session: AccountSession.SignedIn,
    val deletionState: AccountDeletionUiState,
    val accountRepository: AccountRepository,
    val actions: WechatAccountIdentityActions
)

private fun AccountIdentityUiState.hasValidIdentifier(): Boolean {
    val parsedType = runCatching { AccountIdentifierParser.parse(phone).type }.getOrNull()
        ?: return false
    if (parsedType == AccountIdentifierTypeContract.USERNAME) return false
    return targetIdentifierType?.let { it == parsedType } ?: true
}

internal fun WechatAccountIdentityDialogOperations.fail(message: String) {
    actions.onFailureMessage(message)
}

internal fun WechatAccountIdentityDialogOperations.launchNicknameUpdate(
    state: AccountIdentityUiState,
    nickname: String
) {
    scope.launch {
        val inProgressState = state.copy(operationInProgress = true, errorMessage = null)
        actions.onStateChange(inProgressState)
        actions.onHandleCredentials(
            accountRepository.updateNickname(
                credentials = session.toCredentials(deletionState),
                nickname = nickname
            ),
            false
        )
    }
}

internal fun WechatAccountIdentityDialogOperations.launchIdentifierSms(
    state: AccountIdentityUiState
) {
    scope.launch {
        if (!state.hasValidIdentifier()) return@launch fail(state.expectedIdentifierError())
        actions.onStateChange(state.copy(operationInProgress = true, errorMessage = null))
        when (
            val result = accountRepository.prepareIdentifierLink(
                session.token,
                state.phone,
                state.replaceExistingIdentifier
            )
        ) {
            is AccountRepositoryResult.Success -> {
                val currentState = actions.currentState()
                if (currentState.phone != state.phone) {
                    actions.onStateChange(currentState.copy(operationInProgress = false))
                } else {
                    actions.onStateChange(
                        when (val prepared = result.value) {
                            IdentifierLinkPrepareResponseContract.AlreadyLinked -> AccountIdentityUiState()
                            is IdentifierLinkPrepareResponseContract.LinkTicketIssued -> currentState.copy(
                                phoneTicket = prepared.linkTicket,
                                operationInProgress = false,
                                errorMessage = null
                            )
                            is IdentifierLinkPrepareResponseContract.MergeRequired -> currentState.copy(
                                operationInProgress = false,
                                errorMessage = "该标识已属于其他账号，不能绑定或合并"
                            )
                        }
                    )
                }
            }
            is AccountRepositoryResult.Failure -> actions.onFailureResult(result)
        }
    }
}

internal fun WechatAccountIdentityDialogOperations.launchIdentifierConfirmation(
    state: AccountIdentityUiState
) {
    scope.launch {
        if (!state.hasValidIdentifier()) return@launch fail(state.expectedIdentifierError())
        actions.onStateChange(state.copy(operationInProgress = true, errorMessage = null))
        if (state.identifierAttachMethod == IdentifierAttachMethod.PasswordMerge) {
            when (
                val result = accountRepository.prepareMergeWithIdentifierPassword(
                    session.token,
                    state.phone,
                    state.password
                )
            ) {
                is AccountRepositoryResult.Success -> actions.onStateChange(result.value.toMergeState())
                is AccountRepositoryResult.Failure -> actions.onFailureResult(result)
            }
            return@launch
        }

        val ticket = state.phoneTicket ?: return@launch fail("请先获取验证码")
        if (session.identifiers.isEmpty() && session.wechatLinked) {
            actions.onStateChange(
                state.copy(
                    page = AccountIdentityPage.SetPhonePassword,
                    operationInProgress = false,
                    errorMessage = null
                )
            )
        } else {
            actions.onHandleCredentials(
                accountRepository.completeIdentifierLink(
                    token = session.token,
                    linkTicket = ticket,
                    code = state.code
                ),
                false
            )
        }
    }
}

internal fun WechatAccountIdentityDialogOperations.launchPhonePasswordConfirmation(
    state: AccountIdentityUiState
) {
    scope.launch {
        val ticket = state.phoneTicket ?: return@launch fail("绑定票据已失效")
        actions.onStateChange(state.copy(operationInProgress = true, errorMessage = null))
        actions.onHandleCredentials(
            accountRepository.completeIdentifierLink(
                token = session.token,
                linkTicket = ticket,
                code = state.code,
                password = state.password
            ),
            false
        )
    }
}

internal fun WechatAccountIdentityDialogOperations.launchMergeConfirmation(
    state: AccountIdentityUiState
) {
    scope.launch {
        val ticket = state.mergeTicket ?: return@launch fail("合并票据已失效")
        actions.onStateChange(state.copy(operationInProgress = true, errorMessage = null))
        actions.onHandleCredentials(
            accountRepository.confirmMerge(session.token, ticket, state.confirmText),
            false
        )
    }
}

internal fun WechatAccountIdentityDialogOperations.launchUnlinkSmsRequest(
    state: AccountIdentityUiState
) {
    scope.launch {
        val identifier = state.unlinkIdentifier.takeIf { it.isNotBlank() }
            ?: return@launch fail("请选择手机号或邮箱")
        val inProgressState = state.copy(operationInProgress = true, errorMessage = null)
        actions.onStateChange(inProgressState)
        when (
            val result = accountRepository.requestVerificationCode(
                identifier = identifier,
                purpose = AccountVerificationPurpose.WechatUnlink,
                bearerToken = session.token
            )
        ) {
            is AccountRepositoryResult.Success -> actions.onOperationFinished()
            is AccountRepositoryResult.Failure -> actions.onFailureResult(result)
        }
    }
}

internal fun WechatAccountIdentityDialogOperations.launchUnlinkConfirmation(
    state: AccountIdentityUiState
) {
    scope.launch {
        actions.onStateChange(state.copy(operationInProgress = true, errorMessage = null))
        val result = when (state.unlinkMethod) {
            UnlinkMethod.Password -> accountRepository.unlinkWechatWithPassword(session.token, state.password)
            UnlinkMethod.Code -> accountRepository.unlinkWechatWithCode(
                session.token,
                state.unlinkIdentifier,
                state.code
            )
        }
        actions.onHandleCredentials(result, true)
    }
}
