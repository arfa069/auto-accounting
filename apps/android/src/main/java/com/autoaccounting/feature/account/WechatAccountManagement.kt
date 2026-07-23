package com.autoaccounting.feature.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.autoaccounting.api.MergePreviewResponseContract
import com.autoaccounting.api.PhoneLinkPrepareResponseContract
import com.autoaccounting.ui.components.Button
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.OutlinedTextField
import com.autoaccounting.ui.components.TextButton
import kotlinx.coroutines.launch

private enum class AccountIdentityPage {
    Idle,
    AttachPhone,
    SetPhonePassword,
    Merge,
    UnlinkWechat
}

private enum class PhoneAttachMethod {
    Sms,
    PasswordMerge
}

private enum class UnlinkMethod {
    Password,
    Sms
}

private data class AccountIdentityUiState(
    val page: AccountIdentityPage = AccountIdentityPage.Idle,
    val phoneAttachMethod: PhoneAttachMethod = PhoneAttachMethod.Sms,
    val unlinkMethod: UnlinkMethod = UnlinkMethod.Password,
    val phone: String = "",
    val code: String = "",
    val password: String = "",
    val phoneTicket: String? = null,
    val mergeTicket: String? = null,
    val sourcePhone: String? = null,
    val sourceNickname: String? = null,
    val sourceWechatLinked: Boolean = false,
    val confirmText: String = "",
    val operationInProgress: Boolean = false,
    val errorMessage: String? = null
)

@Composable
fun WechatAccountManagementPanel(
    session: AccountSession.SignedIn,
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
    val coroutineScope = rememberCoroutineScope()
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
                            sourcePhone = auth.sourcePhone,
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
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WechatAvatar(session.avatarUrl, avatarCache)
                Column {
                    Text(session.nickname ?: session.phone?.maskPhoneForIdentity() ?: "微信用户", fontWeight = FontWeight.SemiBold)
                    session.phone?.let { Text("手机号：${it.maskPhoneForIdentity()}") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session.phone != null) Text("手机号登录")
                if (session.wechatLinked) Text("微信登录")
            }
            if (!session.wechatLinked && session.phone != null && authCoordinator != null) {
                OutlinedButton(
                    onClick = {
                        if (state.operationInProgress) return@OutlinedButton
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
                    enabled = !state.operationInProgress,
                    modifier = Modifier.fillMaxWidth().testTag("bind-wechat")
                ) { Text("绑定微信") }
            }
            if (session.phone == null && session.wechatLinked) {
                OutlinedButton(
                    onClick = { state = AccountIdentityUiState(page = AccountIdentityPage.AttachPhone) },
                    enabled = !state.operationInProgress,
                    modifier = Modifier.fillMaxWidth().testTag("bind-phone")
                ) { Text("绑定手机号或合并账号") }
            }
            if (session.phone != null && session.wechatLinked) {
                OutlinedButton(
                    onClick = { state = AccountIdentityUiState(page = AccountIdentityPage.UnlinkWechat) },
                    enabled = !state.operationInProgress,
                    modifier = Modifier.fillMaxWidth().testTag("unlink-wechat")
                ) { Text("解绑微信") }
            }
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("identity-error"))
            }
        }
    }

    when (state.page) {
        AccountIdentityPage.AttachPhone -> AccountIdentityDialog(
            title = "绑定手机号或合并账号",
            state = state,
            onStateChange = { state = it },
            onRequestSms = {
                coroutineScope.launch {
                    if (!state.phone.isValidIdentityPhone()) return@launch fail("请输入 11 位手机号")
                    state = state.copy(operationInProgress = true, errorMessage = null)
                    when (
                        val result = accountRepository.requestSmsCode(
                            phone = state.phone,
                            purpose = AccountSmsPurpose.PhoneLink,
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
                    if (!state.phone.isValidIdentityPhone()) return@launch fail("请输入 11 位手机号")
                    state = state.copy(operationInProgress = true, errorMessage = null)
                    if (state.phoneAttachMethod == PhoneAttachMethod.PasswordMerge) {
                        when (
                            val result = accountRepository.prepareMergeWithPhonePassword(
                                session.token,
                                state.phone,
                                state.password
                            )
                        ) {
                            is AccountRepositoryResult.Success -> state = result.value.toMergeState()
                            is AccountRepositoryResult.Failure -> handleFailure(result)
                        }
                    } else {
                        when (val result = accountRepository.preparePhoneLink(session.token, state.phone, state.code)) {
                            is AccountRepositoryResult.Failure -> handleFailure(result)
                            is AccountRepositoryResult.Success -> state = when (val prepared = result.value) {
                                is PhoneLinkPrepareResponseContract.PhoneTicketIssued -> state.copy(
                                    page = AccountIdentityPage.SetPhonePassword,
                                    phoneTicket = prepared.phoneTicket,
                                    operationInProgress = false,
                                    errorMessage = null
                                )
                                is PhoneLinkPrepareResponseContract.MergeRequired -> AccountIdentityUiState(
                                    page = AccountIdentityPage.Merge,
                                    mergeTicket = prepared.mergeTicket,
                                    sourcePhone = prepared.sourcePhone,
                                    sourceWechatLinked = prepared.sourceWechatLinked
                                )
                            }
                        }
                    }
                }
            }
        )
        AccountIdentityPage.SetPhonePassword -> SimplePasswordDialog(
            title = "设置手机号登录密码",
            password = state.password,
            operationInProgress = state.operationInProgress,
            errorMessage = state.errorMessage,
            onPasswordChange = { state = state.copy(password = it, errorMessage = null) },
            onDismiss = { if (!state.operationInProgress) state = AccountIdentityUiState() },
            onConfirm = {
                coroutineScope.launch {
                    val ticket = state.phoneTicket ?: return@launch fail("手机号票据已失效")
                    state = state.copy(operationInProgress = true, errorMessage = null)
                    handleCredentials(accountRepository.completePhoneLink(session.token, ticket, state.password))
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
            onStateChange = { state = it },
            onRequestSms = {
                coroutineScope.launch {
                    val phone = session.phone ?: return@launch fail("最后一种登录方式不可解绑")
                    state = state.copy(operationInProgress = true, errorMessage = null)
                    when (
                        val result = accountRepository.requestSmsCode(
                            phone = phone,
                            purpose = AccountSmsPurpose.WechatUnlink,
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
                        UnlinkMethod.Sms -> accountRepository.unlinkWechatWithSms(session.token, state.code)
                    }
                    handleCredentials(result, clearAvatar = true)
                }
            }
        )
        AccountIdentityPage.Idle -> Unit
    }
}

@Composable
private fun AccountIdentityDialog(
    title: String,
    state: AccountIdentityUiState,
    onStateChange: (AccountIdentityUiState) -> Unit,
    onRequestSms: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.operationInProgress) onStateChange(AccountIdentityUiState()) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本机账本不变；若手机号已有账号，将进入账号合并确认。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MethodButton("短信验证", state.phoneAttachMethod == PhoneAttachMethod.Sms) {
                        onStateChange(state.copy(phoneAttachMethod = PhoneAttachMethod.Sms, errorMessage = null))
                    }
                    MethodButton("密码合并", state.phoneAttachMethod == PhoneAttachMethod.PasswordMerge) {
                        onStateChange(state.copy(phoneAttachMethod = PhoneAttachMethod.PasswordMerge, errorMessage = null))
                    }
                }
                OutlinedTextField(
                    value = state.phone,
                    onValueChange = { onStateChange(state.copy(phone = it, errorMessage = null)) },
                    label = { Text("手机号") },
                    modifier = Modifier.fillMaxWidth().testTag("identity-phone")
                )
                if (state.phoneAttachMethod == PhoneAttachMethod.Sms) {
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = { onStateChange(state.copy(code = it, errorMessage = null)) },
                        label = { Text("验证码") },
                        modifier = Modifier.fillMaxWidth().testTag("identity-code")
                    )
                    OutlinedButton(onClick = onRequestSms, enabled = !state.operationInProgress) { Text("获取验证码") }
                } else {
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = { onStateChange(state.copy(password = it, errorMessage = null)) },
                        label = { Text("来源账号密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("identity-password")
                    )
                }
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !state.operationInProgress) { Text("继续") }
        },
        dismissButton = {
            TextButton(
                onClick = { onStateChange(AccountIdentityUiState()) },
                enabled = !state.operationInProgress
            ) { Text("取消") }
        }
    )
}

@Composable
private fun SimplePasswordDialog(
    title: String,
    password: String,
    operationInProgress: Boolean,
    errorMessage: String?,
    onPasswordChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("新密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("phone-link-password")
                )
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = !operationInProgress) { Text("完成绑定") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !operationInProgress) { Text("取消") } }
    )
}

@Composable
private fun MergeConfirmationDialog(
    session: AccountSession.SignedIn,
    state: AccountIdentityUiState,
    onConfirmTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认合并账号") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前账号（保留）：${session.phone?.maskPhoneForIdentity() ?: session.nickname ?: "微信账号"}")
                Text("来源账号（删除）：${state.sourcePhone?.maskPhoneForIdentity() ?: state.sourceNickname ?: "微信账号"}")
                Text("当前云配置优先，来源独有开关补入；来源 AI 日志将删除。")
                Text("本机账本不变，来源云账号将被删除，操作无法自动撤销。")
                OutlinedTextField(
                    value = state.confirmText,
                    onValueChange = onConfirmTextChange,
                    label = { Text("输入“合并账号”") },
                    modifier = Modifier.fillMaxWidth().testTag("merge-confirm-text")
                )
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !state.operationInProgress && state.confirmText == "合并账号",
                modifier = Modifier.testTag("confirm-account-merge")
            ) { Text("确认合并") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.operationInProgress) { Text("取消") } }
    )
}

@Composable
private fun UnlinkWechatDialog(
    state: AccountIdentityUiState,
    onStateChange: (AccountIdentityUiState) -> Unit,
    onRequestSms: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.operationInProgress) onStateChange(AccountIdentityUiState()) },
        title = { Text("解绑微信") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("解绑后仍可使用当前手机号登录；旧 Session 将全部失效。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MethodButton("密码验证", state.unlinkMethod == UnlinkMethod.Password) {
                        onStateChange(state.copy(unlinkMethod = UnlinkMethod.Password, errorMessage = null))
                    }
                    MethodButton("短信验证", state.unlinkMethod == UnlinkMethod.Sms) {
                        onStateChange(state.copy(unlinkMethod = UnlinkMethod.Sms, errorMessage = null))
                    }
                }
                if (state.unlinkMethod == UnlinkMethod.Password) {
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = { onStateChange(state.copy(password = it, errorMessage = null)) },
                        label = { Text("当前密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("unlink-password")
                    )
                } else {
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = { onStateChange(state.copy(code = it, errorMessage = null)) },
                        label = { Text("验证码") },
                        modifier = Modifier.fillMaxWidth().testTag("unlink-code")
                    )
                    OutlinedButton(onClick = onRequestSms, enabled = !state.operationInProgress) { Text("获取验证码") }
                }
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !state.operationInProgress,
                modifier = Modifier.testTag("confirm-unlink-wechat")
            ) { Text("确认解绑") }
        },
        dismissButton = {
            TextButton(
                onClick = { onStateChange(AccountIdentityUiState()) },
                enabled = !state.operationInProgress
            ) { Text("取消") }
        }
    )
}

@Composable
private fun MethodButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

private fun MergePreviewResponseContract.toMergeState(): AccountIdentityUiState = AccountIdentityUiState(
    page = AccountIdentityPage.Merge,
    mergeTicket = mergeTicket,
    sourcePhone = sourcePhone,
    sourceNickname = sourceNickname,
    sourceWechatLinked = sourceWechatLinked
)

private fun String.isValidIdentityPhone(): Boolean = Regex("^\\d{11}$").matches(this)

private fun String.maskPhoneForIdentity(): String =
    if (length == 11) replaceRange(3, 7, "****") else this

private fun WechatAuthCallback.managementPurpose(): WechatAuthPurpose = when (this) {
    is WechatAuthCallback.Authorized -> purpose
    is WechatAuthCallback.Cancelled -> purpose
    is WechatAuthCallback.Denied -> purpose
    is WechatAuthCallback.Failed -> purpose
}
