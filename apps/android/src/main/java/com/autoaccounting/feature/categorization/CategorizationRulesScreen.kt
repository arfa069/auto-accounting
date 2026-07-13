package com.autoaccounting.feature.categorization

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoaccounting.feature.account.AccountDeletionUiState
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.compliance.AUTO_ACCOUNTING_COMPLIANCE
import com.autoaccounting.feature.compliance.PermissionExplanationId
import com.autoaccounting.feature.compliance.permissionPurpose

@Composable
fun CategorizationRulesScreen(
    modifier: Modifier = Modifier,
    aiSettings: AiCategorizationSettings = AiCategorizationSettings(),
    onAiSettingsChange: (AiCategorizationSettings) -> Unit = {},
    accountSession: AccountSession? = null,
    accountDeletionState: AccountDeletionUiState = AccountDeletionUiState(),
    onBack: (() -> Unit)? = null
) {
    var rules by remember { mutableStateOf(emptyList<CategorizationRule>()) }
    CategorizationRulesScreen(
        rules = rules,
        onRulesChange = { rules = it },
        modifier = modifier,
        aiSettings = aiSettings,
        onAiSettingsChange = onAiSettingsChange,
        accountSession = accountSession,
        accountDeletionState = accountDeletionState,
        onBack = onBack
    )
}

@Composable
fun CategorizationRulesScreen(
    rules: List<CategorizationRule>,
    onRulesChange: (List<CategorizationRule>) -> Unit,
    modifier: Modifier = Modifier,
    aiSettings: AiCategorizationSettings = AiCategorizationSettings(),
    onAiSettingsChange: (AiCategorizationSettings) -> Unit = {},
    accountSession: AccountSession? = null,
    accountDeletionState: AccountDeletionUiState = AccountDeletionUiState(),
    onBack: (() -> Unit)? = null
) {
    var editingRule by remember { mutableStateOf<CategorizationRule?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var currentAiSettings by remember(aiSettings) { mutableStateOf(aiSettings) }

    fun updateAiSettings(next: AiCategorizationSettings) {
        currentAiSettings = next
        onAiSettingsChange(next)
    }


    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        onBack?.let { back ->
            TextButton(onClick = back) {
                Text("返回")
            }
        }
        when (accountSession) {
            is AccountSession.SignedIn -> AiConsentItem(
                settings = currentAiSettings,
                cloudWritesPaused = accountDeletionState.isPending,
                onSettingsChange = ::updateAiSettings
            )
            else -> Text("智能分类登录后可用；本地分类规则不受影响。")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "分类规则",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "按商户、标题、来源和交易类型自动建议分类",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(onClick = {
                editingRule = null
                isCreating = true
            }) {
                Text("新建规则")
            }
        }

        if (rules.isEmpty()) {
            Text("还没有分类规则")
        } else {
            rules
                .sortedWith(compareByDescending<CategorizationRule> { it.priority }.thenByDescending { it.updatedAtEpochMillis })
                .forEach { rule ->
                    CategorizationRuleRow(
                        rule = rule,
                        onEdit = {
                            editingRule = rule
                            isCreating = false
                        },
                        onDelete = {
                            onRulesChange(rules.filterNot { it.id == rule.id })
                        }
                    )
                }
        }
    }

    if (isCreating || editingRule != null) {
        CategorizationRuleDialog(
            initialRule = editingRule,
            onDismiss = {
                isCreating = false
                editingRule = null
            },
            onSave = { savedRule ->
                val nextRules = if (editingRule == null) {
                    rules + savedRule.copy(
                        id = "rule-${rules.size + 1}",
                        updatedAtEpochMillis = rules.nextUpdatedAt()
                    )
                } else {
                    rules.map { existing ->
                        if (existing.id == savedRule.id) {
                            savedRule.copy(updatedAtEpochMillis = rules.nextUpdatedAt())
                        } else {
                            existing
                        }
                    }
                }
                onRulesChange(nextRules)
                isCreating = false
                editingRule = null
            }
        )
    }

}

@Composable
private fun AiConsentItem(
    settings: AiCategorizationSettings,
    cloudWritesPaused: Boolean,
    onSettingsChange: (AiCategorizationSettings) -> Unit
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
            Text("云端 AI 分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                AUTO_ACCOUNTING_COMPLIANCE.permissionPurpose(PermissionExplanationId.CloudAi),
                style = MaterialTheme.typography.bodyMedium
            )
            if (cloudWritesPaused) {
                Text(
                    "账号注销冷静期内，云端 AI 和设备配置写入已暂停。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSettingsChange(
                            reduceAiCategorizationSettings(
                                settings,
                                if (settings.aiConsentGranted) {
                                    AiCategorizationSettingsAction.DisableAi
                                } else {
                                    AiCategorizationSettingsAction.EnableAi
                                }
                            )
                        )
                    },
                    enabled = !cloudWritesPaused
                ) {
                    Text(if (settings.aiConsentGranted) "关闭云端 AI" else "开启云端 AI")
                }
                OutlinedButton(
                    onClick = {
                        onSettingsChange(
                            reduceAiCategorizationSettings(
                                settings,
                                AiCategorizationSettingsAction.SetEnhancedContext(
                                    !settings.enhancedContextGranted
                                )
                            )
                        )
                    },
                    enabled = !cloudWritesPaused && settings.aiConsentGranted
                ) {
                    Text(if (settings.enhancedContextGranted) "减少上下文" else "提供更多上下文")
                }
            }
        }
    }
}

@Composable
private fun CategorizationRuleRow(
    rule: CategorizationRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(rule.displayName(), fontWeight = FontWeight.SemiBold)
                Text(rule.scopeLabel(), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete-rule-${rule.id}")
            ) {
                Text("删除")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onEdit) {
                Text("编辑")
            }
        }
    }
}

@Composable
private fun CategorizationRuleDialog(
    initialRule: CategorizationRule?,
    onDismiss: () -> Unit,
    onSave: (CategorizationRule) -> Unit
) {
    var merchantContains by remember(initialRule?.id) { mutableStateOf(initialRule?.merchantContains.orEmpty()) }
    var titleContains by remember(initialRule?.id) { mutableStateOf(initialRule?.titleContains.orEmpty()) }
    var sourceLabel by remember(initialRule?.id) { mutableStateOf(initialRule?.sourceLabel.orEmpty()) }
    var transactionKind by remember(initialRule?.id) { mutableStateOf(initialRule?.transactionKind.orEmpty()) }
    var category by remember(initialRule?.id) { mutableStateOf(initialRule?.category.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialRule == null) "新建规则" else "编辑规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = merchantContains,
                    onValueChange = { merchantContains = it },
                    label = { Text("商户包含") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = titleContains,
                    onValueChange = { titleContains = it },
                    label = { Text("标题关键词") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sourceLabel,
                    onValueChange = { sourceLabel = it },
                    label = { Text("来源") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = transactionKind,
                    onValueChange = { transactionKind = it },
                    label = { Text("交易类型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("分类") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        CategorizationRule(
                            id = initialRule?.id.orEmpty(),
                            merchantContains = merchantContains.trim(),
                            titleContains = titleContains.trim(),
                            sourceLabel = sourceLabel.trim(),
                            transactionKind = transactionKind.trim(),
                            category = category.trim(),
                            priority = initialRule?.priority ?: 0,
                            enabled = initialRule?.enabled ?: true,
                            updatedAtEpochMillis = initialRule?.updatedAtEpochMillis ?: 0
                        )
                    )
                },
                enabled = category.isNotBlank()
            ) {
                Text("保存规则")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun CategorizationRule.displayName(): String = when {
    merchantContains.isNotBlank() -> merchantContains
    titleContains.isNotBlank() -> titleContains
    sourceLabel.isNotBlank() -> sourceLabel
    transactionKind.isNotBlank() -> transactionKind
    else -> "未命名规则"
}

private fun CategorizationRule.scopeLabel(): String {
    val parts = listOf(
        sourceLabel.takeIf { it.isNotBlank() },
        transactionKind.takeIf { it.isNotBlank() },
        titleContains.takeIf { it.isNotBlank() }
    ).filterNotNull()
    return if (parts.isEmpty()) "全部交易" else parts.joinToString(" / ")
}

private fun List<CategorizationRule>.nextUpdatedAt(): Long {
    return (maxOfOrNull { it.updatedAtEpochMillis } ?: 0L) + 1L
}
