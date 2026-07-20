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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingRestore(
    val backup: String,
    val passphrase: String
)

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
    var exportPassphrase by remember { mutableStateOf("") }
    var selectedBackup by remember { mutableStateOf<String?>(null) }
    var importPassphrase by remember { mutableStateOf("") }
    var importPasswordError by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var isCsvExporting by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var isReadingBackup by remember { mutableStateOf(false) }
    var isValidatingBackup by remember { mutableStateOf(false) }
    var isImportingBackup by remember { mutableStateOf(false) }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        isReadingBackup = true
        coroutineScope.launch {
            val result = runCatching { readBackupFile(context, uri) }
            isReadingBackup = false
            result.onSuccess { content ->
                if (isEncryptedLocalDataBackup(content)) {
                    selectedBackup = content
                    importPassphrase = ""
                    importPasswordError = null
                } else {
                    snackbarHostState.showSnackbar("所选文件不是受支持的加密备份")
                }
            }.onFailure {
                snackbarHostState.showSnackbar("备份文件读取失败")
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        isCsvExporting = true
                        coroutineScope.launch {
                            runCatching {
                                val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(Date())
                                val filename = ledgerCsvFilename(currentLedgerName, timestamp)
                                val content = withContext(Dispatchers.Default) {
                                    exportLedgerCsv(ledgerEntries)
                                }
                                writeDownloadFile(
                                    context = context,
                                    filename = filename,
                                    mimeType = "text/csv",
                                    content = content
                                )
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
                    onClick = { showExportPasswordDialog = true },
                    enabled = !isExporting
                ) { Text("导出加密备份") }
            }
            OutlinedButton(
                onClick = { openDocumentLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = !isReadingBackup && !isValidatingBackup && !isImportingBackup
            ) { Text("导入加密备份") }
        }
        CardSection(title = "危险区", danger = true) {
            Text("删除本机数据不会注销云端账号，且无法撤销。")
            OutlinedButton(onClick = { showDeleteDialog = true }) {
                Text("删除本机数据")
            }
        }
    }

    if (showExportPasswordDialog) {
        BackupPasswordDialog(
            title = "导出加密备份",
            description = "请输入大于 8 位的备份密码。密码不会保存，丢失后无法恢复备份。",
            passphrase = exportPassphrase,
            onPassphraseChange = { exportPassphrase = it },
            confirmLabel = "确认导出",
            isBusy = isExporting,
            minimumLength = MIN_BACKUP_PASSPHRASE_LENGTH,
            onDismiss = {
                if (!isExporting) {
                    exportPassphrase = ""
                    showExportPasswordDialog = false
                }
            },
            onConfirm = {
                isExporting = true
                coroutineScope.launch {
                    val result = runCatching {
                        val content = onExportEncryptedBackup(exportPassphrase)
                        val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(Date())
                        val filename = "$timestamp-ac-backup.bak"
                        writeDownloadFile(
                            context = context,
                            filename = filename,
                            mimeType = "application/octet-stream",
                            content = content
                        )
                        filename
                    }
                    isExporting = false
                    exportPassphrase = ""
                    showExportPasswordDialog = false
                    result.onSuccess {
                        snackbarHostState.showSnackbar("备份已保存到 Downloads/$it")
                    }.onFailure {
                        snackbarHostState.showSnackbar("加密备份失败")
                    }
                }
            }
        )
    }

    selectedBackup?.let { backup ->
        BackupPasswordDialog(
            title = "输入备份密码",
            description = "该文件是加密备份。请输入密码，验证通过后才能恢复。",
            passphrase = importPassphrase,
            onPassphraseChange = {
                importPassphrase = it
                importPasswordError = null
            },
            confirmLabel = "确认",
            isBusy = isValidatingBackup,
            errorMessage = importPasswordError,
            onDismiss = {
                if (!isValidatingBackup) {
                    selectedBackup = null
                    importPassphrase = ""
                    importPasswordError = null
                }
            },
            onConfirm = {
                isValidatingBackup = true
                coroutineScope.launch {
                    val result = runCatching { onValidateEncryptedBackup(backup, importPassphrase) }
                    isValidatingBackup = false
                    result.onSuccess {
                        pendingRestore = PendingRestore(backup, importPassphrase)
                        selectedBackup = null
                        importPassphrase = ""
                        importPasswordError = null
                    }.onFailure {
                        importPasswordError = "密码错误，或备份文件已损坏，请重试"
                    }
                }
            }
        )
    }

    pendingRestore?.let { restore ->
        AlertDialog(
            onDismissRequest = {
                if (!isImportingBackup) pendingRestore = null
            },
            title = { Text("确认恢复备份") },
            text = { Text("备份已通过校验。继续将替换本机现有数据，此操作无法撤销。") },
            confirmButton = {
                Button(onClick = {
                    isImportingBackup = true
                    coroutineScope.launch {
                        val result = runCatching {
                            onImportEncryptedBackup(restore.backup, restore.passphrase)
                        }
                        isImportingBackup = false
                        pendingRestore = null
                        result.onSuccess {
                            snackbarHostState.showSnackbar("备份已恢复成功")
                        }.onFailure {
                            snackbarHostState.showSnackbar("备份恢复失败，本机数据未更改")
                        }
                    }
                }, enabled = !isImportingBackup) {
                    Text(if (isImportingBackup) "正在恢复" else "确认替换并恢复")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingRestore = null },
                    enabled = !isImportingBackup
                ) { Text("取消") }
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
private fun BackupPasswordDialog(
    title: String,
    description: String,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    confirmLabel: String,
    isBusy: Boolean,
    minimumLength: Int? = null,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val meetsMinimumLength = minimumLength == null || passphrase.length >= minimumLength
    val canConfirm = passphrase.isNotBlank() && meetsMinimumLength && !isBusy
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(description)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = onPassphraseChange,
                    label = { Text("备份密码") },
                    singleLine = true,
                    enabled = !isBusy,
                    isError = errorMessage != null,
                    supportingText = when {
                        errorMessage != null -> ({ Text(errorMessage) })
                        minimumLength != null -> ({ Text("密码需大于 8 位") })
                        else -> null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("backup-password-dialog-input")
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = canConfirm) {
                Text(if (isBusy) "请稍候" else confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) { Text("取消") }
        }
    )
}

private suspend fun readBackupFile(context: android.content.Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: error("Failed to read backup file")
    }

private suspend fun writeDownloadFile(
    context: android.content.Context,
    filename: String,
    mimeType: String,
    content: String
) = withContext(Dispatchers.IO) {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, filename)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = context.contentResolver.insert(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        values
    ) ?: error("Failed to create Downloads entry")
    try {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        } ?: error("Failed to write export file")
    } catch (error: Throwable) {
        context.contentResolver.delete(uri, null, null)
        throw error
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
