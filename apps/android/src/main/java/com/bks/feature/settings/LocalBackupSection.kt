package com.bks.feature.settings

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bks.feature.ledger.LedgerUiEntry
import com.bks.ui.components.Button
import com.bks.ui.components.OutlinedButton
import com.bks.ui.components.OutlinedTextField
import com.bks.ui.components.TextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingRestore(
    val backup: String,
    val passphrase: String
)

internal class LocalBackupSectionActions(
    val onExportEncryptedBackup: suspend (String) -> String,
    val onValidateEncryptedBackup: suspend (String, String) -> Unit,
    val onImportEncryptedBackup: suspend (String, String) -> Unit
)

private class ImportDialogCallbacks(
    val onDismiss: () -> Unit,
    val onValidated: (PendingRestore) -> Unit
)

@Composable
internal fun LocalBackupSection(
    ledgerEntries: List<LedgerUiEntry>,
    currentLedgerName: String,
    actions: LocalBackupSectionActions,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedBackup by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var isReadingBackup by remember { mutableStateOf(false) }

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
                } else {
                    snackbarHostState.showSnackbar("所选文件不是受支持的加密备份")
                }
            }.onFailure {
                snackbarHostState.showSnackbar("备份文件读取失败")
            }
        }
    }

    CardSection(title = "导出与恢复") {
        Text(
            "CSV 仅导出当前账本「$currentLedgerName」，且是明文表格；" +
                "加密备份包含全部账本及本机设置。"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CsvExportButton(
                ledgerEntries = ledgerEntries,
                currentLedgerName = currentLedgerName,
                context = context,
                coroutineScope = coroutineScope,
                snackbarHostState = snackbarHostState
            )
            OutlinedButton(
                onClick = { showExportPasswordDialog = true }
            ) { Text("导出加密备份") }
        }
        OutlinedButton(
            onClick = { openDocumentLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
            enabled = !isReadingBackup
        ) { Text("导入加密备份") }
    }

    if (showExportPasswordDialog) {
        ExportBackupDialog(
            context = context,
            coroutineScope = coroutineScope,
            actions = actions,
            snackbarHostState = snackbarHostState,
            onDismissed = { showExportPasswordDialog = false }
        )
    }

    selectedBackup?.let { backup ->
        ImportBackupDialog(
            backup = backup,
            coroutineScope = coroutineScope,
            actions = actions,
            callbacks = ImportDialogCallbacks(
                onDismiss = { selectedBackup = null },
                onValidated = {
                    pendingRestore = it
                    selectedBackup = null
                }
            )
        )
    }

    pendingRestore?.let { restore ->
        RestoreConfirmationDialog(
            restore = restore,
            coroutineScope = coroutineScope,
            actions = actions,
            snackbarHostState = snackbarHostState,
            onDismissed = { pendingRestore = null }
        )
    }
}

@Composable
private fun CsvExportButton(
    ledgerEntries: List<LedgerUiEntry>,
    currentLedgerName: String,
    context: Context,
    coroutineScope: CoroutineScope,
    snackbarHostState: SnackbarHostState
) {
    var isCsvExporting by remember { mutableStateOf(false) }
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
}

@Composable
private fun ExportBackupDialog(
    context: Context,
    coroutineScope: CoroutineScope,
    actions: LocalBackupSectionActions,
    snackbarHostState: SnackbarHostState,
    onDismissed: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    BackupPasswordDialog(
        title = "导出加密备份",
        description = "请输入大于 8 位的备份密码。密码不会保存，丢失后无法恢复备份。",
        passphrase = passphrase,
        onPassphraseChange = { passphrase = it },
        confirmLabel = "确认导出",
        isBusy = isBusy,
        minimumLength = MIN_BACKUP_PASSPHRASE_LENGTH,
        onDismiss = {
            if (!isBusy) {
                passphrase = ""
                onDismissed()
            }
        },
        onConfirm = {
            isBusy = true
            coroutineScope.launch {
                val result = runCatching {
                    val content = actions.onExportEncryptedBackup(passphrase)
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
                isBusy = false
                passphrase = ""
                onDismissed()
                result.onSuccess {
                    snackbarHostState.showSnackbar("备份已保存到 Downloads/$it")
                }.onFailure {
                    snackbarHostState.showSnackbar("加密备份失败")
                }
            }
        }
    )
}

@Composable
private fun ImportBackupDialog(
    backup: String,
    coroutineScope: CoroutineScope,
    actions: LocalBackupSectionActions,
    callbacks: ImportDialogCallbacks
) {
    var passphrase by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    BackupPasswordDialog(
        title = "输入备份密码",
        description = "该文件是加密备份。请输入密码，验证通过后才能恢复。",
        passphrase = passphrase,
        onPassphraseChange = {
            passphrase = it
            passwordError = null
        },
        confirmLabel = "确认",
        isBusy = isBusy,
        errorMessage = passwordError,
        onDismiss = {
            if (!isBusy) {
                passphrase = ""
                passwordError = null
                callbacks.onDismiss()
            }
        },
        onConfirm = {
            isBusy = true
            coroutineScope.launch {
                val result = runCatching { actions.onValidateEncryptedBackup(backup, passphrase) }
                isBusy = false
                result.onSuccess {
                    callbacks.onValidated(PendingRestore(backup, passphrase))
                }.onFailure {
                    passwordError = "密码错误，或备份文件已损坏，请重试"
                }
            }
        }
    )
}

@Composable
private fun RestoreConfirmationDialog(
    restore: PendingRestore,
    coroutineScope: CoroutineScope,
    actions: LocalBackupSectionActions,
    snackbarHostState: SnackbarHostState,
    onDismissed: () -> Unit
) {
    var isBusy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {
            if (!isBusy) onDismissed()
        },
        title = { Text("确认恢复备份") },
        text = { Text("备份已通过校验。继续将替换本机现有数据，此操作无法撤销。") },
        confirmButton = {
            Button(onClick = {
                isBusy = true
                coroutineScope.launch {
                    val result = runCatching {
                        actions.onImportEncryptedBackup(restore.backup, restore.passphrase)
                    }
                    isBusy = false
                    onDismissed()
                    result.onSuccess {
                        snackbarHostState.showSnackbar("备份已恢复成功")
                    }.onFailure {
                        snackbarHostState.showSnackbar("备份恢复失败，本机数据未更改")
                    }
                }
            }, enabled = !isBusy) {
                Text(if (isBusy) "正在恢复" else "确认替换并恢复")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissed,
                enabled = !isBusy
            ) { Text("取消") }
        }
    )
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

private suspend fun readBackupFile(context: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: error("Failed to read backup file")
    }

private suspend fun writeDownloadFile(
    context: Context,
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
