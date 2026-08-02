package com.autoaccounting.feature.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun IgnoredEntriesDialog(
    ignoredEntries: List<ReviewQueueIgnoredEntry>,
    onDismiss: () -> Unit,
    onRecover: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("忽略列表") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ignoredEntries.isEmpty()) {
                    Text("没有可恢复的忽略记录")
                } else {
                    ignoredEntries.forEach { ignored ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ignored.entry.title, fontWeight = FontWeight.SemiBold)
                                Text(formatAmount(ignored.entry.amountMinor))
                            }
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(
                                onClick = { onRecover(ignored.id) },
                                modifier = Modifier.testTag("recover-${ignored.id}")
                            ) {
                                Text("恢复")
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
