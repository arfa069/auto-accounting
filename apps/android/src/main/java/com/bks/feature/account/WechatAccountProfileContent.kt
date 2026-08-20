package com.bks.feature.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bks.ui.components.TextButton

internal enum class WechatAccountProfileAction {
    EditAvatar,
    EditNickname,
    ReplacePhone,
    BindPhone,
    ReplaceEmail,
    BindEmail,
    UnlinkWechat,
    BindWechat
}

internal data class WechatAccountProfileUiState(
    val identityState: AccountIdentityUiState,
    val displayedAvatarUrl: String?,
    val avatarError: String?,
    val wechatAuthAvailable: Boolean
)

@Composable
internal fun WechatAccountProfileContent(
    session: AccountSession.SignedIn,
    uiState: WechatAccountProfileUiState,
    avatarCache: WechatAvatarCache,
    onAction: (WechatAccountProfileAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = uiState.identityState
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
            WechatAccountAvatarRow(uiState, avatarCache, onAction)
            ProfileDivider()
            WechatAccountNicknameRow(session, state, onAction)
            ProfileDivider()
            WechatAccountIdRow(session)
            ProfileDivider()
            WechatAccountPhoneRow(session, state, onAction)
            ProfileDivider()
            WechatAccountEmailRow(session, state, onAction)
            ProfileDivider()
            WechatAccountWechatRow(session, state, uiState.wechatAuthAvailable, onAction)
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("identity-error"))
            }
        }
    }
}

@Composable
private fun ProfileDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun WechatAccountAvatarRow(
    uiState: WechatAccountProfileUiState,
    avatarCache: WechatAvatarCache,
    onAction: (WechatAccountProfileAction) -> Unit
) {
    val operationInProgress = uiState.identityState.operationInProgress
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
            WechatAvatar(uiState.displayedAvatarUrl, avatarCache)
            TextButton(
                onClick = { onAction(WechatAccountProfileAction.EditAvatar) },
                enabled = !operationInProgress,
                modifier = Modifier.testTag("btn-edit-avatar")
            ) {
                Text(if (operationInProgress) "上传中…" else "修改 ›")
            }
        }
    }
    uiState.avatarError?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("avatar-error")
        )
    }
}

@Composable
private fun WechatAccountNicknameRow(
    session: AccountSession.SignedIn,
    state: AccountIdentityUiState,
    onAction: (WechatAccountProfileAction) -> Unit
) {
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
                onClick = { onAction(WechatAccountProfileAction.EditNickname) },
                enabled = !state.operationInProgress,
                modifier = Modifier.testTag("btn-edit-nickname")
            ) {
                Text("修改 ›")
            }
        }
    }
}

@Composable
private fun WechatAccountIdRow(session: AccountSession.SignedIn) {
    val clipboardManager = LocalClipboardManager.current
    val fullId = session.accountUuid
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
            Text(
                text = fullId?.maskAccountUuidForDisplay() ?: "暂不可用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = { fullId?.let { clipboardManager.setText(AnnotatedString(it)) } },
                enabled = fullId != null,
                modifier = Modifier.testTag("copy-account-id")
            ) {
                Text("复制", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun WechatAccountPhoneRow(
    session: AccountSession.SignedIn,
    state: AccountIdentityUiState,
    onAction: (WechatAccountProfileAction) -> Unit
) {
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
                    onClick = { onAction(WechatAccountProfileAction.ReplacePhone) },
                    enabled = !state.operationInProgress,
                    modifier = Modifier.testTag("replace-phone")
                ) {
                    Text("换绑 ›")
                }
            } else {
                TextButton(
                    onClick = { onAction(WechatAccountProfileAction.BindPhone) },
                    enabled = !state.operationInProgress,
                    modifier = Modifier.testTag("bind-phone")
                ) {
                    Text("立即绑定 ›")
                }
            }
        }
    }
}

@Composable
private fun WechatAccountEmailRow(
    session: AccountSession.SignedIn,
    state: AccountIdentityUiState,
    onAction: (WechatAccountProfileAction) -> Unit
) {
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
                    onClick = { onAction(WechatAccountProfileAction.ReplaceEmail) },
                    enabled = !state.operationInProgress,
                    modifier = Modifier.testTag("replace-email")
                ) {
                    Text("换绑 ›")
                }
            } else {
                TextButton(
                    onClick = { onAction(WechatAccountProfileAction.BindEmail) },
                    enabled = !state.operationInProgress,
                    modifier = Modifier.testTag("bind-email")
                ) {
                    Text("立即绑定 ›")
                }
            }
        }
    }
}

@Composable
private fun WechatAccountWechatRow(
    session: AccountSession.SignedIn,
    state: AccountIdentityUiState,
    wechatAuthAvailable: Boolean,
    onAction: (WechatAccountProfileAction) -> Unit
) {
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
                    onClick = { onAction(WechatAccountProfileAction.UnlinkWechat) },
                    enabled = !state.operationInProgress,
                    modifier = Modifier.testTag("unlink-wechat")
                ) {
                    Text("换绑/解绑 ›")
                }
            } else {
                TextButton(
                    onClick = { onAction(WechatAccountProfileAction.BindWechat) },
                    enabled = !state.operationInProgress && wechatAuthAvailable,
                    modifier = Modifier.testTag("bind-wechat")
                ) {
                    Text("立即绑定 ›")
                }
            }
        }
    }
}
