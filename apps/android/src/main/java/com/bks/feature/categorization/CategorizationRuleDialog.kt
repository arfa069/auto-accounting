package com.bks.feature.categorization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bks.ui.components.Button
import com.bks.ui.components.OutlinedTextField
import com.bks.ui.components.TextButton

@Composable
internal fun CategorizationRuleDialog(
    initialRule: CategorizationRule?,
    onDismiss: () -> Unit,
    onSave: (CategorizationRule) -> Unit
) {
    var merchantContains by rememberSaveable(initialRule?.id) {
        mutableStateOf(initialRule?.merchantContains.orEmpty())
    }
    var titleContains by rememberSaveable(initialRule?.id) {
        mutableStateOf(initialRule?.titleContains.orEmpty())
    }
    var sourceLabel by rememberSaveable(initialRule?.id) {
        mutableStateOf(initialRule?.sourceLabel.orEmpty())
    }
    var transactionKind by rememberSaveable(initialRule?.id) {
        mutableStateOf(initialRule?.transactionKind.orEmpty())
    }
    var category by rememberSaveable(initialRule?.id) {
        mutableStateOf(initialRule?.category.orEmpty())
    }

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

internal fun List<CategorizationRule>.nextUpdatedAt(): Long =
    (maxOfOrNull { it.updatedAtEpochMillis } ?: 0L) + 1L
