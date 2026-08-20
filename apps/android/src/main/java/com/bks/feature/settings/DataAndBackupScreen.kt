package com.bks.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.bks.ui.components.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import com.bks.ui.components.OutlinedButton
import com.bks.ui.components.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.bks.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bks.feature.ledger.LedgerUiEntry
import com.bks.api.LedgerSyncConflictChoiceContract
import com.bks.feature.sync.LedgerSyncInitialMode
import com.bks.feature.sync.LedgerSyncOperationResult
import com.bks.feature.sync.LedgerSyncPreview
import com.bks.feature.sync.LedgerSyncUiState

@Composable
fun DataAndBackupScreen(
    ledgerEntries: List<LedgerUiEntry>,
    currentLedgerName: String = "默认账本",
    onExportEncryptedBackup: suspend (String) -> String,
    onValidateEncryptedBackup: suspend (String, String) -> Unit,
    onImportEncryptedBackup: suspend (String, String) -> Unit,
    onDeleteLocalData: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = SnackbarHostState(),
    ledgerSyncState: LedgerSyncUiState = LedgerSyncUiState(),
    onPreviewLedgerSync: suspend () -> LedgerSyncOperationResult<LedgerSyncPreview> = {
        LedgerSyncOperationResult.Failure(null, "请先登录账户", false)
    },
    onEnableLedgerSync: suspend (LedgerSyncInitialMode) -> LedgerSyncOperationResult<Unit> = {
        LedgerSyncOperationResult.Failure(null, "请先登录账户", false)
    },
    onSyncNow: suspend () -> LedgerSyncOperationResult<Unit> = {
        LedgerSyncOperationResult.Failure(null, "请先登录账户", false)
    },
    onDisableLedgerSync: suspend () -> Unit = {},
    onResolveLedgerSyncConflict: suspend (String, Long, LedgerSyncConflictChoiceContract) -> LedgerSyncOperationResult<Unit> =
        { _, _, _ -> LedgerSyncOperationResult.Failure(null, "请先登录账户", false) }
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("返回") }
        Text("数据与备份", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        LedgerSyncSettingsSection(
            ledgerSyncState = ledgerSyncState,
            actions = LedgerSyncSectionActions(
                onPreview = onPreviewLedgerSync,
                onEnable = onEnableLedgerSync,
                onSyncNow = onSyncNow,
                onDisable = onDisableLedgerSync,
                onResolveConflict = onResolveLedgerSyncConflict
            ),
            snackbarHostState = snackbarHostState
        )
        LocalBackupSection(
            ledgerEntries = ledgerEntries,
            currentLedgerName = currentLedgerName,
            actions = LocalBackupSectionActions(
                onExportEncryptedBackup = onExportEncryptedBackup,
                onValidateEncryptedBackup = onValidateEncryptedBackup,
                onImportEncryptedBackup = onImportEncryptedBackup
            ),
            snackbarHostState = snackbarHostState
        )
        CardSection(title = "危险区", danger = true) {
            Text("删除本机数据不会注销云端账号，且无法撤销。")
            OutlinedButton(onClick = { showDeleteDialog = true }) {
                Text("删除本机数据")
            }
        }
    }

    if (showDeleteDialog) {
        LocalDataDeletionDialog(
            onDismiss = { showDeleteDialog = false },
            onDelete = {
                onDeleteLocalData()
                showDeleteDialog = false
            }
        )
    }
}

@Composable
internal fun CardSection(
    title: String,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

@Composable
private fun LocalDataDeletionDialog(onDismiss: () -> Unit, onDelete: () -> Unit) {
    var state by remember { mutableStateOf(LocalDataDeletionState()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除本机数据") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("确认删除前请先导出加密备份。")
                OutlinedButton(onClick = {
                    state = reduceLocalDataDeletionState(
                        state,
                        LocalDataDeletionAction.SetBackupReminderAccepted(!state.backupReminderAccepted)
                    )
                }) { Text("我已了解并完成需要的备份") }
                OutlinedTextField(
                    value = state.confirmationText,
                    onValueChange = {
                        state = reduceLocalDataDeletionState(
                            state,
                            LocalDataDeletionAction.UpdateConfirmationText(it)
                        )
                    },
                    label = { Text("输入 $DELETE_LOCAL_DATA_PHRASE") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = onDelete, enabled = state.canDelete) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
