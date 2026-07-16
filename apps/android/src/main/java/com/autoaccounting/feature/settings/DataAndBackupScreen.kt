package com.autoaccounting.feature.settings

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.autoaccounting.ui.components.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.autoaccounting.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.autoaccounting.feature.ledger.LedgerUiEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

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
    snackbarHostState: SnackbarHostState = SnackbarHostState()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var pendingBackup by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isCsvExporting by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            runCatching {
                val content = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                } ?: error("Failed to read backup file")
                onValidateEncryptedBackup(content, passphrase)
                content
            }.onSuccess { pendingBackup = it }
                .onFailure {
                    snackbarHostState.showSnackbar("备份校验失败：密码错误或文件损坏")
                }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("返回") }
        Text("数据与备份", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        CardSection(title = "导出与恢复") {
            Text(
                "CSV 仅导出当前账本「$currentLedgerName」，且是明文表格；" +
                    "加密备份包含全部账本及本机设置。"
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("备份密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("backup-passphrase")
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        isCsvExporting = true
                        coroutineScope.launch {
                            runCatching {
                                val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(Date())
                                val filename = ledgerCsvFilename(currentLedgerName, timestamp)
                                val values = ContentValues().apply {
                                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                                }
                                val uri = context.contentResolver.insert(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                    values
                                ) ?: error("Failed to create Downloads entry")
                                context.contentResolver.openOutputStream(uri)?.use { output ->
                                    output.write(exportLedgerCsv(ledgerEntries).toByteArray(Charsets.UTF_8))
                                } ?: error("Failed to write CSV file")
                                filename
                            }.onSuccess {
                                snackbarHostState.showSnackbar("CSV 已保存到 Download/$it")
                            }.onFailure {
                                snackbarHostState.showSnackbar("CSV 导出失败")
                            }
                            isCsvExporting = false
                        }
                    },
                    enabled = !isCsvExporting
                ) { Text("导出 CSV") }
                OutlinedButton(
                    onClick = {
                        isExporting = true
                        coroutineScope.launch {
                            runCatching {
                                val content = onExportEncryptedBackup(passphrase)
                                val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(Date())
                                val filename = "$timestamp-ac-backup.bak"
                                val values = ContentValues().apply {
                                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                                }
                                val uri = context.contentResolver.insert(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                    values
                                ) ?: error("Failed to create Downloads entry")
                                context.contentResolver.openOutputStream(uri)?.use { output ->
                                    output.write(content.toByteArray(Charsets.UTF_8))
                                } ?: error("Failed to write backup file")
                                filename
                            }.onSuccess {
                                snackbarHostState.showSnackbar("备份已保存到 Download/$it")
                            }.onFailure {
                                snackbarHostState.showSnackbar("加密备份失败")
                            }
                            isExporting = false
                        }
                    },
                    enabled = passphrase.isNotBlank() && !isExporting
                ) { Text("导出加密备份到文件") }
            }
            OutlinedButton(
                onClick = { openDocumentLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = passphrase.isNotBlank()
            ) { Text("从文件导入备份") }
        }
        CardSection(title = "危险区", danger = true) {
            Text("删除本机数据不会注销云端账号，且无法撤销。")
            OutlinedButton(onClick = { showDeleteDialog = true }) {
                Text("删除本机数据")
            }
        }
    }

    pendingBackup?.let { backup ->
        AlertDialog(
            onDismissRequest = { pendingBackup = null },
            title = { Text("确认恢复备份") },
            text = { Text("备份已通过校验。继续将替换本机现有数据，此操作无法撤销。") },
            confirmButton = {
                Button(onClick = {
                    pendingBackup = null
                    coroutineScope.launch {
                        runCatching { onImportEncryptedBackup(backup, passphrase) }
                            .onSuccess { snackbarHostState.showSnackbar("备份已恢复成功") }
                            .onFailure { snackbarHostState.showSnackbar("备份恢复失败，本机数据未更改") }
                    }
                }) { Text("确认替换并恢复") }
            },
            dismissButton = {
                TextButton(onClick = { pendingBackup = null }) { Text("取消") }
            }
        )
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
private fun CardSection(
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
