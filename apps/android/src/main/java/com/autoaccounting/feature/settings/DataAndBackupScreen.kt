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
import com.autoaccounting.api.LedgerSyncConflictChoiceContract
import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncJsonContracts
import com.autoaccounting.api.LedgerSyncPayloadContract
import com.autoaccounting.feature.sync.LedgerSyncInitialMode
import com.autoaccounting.feature.sync.LedgerSyncOperationResult
import com.autoaccounting.feature.sync.LedgerSyncPreview
import com.autoaccounting.feature.sync.LedgerSyncUiState
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

private fun formatSyncTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

private fun syncEntityLabel(entityType: String): String = when (
    runCatching { LedgerSyncEntityTypeContract.valueOf(entityType) }.getOrNull()
) {
    LedgerSyncEntityTypeContract.CATEGORY -> "分类冲突"
    LedgerSyncEntityTypeContract.FUNDING_ACCOUNT -> "资金账户冲突"
    LedgerSyncEntityTypeContract.LEDGER_BOOK -> "账本冲突"
    LedgerSyncEntityTypeContract.LEDGER_ENTRY -> "账目冲突"
    LedgerSyncEntityTypeContract.CATEGORIZATION_RULE -> "分类规则冲突"
    null -> "同步冲突"
}

private fun syncPayloadSummary(entityType: String, payload: String?, deleted: Boolean): String {
    if (deleted || payload == null) return "已删除"
    val type = runCatching { LedgerSyncEntityTypeContract.valueOf(entityType) }.getOrNull()
        ?: return "数据已更新"
    return runCatching {
        when (val parsed = LedgerSyncJsonContracts.parsePayload(type, payload)) {
            is LedgerSyncPayloadContract.Category -> parsed.name
            is LedgerSyncPayloadContract.FundingAccount -> parsed.label
            is LedgerSyncPayloadContract.LedgerBook -> parsed.name
            is LedgerSyncPayloadContract.LedgerEntry ->
                "${parsed.merchantTitle} · ${parsed.amountMinor / 100.0} ${parsed.currency}"
            is LedgerSyncPayloadContract.CategorizationRule ->
                "${parsed.merchantContains.ifBlank { parsed.titleContains }} → ${parsed.category}"
        }
    }.getOrDefault("数据已更新")
}

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
    var syncPreview by remember { mutableStateOf<LedgerSyncPreview?>(null) }
    var syncBusy by remember { mutableStateOf(false) }

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
        CardSection(title = "账户同步") {
            when {
                !ledgerSyncState.signedIn -> Text("登录账户后可在多台设备间同步正式账本数据。")
                !ledgerSyncState.enabled -> {
                    Text("同步账本、正式及最近删除账目、分类、资金账户和分类规则。待确认记录与设备设置不会上传。")
                    Button(
                        onClick = {
                            syncBusy = true
                            coroutineScope.launch {
                                when (val result = onPreviewLedgerSync()) {
                                    is LedgerSyncOperationResult.Success -> syncPreview = result.value
                                    is LedgerSyncOperationResult.Failure -> snackbarHostState.showSnackbar(result.message)
                                }
                                syncBusy = false
                            }
                        },
                        enabled = !syncBusy,
                        modifier = Modifier.testTag("ledger-sync-enable")
                    ) { Text(if (syncBusy) "正在检查" else "启用账户同步") }
                }
                else -> {
                    Text(
                        ledgerSyncState.lastSuccessAtMillis?.let {
                            "最近同步：${formatSyncTime(it)}"
                        } ?: "尚未完成首次同步"
                    )
                    Text("待上传 ${ledgerSyncState.pendingCount} 项 · 冲突 ${ledgerSyncState.conflicts.size} 项")
                    ledgerSyncState.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (ledgerSyncState.insecureHttpTestMode) {
                        Text(
                            "当前为局域网 HTTP 测试同步，账本内容未经过传输加密。",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                syncBusy = true
                                coroutineScope.launch {
                                    val result = onSyncNow()
                                    snackbarHostState.showSnackbar(
                                        when (result) {
                                            is LedgerSyncOperationResult.Success -> "同步完成"
                                            is LedgerSyncOperationResult.Failure -> result.message
                                        }
                                    )
                                    syncBusy = false
                                }
                            },
                            enabled = !syncBusy,
                            modifier = Modifier.testTag("ledger-sync-now")
                        ) { Text(if (syncBusy) "同步中" else "立即同步") }
                        OutlinedButton(
                            onClick = { coroutineScope.launch { onDisableLedgerSync() } },
                            enabled = !syncBusy
                        ) { Text("关闭同步") }
                    }
                    ledgerSyncState.conflicts.forEach { conflict ->
                        Card(
                            modifier = Modifier.fillMaxWidth().testTag("ledger-sync-conflict-${conflict.conflictId}"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(syncEntityLabel(conflict.entityType), fontWeight = FontWeight.SemiBold)
                                Text("云端：${syncPayloadSummary(conflict.entityType, conflict.canonicalPayload, conflict.canonicalDeleted)}")
                                Text("本机：${syncPayloadSummary(conflict.entityType, conflict.candidatePayload, conflict.candidateDeleted)}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        coroutineScope.launch {
                                            onResolveLedgerSyncConflict(
                                                conflict.conflictId,
                                                conflict.canonicalVersion,
                                                LedgerSyncConflictChoiceContract.CANONICAL
                                            )
                                        }
                                    }) { Text("保留云端") }
                                    Button(onClick = {
                                        coroutineScope.launch {
                                            onResolveLedgerSyncConflict(
                                                conflict.conflictId,
                                                conflict.canonicalVersion,
                                                LedgerSyncConflictChoiceContract.CANDIDATE
                                            )
                                        }
                                    }) { Text("保留本机") }
                                }
                            }
                        }
                    }
                }
            }
        }
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

    syncPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!syncBusy) syncPreview = null },
            title = { Text("启用账户同步") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本机 ${preview.localRecordCount} 项，云端 ${preview.cloudRecordCount} 项。")
                    Text("正式账本数据将上传并以服务端可读取的形式保存；待确认记录、设备设置和诊断日志不会上传。")
                    Text("生产环境仅通过 HTTPS 传输；账号最终注销将删除云端同步数据，本机账本仍会保留。")
                    if (preview.insecureHttpTestMode) {
                        Text("当前使用局域网 HTTP，仅适用于受控测试环境。", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        syncBusy = true
                        coroutineScope.launch {
                            val mode = if (preview.localRecordCount == 0 && preview.cloudRecordCount > 0) {
                                LedgerSyncInitialMode.REPLACE_LOCAL
                            } else {
                                LedgerSyncInitialMode.MERGE
                            }
                            val result = onEnableLedgerSync(mode)
                            if (result is LedgerSyncOperationResult.Success) syncPreview = null
                            snackbarHostState.showSnackbar(
                                when (result) {
                                    is LedgerSyncOperationResult.Success -> "账户同步已启用"
                                    is LedgerSyncOperationResult.Failure -> result.message
                                }
                            )
                            syncBusy = false
                        }
                    },
                    enabled = !syncBusy
                ) { Text(if (preview.localRecordCount > 0 && preview.cloudRecordCount > 0) "确认并合并" else "确认启用") }
            },
            dismissButton = { TextButton(onClick = { syncPreview = null }) { Text("取消") } }
        )
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
