package com.autoaccounting.feature.ledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoaccounting.ui.components.Button
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.TextButton

@Composable
internal fun RecentlyDeletedScreen(
    entries: List<LedgerUiEntry>,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onPermanentlyDelete: (String) -> Unit
) {
    var pendingPermanentDeleteId by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("返回账本") }
        Text("最近删除", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (entries.isEmpty()) {
            Text("最近删除中没有账目")
        }
        entries.forEach { entry ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.title.ifBlank { "未填写标题" }, fontWeight = FontWeight.SemiBold)
                    Text("删除时间 ${formatLedgerEpoch(entry.deletedAtEpochMillis ?: 0)}")
                    Text("剩余 ${entry.remainingRetentionDays(now)} 天")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onRestore(entry.id) }) { Text("恢复") }
                        OutlinedButton(onClick = { pendingPermanentDeleteId = entry.id }) {
                            Text("永久删除")
                        }
                    }
                }
            }
        }
    }
    pendingPermanentDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingPermanentDeleteId = null },
            title = { Text("永久删除这笔账？") },
            text = { Text("永久删除后无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingPermanentDeleteId = null
                        onPermanentlyDelete(id)
                    }
                ) { Text("永久删除") }
            },
            dismissButton = { TextButton(onClick = { pendingPermanentDeleteId = null }) { Text("取消") } }
        )
    }
}
