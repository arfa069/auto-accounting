package com.bks.feature.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bks.ui.components.Button
import com.bks.ui.components.OutlinedButton
import com.bks.ui.components.TextButton
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun AccountManagementScreen(
    session: AccountSession,
    runtimeState: AccountRuntimeState,
    deletionState: AccountDeletionUiState,
    accountRepository: AccountRepository,
    onSignInOrRegister: () -> Unit,
    onSessionVerified: (AccountCredentials) -> Unit,
    onInvalidSession: () -> Unit,
    persistSession: (AccountCredentials) -> Boolean = { true },
    clearPersistedSession: () -> Boolean,
    wechatAuthGateway: WechatAuthGateway? = null,
    wechatAuthCallback: WechatAuthCallback? = null,
    onWechatAuthCallbackConsumed: () -> Unit = {},
    avatarCacheOverride: WechatAvatarCache? = null,
    onSignedOut: () -> Unit,
    onDeletionStateChange: (AccountDeletionUiState) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var operationInProgress by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDeletionConfirmation by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val avatarCache = avatarCacheOverride ?: rememberWechatAvatarCache()

    fun handleFailure(failure: AccountRepositoryResult.Failure) {
        operationInProgress = false
        if (failure.kind == AccountFailureKind.InvalidSession) {
            onInvalidSession()
        } else {
            errorMessage = failure.message
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("返回") }
        Text(
            text = "账户管理",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        when (session) {
            AccountSession.LocalMode -> {
                Text("当前使用本地模式，账本仅保存在本机。")
                Button(
                    onClick = onSignInOrRegister,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("登录或注册")
                }
            }

            is AccountSession.SignedIn -> {
                if (runtimeState.status == AccountRuntimeStatus.OfflineUnverified) {
                    OfflineAccountConnectionCard(
                        onRetry = {
                            if (operationInProgress) return@OfflineAccountConnectionCard
                            operationInProgress = true
                            errorMessage = null
                            coroutineScope.launch {
                                when (
                                    val result = accountRepository.verifySession(
                                        AccountCredentials(
                                            accountId = session.accountId,
                                            accountUuid = session.accountUuid,
                                            primaryIdentifier = session.primaryIdentifier,
                                            identifiers = session.identifiers,
                                            rawPhone = session.rawPhone,
                                            token = session.token,
                                            deletionState = deletionState,
                                            wechatLinked = session.wechatLinked,
                                            nickname = session.nickname,
                                            avatarUrl = session.avatarUrl
                                        )
                                    )
                                ) {
                                    is AccountRepositoryResult.Success -> {
                                        operationInProgress = false
                                        if (
                                            !persistRefreshedAccountSession(
                                                credentials = result.value,
                                                persistSession = persistSession,
                                                onSessionVerified = onSessionVerified
                                            )
                                        ) {
                                            errorMessage = "账号资料已验证，但无法保存到本机，请重试"
                                        }
                                    }
                                    is AccountRepositoryResult.Failure -> handleFailure(result)
                                }
                            }
                        },
                        retryEnabled = !operationInProgress
                    )
                }
                WechatAccountManagementPanel(
                    session = session,
                    dependencies = WechatAccountManagementDependencies(
                        deletionState = deletionState,
                        accountRepository = accountRepository,
                        wechatAuthGateway = wechatAuthGateway,
                        wechatAuthCallback = wechatAuthCallback,
                        avatarCache = avatarCache,
                        sessionActions = WechatAccountSessionActions(
                            onWechatAuthCallbackConsumed = onWechatAuthCallbackConsumed,
                            persistSession = persistSession,
                            clearPersistedSession = clearPersistedSession,
                            onSessionVerified = onSessionVerified,
                            onInvalidSession = onInvalidSession
                        )
                    )
                )
                OutlinedButton(
                    onClick = {
                        if (operationInProgress) return@OutlinedButton
                        operationInProgress = true
                        errorMessage = null
                        coroutineScope.launch {
                            when (val result = accountRepository.signOut(session.token)) {
                                is AccountRepositoryResult.Success -> {
                                    if (clearPersistedSession()) {
                                        operationInProgress = false
                                        onSignedOut()
                                    } else {
                                        operationInProgress = false
                                        errorMessage = "服务端会话已撤销，但本机登录状态清理失败，请重启应用"
                                    }
                                }
                                is AccountRepositoryResult.Failure -> handleFailure(result)
                            }
                        }
                    },
                    enabled = !operationInProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("account-sign-out")
                ) {
                    Text(if (operationInProgress) "处理中…" else "退出登录")
                }
                AccountDeletionCard(
                    state = deletionState,
                    accountVerified = runtimeState.accountOperationsAllowed,
                    operationEnabled = runtimeState.accountOperationsAllowed && !operationInProgress,
                    onRequest = { showDeletionConfirmation = true },
                    onCancel = {
                        operationInProgress = true
                        errorMessage = null
                        coroutineScope.launch {
                            when (val result = accountRepository.cancelDeletion(session.token)) {
                                is AccountRepositoryResult.Success -> {
                                    operationInProgress = false
                                    onDeletionStateChange(AccountDeletionUiState())
                                }
                                is AccountRepositoryResult.Failure -> handleFailure(result)
                            }
                        }
                    }
                )
            }
        }
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("account-error"))
        }
    }

    if (showDeletionConfirmation && session is AccountSession.SignedIn) {
        AlertDialog(
            onDismissRequest = { if (!operationInProgress) showDeletionConfirmation = false },
            title = { Text("申请注销账号？") },
            text = {
                Text(
                    "申请后进入七天冷静期。期满将删除云端账号、注册设备、云端配置和 AI 分类日志；本机账本不会被删除。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (operationInProgress) return@Button
                        operationInProgress = true
                        errorMessage = null
                        coroutineScope.launch {
                            when (val result = accountRepository.requestDeletion(session.token)) {
                                is AccountRepositoryResult.Success -> {
                                    operationInProgress = false
                                    showDeletionConfirmation = false
                                    onDeletionStateChange(result.value)
                                }
                                is AccountRepositoryResult.Failure -> {
                                    showDeletionConfirmation = false
                                    handleFailure(result)
                                }
                            }
                        }
                    },
                    enabled = !operationInProgress,
                    modifier = Modifier.testTag("confirm-account-deletion")
                ) {
                    Text(if (operationInProgress) "提交中…" else "确认申请")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeletionConfirmation = false },
                    enabled = !operationInProgress
                ) { Text("取消") }
            }
        )
    }
}

@Composable
private fun OfflineAccountConnectionCard(
    onRetry: () -> Unit,
    retryEnabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                AccountRuntimeStatus.OfflineUnverified.connectionLabel(),
                modifier = Modifier.testTag("account-connection-status")
            )
            OutlinedButton(onClick = onRetry, enabled = retryEnabled) {
                Text("重新验证")
            }
        }
    }
}

@Composable
private fun AccountDeletionCard(
    state: AccountDeletionUiState,
    accountVerified: Boolean,
    operationEnabled: Boolean,
    onRequest: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("账户注销", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (state.isPending) {
                Text("注销冷静期中，云端写入已暂停。")
                state.finalDeletionAtEpochMillis?.let { deadline ->
                    Text("预计最终注销时间：${deadline.formatDateTime()}")
                }
                OutlinedButton(
                    onClick = onCancel,
                    enabled = operationEnabled,
                    modifier = Modifier.testTag("cancel-account-deletion")
                ) { Text("取消注销") }
            } else {
                Text("注销会删除云端账号数据；本机账本不受影响，也不会被重新分配。")
                Button(
                    onClick = onRequest,
                    enabled = operationEnabled,
                    modifier = Modifier.testTag("request-account-deletion")
                ) { Text("申请注销账号") }
            }
            if (!accountVerified) {
                Text("账号尚未完成在线验证，暂不能执行注销操作。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun AccountRuntimeStatus.connectionLabel(): String = when (this) {
    AccountRuntimeStatus.LocalMode -> "本地模式"
    AccountRuntimeStatus.Validating -> "正在验证账号连接"
    AccountRuntimeStatus.Verified -> "账号服务已连接"
    AccountRuntimeStatus.OfflineUnverified -> "离线使用 · 账号尚未验证"
    AccountRuntimeStatus.DeletionCoolingOff -> "账号服务已连接 · 注销冷静期"
}

private fun Long.formatDateTime(): String = DateFormat.getDateTimeInstance(
    DateFormat.MEDIUM,
    DateFormat.SHORT
).format(Date(this))
