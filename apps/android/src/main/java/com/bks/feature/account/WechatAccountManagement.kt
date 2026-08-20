package com.bks.feature.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bks.api.AccountIdentifierTypeContract

internal enum class AccountIdentityPage {
    Idle,
    AttachIdentifier,
    SetPhonePassword,
    Merge,
    UnlinkWechat,
    EditNickname
}

internal enum class IdentifierAttachMethod {
    Sms,
    PasswordMerge
}

internal enum class UnlinkMethod {
    Password,
    Code
}

internal data class AccountIdentityUiState(
    val page: AccountIdentityPage = AccountIdentityPage.Idle,
    val identifierAttachMethod: IdentifierAttachMethod = IdentifierAttachMethod.Sms,
    val unlinkMethod: UnlinkMethod = UnlinkMethod.Password,
    val phone: String = "",
    val code: String = "",
    val password: String = "",
    val editNicknameInput: String = "",
    val targetIdentifierType: AccountIdentifierTypeContract? = null,
    val replaceExistingIdentifier: Boolean = false,
    val phoneTicket: String? = null,
    val mergeTicket: String? = null,
    val sourceIdentifiers: List<com.bks.api.AccountIdentifierContract> = emptyList(),
    val sourceNickname: String? = null,
    val sourceWechatLinked: Boolean = false,
    val unlinkIdentifier: String = "",
    val confirmText: String = "",
    val operationInProgress: Boolean = false,
    val errorMessage: String? = null
)

internal data class WechatAccountSessionActions(
    val onWechatAuthCallbackConsumed: () -> Unit,
    val persistSession: (AccountCredentials) -> Boolean,
    val clearPersistedSession: () -> Boolean,
    val onSessionVerified: (AccountCredentials) -> Unit,
    val onInvalidSession: () -> Unit
)

internal data class WechatAccountManagementDependencies(
    val deletionState: AccountDeletionUiState,
    val accountRepository: AccountRepository,
    val wechatAuthGateway: WechatAuthGateway?,
    val wechatAuthCallback: WechatAuthCallback?,
    val avatarCache: WechatAvatarCache,
    val sessionActions: WechatAccountSessionActions
)

private class WechatAccountProfileActionHandler(
    private val session: AccountSession.SignedIn,
    private val authCoordinator: WechatAuthCoordinator?,
    private val onStateChange: (AccountIdentityUiState) -> Unit,
    private val onFailureMessage: (String) -> Unit,
    private val onAvatarEditorRequested: () -> Unit
) {
    fun handle(action: WechatAccountProfileAction, state: AccountIdentityUiState) {
        when (action) {
            WechatAccountProfileAction.EditAvatar -> onAvatarEditorRequested()
            WechatAccountProfileAction.EditNickname -> onStateChange(
                state.copy(
                    page = AccountIdentityPage.EditNickname,
                    editNicknameInput = session.nickname.orEmpty()
                )
            )
            WechatAccountProfileAction.ReplacePhone -> onStateChange(
                AccountIdentityUiState(
                    page = AccountIdentityPage.AttachIdentifier,
                    targetIdentifierType = AccountIdentifierTypeContract.PHONE,
                    replaceExistingIdentifier = true
                )
            )
            WechatAccountProfileAction.BindPhone -> onStateChange(
                AccountIdentityUiState(
                    page = AccountIdentityPage.AttachIdentifier,
                    targetIdentifierType = AccountIdentifierTypeContract.PHONE
                )
            )
            WechatAccountProfileAction.ReplaceEmail -> onStateChange(
                AccountIdentityUiState(
                    page = AccountIdentityPage.AttachIdentifier,
                    targetIdentifierType = AccountIdentifierTypeContract.EMAIL,
                    replaceExistingIdentifier = true
                )
            )
            WechatAccountProfileAction.BindEmail -> onStateChange(
                AccountIdentityUiState(
                    page = AccountIdentityPage.AttachIdentifier,
                    targetIdentifierType = AccountIdentifierTypeContract.EMAIL
                )
            )
            WechatAccountProfileAction.UnlinkWechat -> openWechatUnlink()
            WechatAccountProfileAction.BindWechat -> startWechatLink(state)
        }
    }

    private fun startWechatLink(state: AccountIdentityUiState) {
        if (state.operationInProgress || authCoordinator == null) return
        when (
            authCoordinator.startAuthorization(
                agreementAccepted = true,
                purpose = WechatAuthPurpose.LinkCurrentAccount,
                sessionFingerprint = wechatSessionFingerprint(session.token)
            )
        ) {
            WechatAuthLaunchResult.Started -> onStateChange(state.copy(operationInProgress = true, errorMessage = null))
            WechatAuthLaunchResult.NotInstalled -> onFailureMessage("未检测到微信，请先安装微信")
            WechatAuthLaunchResult.VersionUnsupported -> onFailureMessage("当前微信版本过低，请升级后重试")
            WechatAuthLaunchResult.NotConfigured -> onFailureMessage("微信登录暂未配置")
            WechatAuthLaunchResult.SendFailed -> onFailureMessage("无法启动微信授权，请稍后重试")
            WechatAuthLaunchResult.AgreementRequired -> onFailureMessage("请先同意用户协议和隐私政策")
        }
    }

    private fun openWechatUnlink() {
        if (session.phone == null && session.email == null && session.username == null) {
            onFailureMessage("最后一种登录方式不可解绑")
        } else {
            onStateChange(
                AccountIdentityUiState(
                    page = AccountIdentityPage.UnlinkWechat,
                    unlinkIdentifier = session.phone ?: session.email.orEmpty()
                )
            )
        }
    }
}

private data class WechatAccountSessionEffectActions(
    val onStateChange: (AccountIdentityUiState) -> Unit,
    val onFailureMessage: (String) -> Unit,
    val onFailureResult: (AccountRepositoryResult.Failure) -> Unit,
    val onCommit: suspend (AccountCredentials) -> Unit
)

@Composable
private fun WechatAccountSessionEffect(
    session: AccountSession.SignedIn,
    dependencies: WechatAccountManagementDependencies,
    state: AccountIdentityUiState,
    actions: WechatAccountSessionEffectActions
) {
    LaunchedEffect(dependencies.wechatAuthCallback, session.token) {
        val callback = dependencies.wechatAuthCallback ?: return@LaunchedEffect
        if (callback.managementPurpose() != WechatAuthPurpose.LinkCurrentAccount) return@LaunchedEffect
        dependencies.sessionActions.onWechatAuthCallbackConsumed()
        if (!callback.matchesSession(session.token)) {
            actions.onFailureMessage("登录状态已变化，请重新发起微信授权")
            return@LaunchedEffect
        }
        when (callback) {
            is WechatAuthCallback.Authorized -> {
                actions.onStateChange(state.copy(operationInProgress = true, errorMessage = null))
                when (val result = dependencies.accountRepository.exchangeWechatCode(callback.code, session.token)) {
                    is AccountRepositoryResult.Failure -> actions.onFailureResult(result)
                    is AccountRepositoryResult.Success -> when (val auth = result.value) {
                        is AccountWechatAuthResult.SignedIn -> actions.onCommit(auth.credentials)
                        is AccountWechatAuthResult.MergeRequired -> actions.onStateChange(
                            AccountIdentityUiState(
                                page = AccountIdentityPage.Merge,
                                mergeTicket = auth.mergeTicket,
                                sourceIdentifiers = auth.sourceIdentifiers,
                                sourceNickname = auth.sourceNickname,
                                sourceWechatLinked = true
                            )
                        )
                        is AccountWechatAuthResult.RegistrationRequired -> actions.onFailureMessage("微信绑定状态异常，请重新授权")
                    }
                }
            }
            is WechatAuthCallback.Cancelled -> actions.onFailureMessage("已取消微信授权")
            is WechatAuthCallback.Denied -> actions.onFailureMessage("微信授权已拒绝")
            is WechatAuthCallback.Failed -> actions.onFailureMessage("微信授权失败，请重试")
        }
    }
}

@Composable
internal fun WechatAccountManagementPanel(
    session: AccountSession.SignedIn,
    dependencies: WechatAccountManagementDependencies,
    modifier: Modifier = Modifier
) {
    var state by remember { mutableStateOf(AccountIdentityUiState()) }
    var displayedAvatarUrl by remember { mutableStateOf(session.avatarUrl) }
    var avatarError by remember { mutableStateOf<String?>(null) }
    var showAvatarSourceDialog by rememberSaveable { mutableStateOf(false) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val authCoordinator = remember(dependencies.wechatAuthGateway) {
        dependencies.wechatAuthGateway?.let(::WechatAuthCoordinator)
    }

    fun fail(message: String) {
        state = state.copy(operationInProgress = false, errorMessage = message)
    }

    fun handleFailure(failure: AccountRepositoryResult.Failure) {
        state = state.copy(operationInProgress = false)
        if (failure.kind == AccountFailureKind.InvalidSession) {
            dependencies.sessionActions.onInvalidSession()
        } else {
            fail(failure.message)
        }
    }

    suspend fun commit(credentials: AccountCredentials, clearAvatar: Boolean = false) {
        val committed = persistAccountSessionOrRevoke(
            credentials = credentials,
            accountRepository = dependencies.accountRepository,
            persistSession = dependencies.sessionActions.persistSession,
            clearPersistedSession = dependencies.sessionActions.clearPersistedSession
        )
        if (committed) {
            if (clearAvatar) dependencies.avatarCache.clear()
            else dependencies.avatarCache.prepareUrl(credentials.avatarUrl)
            state = AccountIdentityUiState()
            dependencies.sessionActions.onSessionVerified(credentials)
        } else {
            dependencies.avatarCache.clear()
            state = AccountIdentityUiState(errorMessage = "无法安全保存新会话，已切换到本地模式")
            dependencies.sessionActions.onInvalidSession()
        }
    }

    suspend fun handleCredentials(
        result: AccountRepositoryResult<AccountCredentials>,
        clearAvatar: Boolean = false
    ) {
        when (result) {
            is AccountRepositoryResult.Success -> commit(result.value, clearAvatar)
            is AccountRepositoryResult.Failure -> handleFailure(result)
        }
    }

    val profileActionHandler = WechatAccountProfileActionHandler(
        session = session,
        authCoordinator = authCoordinator,
        onStateChange = { state = it },
        onFailureMessage = ::fail,
        onAvatarEditorRequested = { showAvatarSourceDialog = true }
    )

    LaunchedEffect(session.avatarUrl) {
        displayedAvatarUrl = session.avatarUrl
    }

    WechatAccountSessionEffect(
        session = session,
        dependencies = dependencies,
        state = state,
        actions = WechatAccountSessionEffectActions(
            onStateChange = { state = it },
            onFailureMessage = ::fail,
            onFailureResult = ::handleFailure,
            onCommit = { commit(it) }
        )
    )

    WechatAccountProfileContent(
        session = session,
        uiState = WechatAccountProfileUiState(
            identityState = state,
            displayedAvatarUrl = displayedAvatarUrl,
            avatarError = avatarError,
            wechatAuthAvailable = authCoordinator != null
        ),
        avatarCache = dependencies.avatarCache,
        onAction = { profileActionHandler.handle(it, state) },
        modifier = modifier
    )

    WechatAccountAvatarEditor(
        session = session,
        deletionState = dependencies.deletionState,
        accountRepository = dependencies.accountRepository,
        state = WechatAvatarEditorState(
            showSourceDialog = showAvatarSourceDialog,
            pendingCameraUri = pendingCameraUri
        ),
        actions = WechatAvatarEditorActions(
            onStateChange = { next ->
                showAvatarSourceDialog = next.showSourceDialog
                pendingCameraUri = next.pendingCameraUri
            },
            onOperationStarted = {
                state = state.copy(operationInProgress = true, errorMessage = null)
                avatarError = null
            },
            onAvatarError = { avatarError = it },
            onFailureMessage = ::fail,
            onResult = { result ->
                when (result) {
                    is AccountRepositoryResult.Success -> {
                        displayedAvatarUrl = result.value.avatarUrl
                        avatarError = null
                        commit(result.value)
                    }
                    is AccountRepositoryResult.Failure -> {
                        avatarError = result.message
                        handleFailure(result)
                    }
                }
            }
        )
    )

    WechatAccountIdentityDialogHost(
        session = session,
        deletionState = dependencies.deletionState,
        accountRepository = dependencies.accountRepository,
        state = state,
        actions = WechatAccountIdentityActions(
            onStateChange = { state = it },
            currentState = { state },
            onOperationFinished = { state = state.copy(operationInProgress = false) },
            onFailureMessage = ::fail,
            onFailureResult = ::handleFailure,
            onHandleCredentials = { result, clearAvatar -> handleCredentials(result, clearAvatar) }
        )
    )
}
