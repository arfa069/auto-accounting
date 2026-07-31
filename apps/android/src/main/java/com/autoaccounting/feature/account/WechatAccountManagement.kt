package com.autoaccounting.feature.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.autoaccounting.api.MergePreviewResponseContract
import com.autoaccounting.api.IdentifierLinkPrepareResponseContract
import com.autoaccounting.api.AccountIdentifierParser
import com.autoaccounting.api.AccountIdentifierTypeContract
import com.autoaccounting.ui.components.Button
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.OutlinedTextField
import com.autoaccounting.ui.components.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    val sourceIdentifiers: List<com.autoaccounting.api.AccountIdentifierContract> = emptyList(),
    val sourceNickname: String? = null,
    val sourceWechatLinked: Boolean = false,
    val unlinkIdentifier: String = "",
    val confirmText: String = "",
    val operationInProgress: Boolean = false,
    val errorMessage: String? = null
)

@Composable
fun WechatAccountManagementPanel(
    session: AccountSession.SignedIn,
    deletionState: AccountDeletionUiState,
    accountRepository: AccountRepository,
    wechatAuthGateway: WechatAuthGateway?,
    wechatAuthCallback: WechatAuthCallback?,
    onWechatAuthCallbackConsumed: () -> Unit,
    persistSession: (AccountCredentials) -> Boolean,
    clearPersistedSession: () -> Boolean,
    avatarCache: WechatAvatarCache,
    onSessionVerified: (AccountCredentials) -> Unit,
    onInvalidSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    var state by remember { mutableStateOf(AccountIdentityUiState()) }
    var displayedAvatarUrl by remember { mutableStateOf(session.avatarUrl) }
    var avatarError by remember { mutableStateOf<String?>(null) }
    var showAvatarSourceDialog by rememberSaveable { mutableStateOf(false) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val authCoordinator = remember(wechatAuthGateway) {
        wechatAuthGateway?.let(::WechatAuthCoordinator)
    }
    fun fail(message: String) {
        state = state.copy(operationInProgress = false, errorMessage = message)
    }

    fun handleFailure(failure: AccountRepositoryResult.Failure) {
        state = state.copy(operationInProgress = false)
        if (failure.kind == AccountFailureKind.InvalidSession) {
            onInvalidSession()
        } else {
            fail(failure.message)
        }
    }

    suspend fun commit(credentials: AccountCredentials, clearAvatar: Boolean = false) {
        val committed = persistAccountSessionOrRevoke(
            credentials = credentials,
            accountRepository = accountRepository,
            persistSession = persistSession,
            clearPersistedSession = clearPersistedSession
        )
        if (committed) {
            if (clearAvatar) avatarCache.clear() else avatarCache.prepareUrl(credentials.avatarUrl)
            state = AccountIdentityUiState()
            onSessionVerified(credentials)
        } else {
            avatarCache.clear()
            state = AccountIdentityUiState(errorMessage = "无法安全保存新会话，已切换到本地模式")
            onInvalidSession()
        }
    }

    suspend fun handleCredentials(result: AccountRepositoryResult<AccountCredentials>, clearAvatar: Boolean = false) {
        when (result) {
            is AccountRepositoryResult.Success -> commit(result.value, clearAvatar)
            is AccountRepositoryResult.Failure -> handleFailure(result)
        }
    }

    fun submitAvatar(uri: android.net.Uri, deleteAfterRead: Boolean = false) {
        coroutineScope.launch {
            state = state.copy(operationInProgress = true, errorMessage = null)
            avatarError = null
            val avatarDataUrl = try {
                context.readCompressedAvatarDataUrl(uri)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                avatarError = "无法读取图片，请换一张图片重试"
                fail(avatarError!!)
                return@launch
            } finally {
                if (deleteAfterRead) context.deleteAvatarCapture(uri)
            }
            when (
                val result = accountRepository.updateAvatar(
                    credentials = session.toCredentials(deletionState),
                    avatarDataUrl = avatarDataUrl
                )
            ) {
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
    }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        submitAvatar(uri)
    }
    val avatarCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri = pendingCameraUri?.let(android.net.Uri::parse)
        pendingCameraUri = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (captured) {
            submitAvatar(uri, deleteAfterRead = true)
        } else {
            context.deleteAvatarCapture(uri)
        }
    }

    LaunchedEffect(session.avatarUrl) {
        displayedAvatarUrl = session.avatarUrl
    }

    LaunchedEffect(wechatAuthCallback, session.token) {
        val callback = wechatAuthCallback ?: return@LaunchedEffect
        if (callback.managementPurpose() != WechatAuthPurpose.LinkCurrentAccount) return@LaunchedEffect
        onWechatAuthCallbackConsumed()
        if (!callback.matchesSession(session.token)) {
            fail("登录状态已变化，请重新发起微信授权")
            return@LaunchedEffect
        }
        when (callback) {
            is WechatAuthCallback.Authorized -> {
                state = state.copy(operationInProgress = true, errorMessage = null)
                when (val result = accountRepository.exchangeWechatCode(callback.code, session.token)) {
                    is AccountRepositoryResult.Failure -> handleFailure(result)
                    is AccountRepositoryResult.Success -> when (val auth = result.value) {
                        is AccountWechatAuthResult.SignedIn -> commit(auth.credentials)
                        is AccountWechatAuthResult.MergeRequired -> state = AccountIdentityUiState(
                            page = AccountIdentityPage.Merge,
                            mergeTicket = auth.mergeTicket,
                            sourceIdentifiers = auth.sourceIdentifiers,
                            sourceNickname = auth.sourceNickname,
                            sourceWechatLinked = true
                        )
                        is AccountWechatAuthResult.RegistrationRequired -> fail("微信绑定状态异常，请重新授权")
                    }
                }
            }
            is WechatAuthCallback.Cancelled -> fail("已取消微信授权")
            is WechatAuthCallback.Denied -> fail("微信授权已拒绝")
            is WechatAuthCallback.Failed -> fail("微信授权失败，请重试")
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "个人信息",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // 1. 头像
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("account-avatar"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("头像", style = MaterialTheme.typography.bodyLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WechatAvatar(displayedAvatarUrl, avatarCache)
                    TextButton(
                        onClick = { showAvatarSourceDialog = true },
                        enabled = !state.operationInProgress,
                        modifier = Modifier.testTag("btn-edit-avatar")
                    ) {
                        Text(if (state.operationInProgress) "上传中…" else "修改 ›")
                    }
                }
            }
            avatarError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("avatar-error")
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // 2. 昵称
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("edit-nickname"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("昵称", style = MaterialTheme.typography.bodyLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = session.nickname ?: session.username ?: "未设置",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            state = state.copy(
                                page = AccountIdentityPage.EditNickname,
                                editNicknameInput = session.nickname.orEmpty()
                            )
                        },
                        enabled = !state.operationInProgress,
                        modifier = Modifier.testTag("btn-edit-nickname")
                    ) {
                        Text("修改 ›")
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // 3. ID
            val clipboardManager = LocalClipboardManager.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ID", style = MaterialTheme.typography.bodyLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val fullId = session.accountUuid
                    val displayId = fullId?.maskAccountUuidForDisplay() ?: "暂不可用"
                    Text(
                        text = displayId,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            fullId?.let { clipboardManager.setText(AnnotatedString(it)) }
                        },
                        enabled = fullId != null,
                        modifier = Modifier.testTag("copy-account-id")
                    ) {
                        Text("复制", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // 4. 手机号
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("手机号", style = MaterialTheme.typography.bodyLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (session.phone != null) {
                        Text(
                            text = session.phone!!.maskPhoneForIdentity(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                state = AccountIdentityUiState(
                                    page = AccountIdentityPage.AttachIdentifier,
                                    targetIdentifierType = AccountIdentifierTypeContract.PHONE,
                                    replaceExistingIdentifier = true
                                )
                            },
                            enabled = !state.operationInProgress,
                            modifier = Modifier.testTag("replace-phone")
                        ) {
                            Text("换绑 ›")
                        }
                    } else {
                        TextButton(
                            onClick = {
                                state = AccountIdentityUiState(
                                    page = AccountIdentityPage.AttachIdentifier,
                                    targetIdentifierType = AccountIdentifierTypeContract.PHONE
                                )
                            },
                            enabled = !state.operationInProgress,
                            modifier = Modifier.testTag("bind-phone")
                        ) {
                            Text("立即绑定 ›")
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // 5. 邮箱
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("邮箱", style = MaterialTheme.typography.bodyLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (session.email != null) {
                        Text(
                            text = session.email!!.maskEmailForIdentity(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                state = AccountIdentityUiState(
                                    page = AccountIdentityPage.AttachIdentifier,
                                    targetIdentifierType = AccountIdentifierTypeContract.EMAIL,
                                    replaceExistingIdentifier = true
                                )
                            },
                            enabled = !state.operationInProgress,
                            modifier = Modifier.testTag("replace-email")
                        ) {
                            Text("换绑 ›")
                        }
                    } else {
                        TextButton(
                            onClick = {
                                state = AccountIdentityUiState(
                                    page = AccountIdentityPage.AttachIdentifier,
                                    targetIdentifierType = AccountIdentifierTypeContract.EMAIL
                                )
                            },
                            enabled = !state.operationInProgress,
                            modifier = Modifier.testTag("bind-email")
                        ) {
                            Text("立即绑定 ›")
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // 6. 微信
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("微信", style = MaterialTheme.typography.bodyLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (session.wechatLinked) {
                        Text(
                            text = "已绑定",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                if (session.phone == null && session.email == null && session.username == null) {
                                    fail("最后一种登录方式不可解绑")
                                } else {
                                    state = AccountIdentityUiState(
                                        page = AccountIdentityPage.UnlinkWechat,
                                        unlinkIdentifier = session.phone ?: session.email.orEmpty()
                                    )
                                }
                            },
                            enabled = !state.operationInProgress,
                            modifier = Modifier.testTag("unlink-wechat")
                        ) {
                            Text("换绑/解绑 ›")
                        }
                    } else {
                        TextButton(
                            onClick = {
                                if (state.operationInProgress || authCoordinator == null) return@TextButton
                                when (
                                    authCoordinator.startAuthorization(
                                        agreementAccepted = true,
                                        purpose = WechatAuthPurpose.LinkCurrentAccount,
                                        sessionFingerprint = wechatSessionFingerprint(session.token)
                                    )
                                ) {
                                    WechatAuthLaunchResult.Started -> state = state.copy(operationInProgress = true, errorMessage = null)
                                    WechatAuthLaunchResult.NotInstalled -> fail("未检测到微信，请先安装微信")
                                    WechatAuthLaunchResult.VersionUnsupported -> fail("当前微信版本过低，请升级后重试")
                                    WechatAuthLaunchResult.NotConfigured -> fail("微信登录暂未配置")
                                    WechatAuthLaunchResult.SendFailed -> fail("无法启动微信授权，请稍后重试")
                                    WechatAuthLaunchResult.AgreementRequired -> fail("请先同意用户协议和隐私政策")
                                }
                            },
                            enabled = !state.operationInProgress && authCoordinator != null,
                            modifier = Modifier.testTag("bind-wechat")
                        ) {
                            Text("立即绑定 ›")
                        }
                    }
                }
            }

            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("identity-error"))
            }
        }
    }

    if (showAvatarSourceDialog) {
        AlertDialog(
            onDismissRequest = { showAvatarSourceDialog = false },
            title = { Text("修改头像") },
            text = { Text("请选择头像来源") },
            confirmButton = {
                Button(
                    onClick = {
                        showAvatarSourceDialog = false
                        val uri = runCatching { context.createAvatarCaptureUri() }
                            .getOrElse {
                                avatarError = "无法启动相机，请稍后重试"
                                return@Button
                            }
                        pendingCameraUri = uri.toString()
                        avatarCamera.launch(uri)
                    },
                    modifier = Modifier.testTag("take-avatar-photo")
                ) {
                    Text("拍照")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAvatarSourceDialog = false
                        avatarPicker.launch("image/*")
                    },
                    modifier = Modifier.testTag("pick-avatar-gallery")
                ) {
                    Text("从相册选择")
                }
            }
        )
    }

    when (state.page) {
        AccountIdentityPage.EditNickname -> EditNicknameDialog(
            initialValue = state.editNicknameInput,
            operationInProgress = state.operationInProgress,
            onDismiss = { state = AccountIdentityUiState() },
            onConfirm = { newNickname ->
                coroutineScope.launch {
                    state = state.copy(operationInProgress = true, errorMessage = null)
                    handleCredentials(
                        accountRepository.updateNickname(
                            credentials = session.toCredentials(deletionState),
                            nickname = newNickname
                        )
                    )
                }
            }
        )

        AccountIdentityPage.AttachIdentifier -> AccountIdentityDialog(
            title = when (state.targetIdentifierType) {
                AccountIdentifierTypeContract.PHONE -> if (state.replaceExistingIdentifier) "换绑手机号" else "绑定手机号"
                AccountIdentifierTypeContract.EMAIL -> if (state.replaceExistingIdentifier) "换绑邮箱" else "绑定邮箱"
                else -> "绑定手机号或邮箱"
            },
            state = state,
            allowPasswordMerge = !state.replaceExistingIdentifier &&
                session.identifiers.isEmpty() && session.wechatLinked,
            onStateChange = { state = it },
            onRequestSms = {
                coroutineScope.launch {
                    val parsedType = runCatching { AccountIdentifierParser.parse(state.phone).type }.getOrNull()
                    if (parsedType == null || parsedType == AccountIdentifierTypeContract.USERNAME ||
                        (state.targetIdentifierType != null && parsedType != state.targetIdentifierType)
                    ) return@launch fail(state.expectedIdentifierError())
                    state = state.copy(operationInProgress = true, errorMessage = null)
                    when (
                        val result = accountRepository.prepareIdentifierLink(
                            session.token,
                            state.phone,
                            state.replaceExistingIdentifier
                        )
                    ) {
                        is AccountRepositoryResult.Success -> state = when (val prepared = result.value) {
                            IdentifierLinkPrepareResponseContract.AlreadyLinked -> AccountIdentityUiState()
                            is IdentifierLinkPrepareResponseContract.LinkTicketIssued -> state.copy(
                                phoneTicket = prepared.linkTicket,
                                operationInProgress = false,
                                errorMessage = null
                            )
                            is IdentifierLinkPrepareResponseContract.MergeRequired -> state.copy(
                                operationInProgress = false,
                                errorMessage = "该标识已属于其他账号，不能绑定或合并"
                            )
                        }
                        is AccountRepositoryResult.Failure -> handleFailure(result)
                    }
                }
            },
            onConfirm = {
                coroutineScope.launch {
                    val parsedType = runCatching { AccountIdentifierParser.parse(state.phone).type }.getOrNull()
                    if (parsedType == null || parsedType == AccountIdentifierTypeContract.USERNAME ||
                        (state.targetIdentifierType != null && parsedType != state.targetIdentifierType)
                    ) return@launch fail(state.expectedIdentifierError())
                    state = state.copy(operationInProgress = true, errorMessage = null)
                    if (state.identifierAttachMethod == IdentifierAttachMethod.PasswordMerge) {
                        when (
                            val result = accountRepository.prepareMergeWithIdentifierPassword(
                                session.token,
                                state.phone,
                                state.password
                            )
                        ) {
                            is AccountRepositoryResult.Success -> state = result.value.toMergeState()
                            is AccountRepositoryResult.Failure -> handleFailure(result)
                        }
                    } else {
                        val ticket = state.phoneTicket ?: return@launch fail("请先获取验证码")
                        if (session.identifiers.isEmpty() && session.wechatLinked) {
                            state = state.copy(
                                page = AccountIdentityPage.SetPhonePassword,
                                operationInProgress = false,
                                errorMessage = null
                            )
                        } else {
                            handleCredentials(
                                accountRepository.completeIdentifierLink(
                                    token = session.token,
                                    linkTicket = ticket,
                                    code = state.code
                                )
                            )
                        }
                    }
                }
            }
        )
        AccountIdentityPage.SetPhonePassword -> SimplePasswordDialog(
            title = "设置登录密码",
            password = state.password,
            operationInProgress = state.operationInProgress,
            errorMessage = state.errorMessage,
            onPasswordChange = { state = state.copy(password = it, errorMessage = null) },
            onDismiss = { if (!state.operationInProgress) state = AccountIdentityUiState() },
            onConfirm = {
                coroutineScope.launch {
                    val ticket = state.phoneTicket ?: return@launch fail("绑定票据已失效")
                    state = state.copy(operationInProgress = true, errorMessage = null)
                    handleCredentials(
                        accountRepository.completeIdentifierLink(
                            token = session.token,
                            linkTicket = ticket,
                            code = state.code,
                            password = state.password
                        )
                    )
                }
            }
        )
        AccountIdentityPage.Merge -> MergeConfirmationDialog(
            session = session,
            state = state,
            onConfirmTextChange = { state = state.copy(confirmText = it, errorMessage = null) },
            onDismiss = { if (!state.operationInProgress) state = AccountIdentityUiState() },
            onConfirm = {
                coroutineScope.launch {
                    val ticket = state.mergeTicket ?: return@launch fail("合并票据已失效")
                    state = state.copy(operationInProgress = true, errorMessage = null)
                    handleCredentials(accountRepository.confirmMerge(session.token, ticket, state.confirmText))
                }
            }
        )
        AccountIdentityPage.UnlinkWechat -> UnlinkWechatDialog(
            state = state,
            availableIdentifiers = session.identifiers.filter {
                it.type == com.autoaccounting.api.AccountIdentifierTypeContract.PHONE ||
                    it.type == com.autoaccounting.api.AccountIdentifierTypeContract.EMAIL
            },
            onStateChange = { state = it },
            onRequestSms = {
                coroutineScope.launch {
                    val identifier = state.unlinkIdentifier.takeIf { it.isNotBlank() }
                        ?: return@launch fail("请选择手机号或邮箱")
                    state = state.copy(operationInProgress = true, errorMessage = null)
                    when (
                        val result = accountRepository.requestVerificationCode(
                            identifier = identifier,
                            purpose = AccountVerificationPurpose.WechatUnlink,
                            bearerToken = session.token
                        )
                    ) {
                        is AccountRepositoryResult.Success -> state = state.copy(operationInProgress = false)
                        is AccountRepositoryResult.Failure -> handleFailure(result)
                    }
                }
            },
            onConfirm = {
                coroutineScope.launch {
                    state = state.copy(operationInProgress = true, errorMessage = null)
                    val result = when (state.unlinkMethod) {
                        UnlinkMethod.Password -> accountRepository.unlinkWechatWithPassword(session.token, state.password)
                        UnlinkMethod.Code -> accountRepository.unlinkWechatWithCode(
                            session.token,
                            state.unlinkIdentifier,
                            state.code
                        )
                    }
                    handleCredentials(result, clearAvatar = true)
                }
            }
        )
        AccountIdentityPage.Idle -> Unit
    }
}
