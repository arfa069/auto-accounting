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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoaccounting.ui.components.Button

data class CategorizationRulesActions(
    val onAiSettingsChange: (AiCategorizationSettings) -> Unit = {},
    val onBack: (() -> Unit)? = null
)

internal enum class RuleFilter(val label: String) {
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

@Composable
fun CategorizationRulesScreen(
    modifier: Modifier = Modifier,
    aiUiState: CategorizationAiUiState = CategorizationAiUiState(),
    onAiSettingsChange: (AiCategorizationSettings) -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    var rules by remember { mutableStateOf(emptyList<CategorizationRule>()) }
    CategorizationRulesScreen(
        rules = rules,
        onRulesChange = { rules = it },
        modifier = modifier,
        aiUiState = aiUiState,
        actions = CategorizationRulesActions(
            onAiSettingsChange = onAiSettingsChange,
            onBack = onBack
        )
    )
}

@Composable
fun CategorizationRulesScreen(
    rules: List<CategorizationRule>,
    onRulesChange: (List<CategorizationRule>) -> Unit,
    modifier: Modifier = Modifier,
    aiUiState: CategorizationAiUiState = CategorizationAiUiState(),
    actions: CategorizationRulesActions = CategorizationRulesActions()
) {
    var editingRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    var isCreating by rememberSaveable { mutableStateOf(false) }
    var selectedFilter by rememberSaveable { mutableStateOf(RuleFilter.All) }
    val editingRule = remember(rules, editingRuleId) {
        rules.firstOrNull { it.id == editingRuleId }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        CategorizationRulesHeader(onBack = actions.onBack)

        CategorizationAiSettingsSection(
            uiState = aiUiState,
            onSettingsChange = actions.onAiSettingsChange
        )

        RuleListHeading(
            ruleCount = rules.size,
            onCreate = {
                editingRuleId = null
                isCreating = true
            }
        )

        CategorizationRuleListContent(
            rules = rules,
            selectedFilter = selectedFilter,
            onSelectedFilter = { selectedFilter = it },
            onEdit = { rule ->
                editingRuleId = rule.id
                isCreating = false
            },
            onDelete = { rule ->
                onRulesChange(rules.filterNot { it.id == rule.id })
            }
        )

        Spacer(Modifier.height(8.dp))
    }

    if (isCreating || editingRule != null) {
        CategorizationRuleDialog(
            initialRule = editingRule,
            onDismiss = {
                isCreating = false
                editingRuleId = null
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
                editingRuleId = null
            }
        )
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
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
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
private fun CategorizationRulesHeader(onBack: (() -> Unit)?) {
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
