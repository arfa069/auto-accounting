package com.autoaccounting.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoaccounting.feature.account.AccountDeletionUiAction
import com.autoaccounting.feature.account.AccountDeletionUiState
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.account.reduceAccountDeletionState

enum class ProfileDestination(
    val title: String
) {
    AccountManagement("账户管理"),
    AutomaticBookkeeping("自动记账"),
    CategorizationRules("分类规则"),
    DataAndBackup("数据与备份"),
    ComplianceAndPrivacy("合规与隐私")
}

@Composable
fun ProfileOverviewScreen(
    session: AccountSession,
    onDestinationSelected: (ProfileDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "我的",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        AccountStatusCard(
            session = session,
            onClick = { onDestinationSelected(ProfileDestination.AccountManagement) }
        )
        ProfileDestination.entries.forEach { destination ->
            ProfileEntry(
                destination = destination,
                summary = destination.summary(session),
                onClick = { onDestinationSelected(destination) }
            )
        }
    }
}

@Composable
fun AccountManagementScreen(
    session: AccountSession,
    deletionState: AccountDeletionUiState,
    onSignInOrRegister: () -> Unit,
    onSignOut: () -> Unit,
    onDeletionStateChange: (AccountDeletionUiState) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) {
            Text("返回")
        }
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
                Text("已登录：${session.phone.maskPhone()}")
                OutlinedButton(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("退出登录")
                }
                AccountDeletionCard(
                    state = deletionState,
                    onStateChange = onDeletionStateChange
                )
            }
        }
    }
}

@Composable
fun ProfileSecondaryPlaceholderScreen(
    destination: ProfileDestination,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) {
            Text("返回")
        }
        Text(
            text = destination.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text("该功能将在后续页面迁移中提供。")
    }
}

@Composable
private fun AccountDeletionCard(
    state: AccountDeletionUiState,
    onStateChange: (AccountDeletionUiState) -> Unit
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
            Text(
                if (state.isPending) {
                    "注销冷静期中，云端 AI 和设备配置写入已暂停。"
                } else {
                    "注销会删除云端账号、注册设备、云端配置和 AI 分类日志；本机账本需单独删除。"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (state.isPending) {
                OutlinedButton(
                    onClick = {
                        onStateChange(
                            reduceAccountDeletionState(state, AccountDeletionUiAction.CancelDeletion)
                        )
                    }
                ) {
                    Text("取消注销")
                }
            } else {
                Button(
                    onClick = {
                        onStateChange(
                            reduceAccountDeletionState(
                                state,
                                AccountDeletionUiAction.RequestDeletion(System.currentTimeMillis())
                            )
                        )
                    }
                ) {
                    Text("申请注销账号")
                }
            }
        }
    }
}

@Composable
private fun AccountStatusCard(
    session: AccountSession,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile-account-status-card")
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("账户管理", fontWeight = FontWeight.SemiBold)
            Text(session.accountSummary(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ProfileEntry(
    destination: ProfileDestination,
    summary: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile-entry-${destination.name}")
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(destination.title, fontWeight = FontWeight.SemiBold)
            Text(summary, style = MaterialTheme.typography.bodyMedium)
            Text("进入", style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun ProfileDestination.summary(session: AccountSession): String = when (this) {
    ProfileDestination.AccountManagement -> session.accountSummary()
    ProfileDestination.AutomaticBookkeeping -> "查看自动记账状态"
    ProfileDestination.CategorizationRules -> "管理本地分类规则"
    ProfileDestination.DataAndBackup -> "导入、导出与备份"
    ProfileDestination.ComplianceAndPrivacy -> "查看隐私与权限说明"
}

private fun AccountSession.accountSummary(): String = when (this) {
    AccountSession.LocalMode -> "本地模式 · 账本仅保存在本机"
    is AccountSession.SignedIn -> "已登录 · ${phone.maskPhone()}"
}

private fun String.maskPhone(): String =
    if (length == 11) replaceRange(3, 7, "****") else "已登录"
