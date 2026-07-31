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

@Composable
internal fun AccountIdentityDialog(
    title: String,
    state: AccountIdentityUiState,
    allowPasswordMerge: Boolean,
    onStateChange: (AccountIdentityUiState) -> Unit,
    onRequestSms: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.operationInProgress) onStateChange(AccountIdentityUiState()) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (state.replaceExistingIdentifier) {
                        "验证新标识后将替换当前标识；本机账本不受影响。"
                    } else {
                        "绑定不会改变本机账本；已属于其他密码账号的标识不能转移或合并。"
                    }
                )
                if (allowPasswordMerge) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MethodButton("验证码绑定", state.identifierAttachMethod == IdentifierAttachMethod.Sms) {
                        onStateChange(state.copy(identifierAttachMethod = IdentifierAttachMethod.Sms, errorMessage = null))
                    }
                    MethodButton("绑定已有账号", state.identifierAttachMethod == IdentifierAttachMethod.PasswordMerge) {
                        onStateChange(state.copy(identifierAttachMethod = IdentifierAttachMethod.PasswordMerge, errorMessage = null))
                    }
                }
                OutlinedTextField(
                    value = state.phone,
                    onValueChange = {
                        onStateChange(
                            state.copy(
                                phone = it,
                                code = "",
                                phoneTicket = null,
                                errorMessage = null
                            )
                        )
                    },
                    label = {
                        Text(
                            when (state.targetIdentifierType) {
                                AccountIdentifierTypeContract.PHONE -> "新手机号"
                                AccountIdentifierTypeContract.EMAIL -> "新邮箱"
                                else -> "手机号或邮箱"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("identity-phone")
                )
                if (state.identifierAttachMethod == IdentifierAttachMethod.Sms) {
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
internal fun SimplePasswordDialog(
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
internal fun MergeConfirmationDialog(
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
                Text("来源账号（删除）：${state.sourceIdentifiers.identitySummary() ?: state.sourceNickname ?: "微信账号"}")
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
internal fun UnlinkWechatDialog(
    state: AccountIdentityUiState,
    availableIdentifiers: List<com.autoaccounting.api.AccountIdentifierContract>,
    onStateChange: (AccountIdentityUiState) -> Unit,
    onRequestSms: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.operationInProgress) onStateChange(AccountIdentityUiState()) },
        title = { Text("解绑微信") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("解绑后仍可使用已绑定账号登录；旧 Session 将全部失效。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MethodButton("密码验证", state.unlinkMethod == UnlinkMethod.Password) {
                        onStateChange(state.copy(unlinkMethod = UnlinkMethod.Password, errorMessage = null))
                    }
                    MethodButton("验证码验证", state.unlinkMethod == UnlinkMethod.Code) {
                        onStateChange(state.copy(unlinkMethod = UnlinkMethod.Code, errorMessage = null))
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
                    if (availableIdentifiers.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            availableIdentifiers.forEach { identifier ->
                                val label = when (identifier.type) {
                                    com.autoaccounting.api.AccountIdentifierTypeContract.PHONE -> "手机验证码"
                                    com.autoaccounting.api.AccountIdentifierTypeContract.EMAIL -> "邮箱验证码"
                                    else -> return@forEach
                                }
                                MethodButton(label, state.unlinkIdentifier == identifier.value) {
                                    onStateChange(
                                        state.copy(
                                            unlinkIdentifier = identifier.value,
                                            code = "",
                                            errorMessage = null
                                        )
                                    )
                                }
                            }
                        }
                    }
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
internal fun MethodButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

internal fun MergePreviewResponseContract.toMergeState(): AccountIdentityUiState = AccountIdentityUiState(
    page = AccountIdentityPage.Merge,
    mergeTicket = mergeTicket,
    sourceIdentifiers = sourceIdentifiers,
    sourceNickname = sourceNickname,
    sourceWechatLinked = sourceWechatLinked
)

internal fun String.isValidIdentityContact(): Boolean = runCatching {
    val type = com.autoaccounting.api.AccountIdentifierParser.parse(this).type
    type == com.autoaccounting.api.AccountIdentifierTypeContract.PHONE ||
        type == com.autoaccounting.api.AccountIdentifierTypeContract.EMAIL
}.getOrDefault(false)

internal fun String.maskPhoneForIdentity(): String =
    if (length == 11) replaceRange(3, 7, "****") else this

internal fun String.maskAccountUuidForDisplay(): String =
    if (length > 16) "${take(8)}****${takeLast(4)}" else this

internal fun String.maskEmailForIdentity(): String {
    val parts = split("@")
    if (parts.size != 2) return this
    val name = parts[0]
    val maskedName = if (name.length <= 2) "${name.first()}***" else "${name.first()}***${name.last()}"
    return "$maskedName@${parts[1]}"
}

internal fun List<com.autoaccounting.api.AccountIdentifierContract>.identitySummary(): String? {
    val identifier = firstOrNull() ?: return null
    return when (identifier.type) {
        com.autoaccounting.api.AccountIdentifierTypeContract.PHONE -> identifier.value.maskPhoneForIdentity()
        com.autoaccounting.api.AccountIdentifierTypeContract.EMAIL -> identifier.value.maskEmailForIdentity()
        com.autoaccounting.api.AccountIdentifierTypeContract.USERNAME -> identifier.value
    }
}

internal fun WechatAuthCallback.managementPurpose(): WechatAuthPurpose = when (this) {
    is WechatAuthCallback.Authorized -> purpose
    is WechatAuthCallback.Cancelled -> purpose
    is WechatAuthCallback.Denied -> purpose
    is WechatAuthCallback.Failed -> purpose
}

@Composable
internal fun EditNicknameDialog(
    initialValue: String,
    operationInProgress: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = { if (!operationInProgress) onDismiss() },
        title = { Text("修改昵称") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 20) text = it },
                    label = { Text("新昵称（最多20字）") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input-edit-nickname")
                )
                Text(
                    text = "${text.length}/20",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text.trim()) },
                enabled = !operationInProgress && text.isNotBlank(),
                modifier = Modifier.testTag("confirm-edit-nickname")
            ) { Text(if (operationInProgress) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !operationInProgress) { Text("取消") }
        }
    )
}

internal fun AccountSession.SignedIn.toCredentials(
    deletionState: AccountDeletionUiState
): AccountCredentials = AccountCredentials(
    accountId = accountId,
    accountUuid = accountUuid,
    primaryIdentifier = primaryIdentifier,
    identifiers = identifiers,
    rawPhone = rawPhone,
    token = token,
    deletionState = deletionState,
    wechatLinked = wechatLinked,
    nickname = nickname,
    avatarUrl = avatarUrl
)

internal fun AccountIdentityUiState.expectedIdentifierError(): String = when (targetIdentifierType) {
    AccountIdentifierTypeContract.PHONE -> "请输入有效的手机号"
    AccountIdentifierTypeContract.EMAIL -> "请输入有效的邮箱"
    else -> "请输入有效的手机号或邮箱"
}
