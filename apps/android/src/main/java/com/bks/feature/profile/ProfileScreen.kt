package com.bks.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.bks.ui.components.HomeReturnButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bks.R
import com.bks.feature.account.AccountSession
import com.bks.ui.visual.CachedResourceImage

enum class ProfileDestination(
    val title: String,
    val iconRes: Int
) {
    AccountManagement("账户管理", R.drawable.aa_profile_account),
    AutomaticBookkeeping("自动记账", R.drawable.aa_profile_automatic),
    CategorizationRules("分类规则", R.drawable.aa_profile_rules),
    DataAndBackup("数据与备份", R.drawable.aa_profile_backup),
    ComplianceAndPrivacy("合规与隐私", R.drawable.aa_profile_privacy)
}

@Composable
fun ProfileOverviewScreen(
    session: AccountSession,
    onDestinationSelected: (ProfileDestination) -> Unit,
    onNavigateHome: () -> Unit = {},
    ledgerSyncEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "我的",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            HomeReturnButton(onClick = onNavigateHome)
        }
        AccountStatusCard(
            session = session,
            onClick = { onDestinationSelected(ProfileDestination.AccountManagement) }
        )
        ProfileDestination.entries
            .filterNot { it == ProfileDestination.AccountManagement }
            .forEach { destination ->
                ProfileEntry(
                    destination = destination,
                    summary = destination.summary(session, ledgerSyncEnabled),
                    onClick = { onDestinationSelected(destination) }
                )
            }
    }
}

@Composable
private fun AccountStatusCard(
    session: AccountSession,
    onClick: () -> Unit
) {
    ProfileEntry(
        destination = ProfileDestination.AccountManagement,
        summary = session.accountSummary(),
        testTag = "profile-account-status-card",
        onClick = onClick
    )
}

@Composable
private fun ProfileEntry(
    destination: ProfileDestination,
    summary: String,
    testTag: String = "profile-entry-${destination.name}",
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CachedResourceImage(
                imageRes = destination.iconRes,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(destination.title, fontWeight = FontWeight.SemiBold)
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun ProfileDestination.summary(session: AccountSession, ledgerSyncEnabled: Boolean): String = when (this) {
    ProfileDestination.AccountManagement -> session.accountSummary()
    ProfileDestination.AutomaticBookkeeping -> "查看功能说明"
    ProfileDestination.CategorizationRules -> "管理本地分类规则"
    ProfileDestination.DataAndBackup -> if (ledgerSyncEnabled) "账户同步已启用 · 导入、导出与备份" else "导入、导出与备份"
    ProfileDestination.ComplianceAndPrivacy -> "查看隐私与权限说明"
}

private fun AccountSession.accountSummary(): String = when (this) {
    AccountSession.LocalMode -> "本地模式 · 账本仅保存在本机"
    is AccountSession.SignedIn -> phone
        ?.let { "已登录 · ${it.maskPhone()}" }
        ?: "已登录 · ${nickname ?: "微信账号"}"
}

private fun String.maskPhone(): String =
    if (length == 11) replaceRange(3, 7, "****") else "已登录"
