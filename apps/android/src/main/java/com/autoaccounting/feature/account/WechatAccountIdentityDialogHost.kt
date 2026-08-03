package com.autoaccounting.feature.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.autoaccounting.api.AccountIdentifierTypeContract

@Composable
internal fun WechatAccountIdentityDialogHost(
    session: AccountSession.SignedIn,
    deletionState: AccountDeletionUiState,
    accountRepository: AccountRepository,
    state: AccountIdentityUiState,
    actions: WechatAccountIdentityActions
) {
    val operation = WechatAccountIdentityDialogOperations(
        scope = rememberCoroutineScope(),
        session = session,
        deletionState = deletionState,
        accountRepository = accountRepository,
        actions = actions
    )
    when (state.page) {
        AccountIdentityPage.EditNickname -> WechatEditNicknameDialog(state, operation)
        AccountIdentityPage.AttachIdentifier -> WechatAttachIdentifierDialog(state, operation)
        AccountIdentityPage.SetPhonePassword -> WechatSetPhonePasswordDialog(state, operation)
        AccountIdentityPage.Merge -> WechatMergeDialog(state, operation)
        AccountIdentityPage.UnlinkWechat -> WechatUnlinkDialog(state, operation)
        AccountIdentityPage.Idle -> Unit
    }
}

@Composable
private fun WechatEditNicknameDialog(
    state: AccountIdentityUiState,
    operation: WechatAccountIdentityDialogOperations
) {
    EditNicknameDialog(
        initialValue = state.editNicknameInput,
        operationInProgress = state.operationInProgress,
        onDismiss = { operation.actions.onStateChange(AccountIdentityUiState()) },
        onConfirm = { operation.launchNicknameUpdate(state, it) }
    )
}

@Composable
private fun WechatAttachIdentifierDialog(
    state: AccountIdentityUiState,
    operation: WechatAccountIdentityDialogOperations
) {
    val session = operation.session
    AccountIdentityDialog(
        title = when (state.targetIdentifierType) {
            AccountIdentifierTypeContract.PHONE -> if (state.replaceExistingIdentifier) "换绑手机号" else "绑定手机号"
            AccountIdentifierTypeContract.EMAIL -> if (state.replaceExistingIdentifier) "换绑邮箱" else "绑定邮箱"
            else -> "绑定手机号或邮箱"
        },
        state = state,
        allowPasswordMerge = !state.replaceExistingIdentifier &&
            session.identifiers.isEmpty() && session.wechatLinked,
        onStateChange = operation.actions.onStateChange,
        onRequestSms = { operation.launchIdentifierSms(state) },
        onConfirm = { operation.launchIdentifierConfirmation(state) }
    )
}

@Composable
private fun WechatSetPhonePasswordDialog(
    state: AccountIdentityUiState,
    operation: WechatAccountIdentityDialogOperations
) {
    SimplePasswordDialog(
        title = "设置登录密码",
        password = state.password,
        operationInProgress = state.operationInProgress,
        errorMessage = state.errorMessage,
        onPasswordChange = {
            operation.actions.onStateChange(state.copy(password = it, errorMessage = null))
        },
        onDismiss = {
            if (!state.operationInProgress) operation.actions.onStateChange(AccountIdentityUiState())
        },
        onConfirm = { operation.launchPhonePasswordConfirmation(state) }
    )
}

@Composable
private fun WechatMergeDialog(
    state: AccountIdentityUiState,
    operation: WechatAccountIdentityDialogOperations
) {
    MergeConfirmationDialog(
        session = operation.session,
        state = state,
        onConfirmTextChange = {
            operation.actions.onStateChange(state.copy(confirmText = it, errorMessage = null))
        },
        onDismiss = {
            if (!state.operationInProgress) operation.actions.onStateChange(AccountIdentityUiState())
        },
        onConfirm = { operation.launchMergeConfirmation(state) }
    )
}

@Composable
private fun WechatUnlinkDialog(
    state: AccountIdentityUiState,
    operation: WechatAccountIdentityDialogOperations
) {
    UnlinkWechatDialog(
        state = state,
        availableIdentifiers = operation.session.identifiers.filter {
            it.type == AccountIdentifierTypeContract.PHONE ||
                it.type == AccountIdentifierTypeContract.EMAIL
        },
        onStateChange = operation.actions.onStateChange,
        onRequestSms = { operation.launchUnlinkSmsRequest(state) },
        onConfirm = { operation.launchUnlinkConfirmation(state) }
    )
}

internal data class WechatAccountIdentityActions(
    val onStateChange: (AccountIdentityUiState) -> Unit,
    val currentState: () -> AccountIdentityUiState,
    val onOperationFinished: () -> Unit,
    val onFailureMessage: (String) -> Unit,
    val onFailureResult: (AccountRepositoryResult.Failure) -> Unit,
    val onHandleCredentials: suspend (AccountRepositoryResult<AccountCredentials>, Boolean) -> Unit
)
