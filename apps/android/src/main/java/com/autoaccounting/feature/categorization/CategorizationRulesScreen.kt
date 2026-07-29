package com.autoaccounting.feature.categorization

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoaccounting.ui.components.Button
import com.autoaccounting.ui.components.OutlinedTextField
import com.autoaccounting.ui.components.TextButton
import com.autoaccounting.feature.account.AccountDeletionUiState
import com.autoaccounting.feature.account.AccountRuntimeState
import com.autoaccounting.feature.account.AccountRuntimeStatus
import com.autoaccounting.feature.account.AccountSession

@Composable
fun CategorizationRulesScreen(
    modifier: Modifier = Modifier,
    aiSettings: AiCategorizationSettings = AiCategorizationSettings(),
    onAiSettingsChange: (AiCategorizationSettings) -> Unit = {},
    aiSettingsSyncInFlight: Boolean = false,
    accountSession: AccountSession? = null,
    accountDeletionState: AccountDeletionUiState = AccountDeletionUiState(),
    accountRuntimeState: AccountRuntimeState = AccountRuntimeState(AccountRuntimeStatus.Verified),
    onBack: (() -> Unit)? = null
) {
    var rules by remember { mutableStateOf(emptyList<CategorizationRule>()) }
    CategorizationRulesScreen(
        rules = rules,
        onRulesChange = { rules = it },
        modifier = modifier,
        aiSettings = aiSettings,
        onAiSettingsChange = onAiSettingsChange,
        aiSettingsSyncInFlight = aiSettingsSyncInFlight,
        accountSession = accountSession,
        accountDeletionState = accountDeletionState,
        accountRuntimeState = accountRuntimeState,
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
    aiSettingsSyncInFlight: Boolean = false,
    accountSession: AccountSession? = null,
    accountDeletionState: AccountDeletionUiState = AccountDeletionUiState(),
    accountRuntimeState: AccountRuntimeState = AccountRuntimeState(AccountRuntimeStatus.Verified),
    onBack: (() -> Unit)? = null
) {
    var editingRule by remember { mutableStateOf<CategorizationRule?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(RuleFilter.All) }
    val visibleRules = rules.filter(selectedFilter::matches)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        PageHeading(onBack = onBack)

        when (accountSession) {
            is AccountSession.SignedIn -> AiConsentItem(
                settings = aiSettings,
                cloudWritesPaused = accountDeletionState.isPending ||
                    !accountRuntimeState.cloudWritesAllowed,
                settingsSyncInFlight = aiSettingsSyncInFlight,
                onSettingsChange = onAiSettingsChange
            )
            else -> SignedOutAiItem()
        }

        RuleListHeading(
            ruleCount = rules.size,
            onCreate = {
                editingRule = null
                isCreating = true
            }
        )

        RuleFilterRow(
            rules = rules,
            selected = selectedFilter,
            onSelected = { selectedFilter = it }
        )

        if (rules.isEmpty()) {
            EmptyRulesCard()
        } else if (visibleRules.isEmpty()) {
            EmptyRulesCard(
                title = "这个分类下还没有规则",
                description = "可以切换筛选，或者新建一条规则。"
            )
        } else {
            visibleRules
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

        Spacer(Modifier.height(8.dp))
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
private fun PageHeading(onBack: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            onBack?.let { back ->
                Surface(
                    onClick = back,
                    modifier = Modifier
                        .size(42.dp)
                        .testTag("categorization-back"),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            Text(
                text = "分类规则",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = "让常见交易按你的习惯自动归类。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AiConsentItem(
    settings: AiCategorizationSettings,
    cloudWritesPaused: Boolean,
    settingsSyncInFlight: Boolean,
    onSettingsChange: (AiCategorizationSettings) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AiSettingsHeader(
                checked = settings.aiConsentGranted,
                enabled = !cloudWritesPaused && !settingsSyncInFlight,
                onCheckedChange = { enabled ->
                    onSettingsChange(
                        reduceAiCategorizationSettings(
                            settings,
                            if (enabled) {
                                AiCategorizationSettingsAction.EnableAi
                            } else {
                                AiCategorizationSettingsAction.DisableAi
                            }
                        )
                    )
                }
            )
            Text(
                text = "需要时帮你判断分类，结果会先留在待确认里，由你决定是否采用。",
                style = MaterialTheme.typography.bodyMedium
            )
            if (cloudWritesPaused) {
                Text(
                    "账号注销冷静期内，智能分类暂时不可用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (settingsSyncInFlight) {
                Text(
                    "正在同步云端 AI 设置…",
                    modifier = Modifier.testTag("ai-settings-syncing"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("更多判断依据", fontWeight = FontWeight.SemiBold)
                        Text(
                            "允许使用备注和识别到的交易文字",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.enhancedContextGranted,
                        onCheckedChange = { enabled ->
                            onSettingsChange(
                                reduceAiCategorizationSettings(
                                    settings,
                                    AiCategorizationSettingsAction.SetEnhancedContext(enabled)
                                )
                            )
                        },
                        modifier = Modifier.testTag("enhanced-context-switch"),
                        enabled = !cloudWritesPaused &&
                            !settingsSyncInFlight &&
                            settings.aiConsentGranted
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = "🔒 关闭时只使用基础交易信息",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AiSettingsHeader(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primary
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("✦", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(
            text = "智能分类",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            shape = RoundedCornerShape(50)
        ) {
            Text(
                text = "试用",
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("ai-consent-switch"),
            enabled = enabled
        )
    }
}

@Composable
private fun SignedOutAiItem() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("✦", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                Text("智能分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        "登录后可用",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                "登录后可以使用智能分类；本地规则照常生效。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RuleListHeading(
    ruleCount: Int,
    onCreate: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "我的规则",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = ruleCount.toString(),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = "符合条件时，自动带上建议分类。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(
            onClick = onCreate,
            modifier = Modifier.testTag("create-rule")
        ) {
            Text("＋", modifier = Modifier.clearAndSetSemantics {})
            Spacer(Modifier.width(4.dp))
            Text("新建规则")
        }
    }
}

@Composable
private fun RuleFilterRow(
    rules: List<CategorizationRule>,
    selected: RuleFilter,
    onSelected: (RuleFilter) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RuleFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelected(filter) },
                label = { Text("${filter.label} ${rules.count(filter::matches)}") },
                modifier = Modifier.testTag("rule-filter-${filter.name}")
            )
        }
    }
}

@Composable
private fun EmptyRulesCard(
    title: String = "还没有分类规则",
    description: String = "新建一条规则，让常见交易自动带上分类建议。"
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CategorizationRuleRow(
    rule: CategorizationRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember(rule.id) { mutableStateOf(false) }
    val accentColor = rule.accentColor()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("rule-card-${rule.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = accentColor.copy(alpha = 0.14f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = rule.displayName().take(1),
                        color = accentColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    text = rule.displayName(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = rule.conditionSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    color = accentColor.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = "建议为 ${rule.category}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box {
                TextButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("rule-menu-${rule.id}")
                ) {
                    Text("⋮", style = MaterialTheme.typography.titleLarge)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                        modifier = Modifier.testTag("edit-rule-${rule.id}")
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        modifier = Modifier.testTag("delete-rule-${rule.id}")
                    )
                }
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

private fun CategorizationRule.conditionSummary(): String {
    val parts = listOf(
        merchantContains.takeIf { it.isNotBlank() }?.let { "商户包含“$it”" },
        titleContains.takeIf { it.isNotBlank() }?.let { "标题包含“$it”" },
        sourceLabel.takeIf { it.isNotBlank() }?.let { "来源是“$it”" },
        transactionKind.takeIf { it.isNotBlank() }?.let { kind ->
            if (partsNeedTransactionLabel()) "交易类型是“$kind”" else kind
        }
    ).filterNotNull()
    return if (parts.isEmpty()) "适用于所有交易" else parts.joinToString(" · ")
}

private fun CategorizationRule.partsNeedTransactionLabel(): Boolean =
    merchantContains.isBlank() && titleContains.isBlank() && sourceLabel.isBlank()

private fun CategorizationRule.accentColor(): Color = when (transactionKind) {
    "收入" -> Color(0xFF27877B)
    "退款" -> Color(0xFFC64D5A)
    else -> when (category) {
        "餐饮" -> Color(0xFFC77A18)
        "交通" -> Color(0xFF5555C2)
        else -> Color(0xFF5B5BD6)
    }
}

private enum class RuleFilter(val label: String) {
    All("全部"),
    Expense("支出"),
    Income("收入"),
    Other("其他");

    fun matches(rule: CategorizationRule): Boolean = when (this) {
        All -> true
        Expense -> rule.transactionKind.equals("支出", ignoreCase = true)
        Income -> rule.transactionKind.equals("收入", ignoreCase = true)
        Other -> !Expense.matches(rule) && !Income.matches(rule)
    }
}

private fun List<CategorizationRule>.nextUpdatedAt(): Long {
    return (maxOfOrNull { it.updatedAtEpochMillis } ?: 0L) + 1L
}
