package com.autoaccounting.feature.diagnostics

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.autoaccounting.ui.components.Button
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.TextButton
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DiagnosticLogsScreen(
    isDebugBuild: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    repositoryOverride: DiagnosticLogRepository? = null,
    applySecureWindowFlag: Boolean = true,
    exportWriterOverride: ((String) -> String)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = repositoryOverride ?: remember(context.applicationContext) {
        DiagnosticLogs.get(context)
    }
    val enabled by repository.enabled.collectAsState()
    val events by repository.events.collectAsState()
    val stats by repository.stats.collectAsState()
    val scope = rememberCoroutineScope()
    var showEnableConfirmation by remember { mutableStateOf(false) }
    var showRevealConfirmation by remember { mutableStateOf(false) }
    var showSensitive by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var levelFilter by remember { mutableStateOf<DiagnosticLevel?>(null) }
    var componentFilter by remember { mutableStateOf<DiagnosticComponent?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var exportResult by remember { mutableStateOf<DiagnosticExportResult?>(null) }
    val exportWriter = remember(context, exportWriterOverride) {
        exportWriterOverride ?: { encrypted: String -> writeDiagnosticExport(context, encrypted) }
    }

    LaunchedEffect(Unit) { repository.refresh() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) showSensitive = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    SecureWindowWhile(showSensitive && applySecureWindowFlag)

    val filtered = events.filter { event ->
        (levelFilter == null || event.metadata.level == levelFilter) &&
            (componentFilter == null || event.metadata.component == componentFilter) &&
            queryMatches(event, query)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(20.dp).testTag("diagnostic-event-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { TextButton(onClick = onBack) { Text("返回合规与隐私") } }
        item {
            Text("诊断日志", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        item { Text("日志仅保存在本机加密目录；关闭后保留历史，清空会同时删除密文和设备密钥。") }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(if (enabled) "记录已开启" else "记录已关闭", fontWeight = FontWeight.SemiBold)
                    Text("${stats.eventCount} 条 · ${stats.segmentCount} 段 · ${formatBytes(stats.encryptedBytes)}")
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        if (checked && !isDebugBuild) {
                            showEnableConfirmation = true
                        } else {
                            repository.setEnabled(checked, userConfirmed = isDebugBuild)
                        }
                    },
                    modifier = Modifier.testTag("diagnostic-enabled-switch")
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { scope.launch { repository.refresh() } }) { Text("刷新") }
                OutlinedButton(onClick = { showExportDialog = true }) { Text("加密导出") }
                OutlinedButton(onClick = { showClearConfirmation = true }) { Text("清空") }
                OutlinedButton(
                    onClick = {
                        if (showSensitive) showSensitive = false else showRevealConfirmation = true
                    }
                ) { Text(if (showSensitive) "遮罩内容" else "显示敏感内容") }
            }
        }
        statusMessage?.let { message ->
            item { Text(message, style = MaterialTheme.typography.bodySmall) }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("筛选事件、原因、traceId / sessionId") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = levelFilter == null, onClick = { levelFilter = null }, label = { Text("全部级别") })
                DiagnosticLevel.entries.forEach { level ->
                    FilterChip(
                        selected = levelFilter == level,
                        onClick = { levelFilter = level },
                        label = { Text(level.name) }
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = componentFilter == null, onClick = { componentFilter = null }, label = { Text("全部组件") })
                DiagnosticComponent.entries.forEach { component ->
                    FilterChip(
                        selected = componentFilter == component,
                        onClick = { componentFilter = component },
                        label = { Text(component.name) }
                    )
                }
            }
        }
        items(filtered, key = { "${it.metadata.timestampEpochMillis}-${it.metadata.traceId}-${it.metadata.event}" }) {
            DiagnosticEventCard(it, showSensitive)
        }
    }

    if (showEnableConfirmation) {
        AlertDialog(
            onDismissRequest = { showEnableConfirmation = false },
            title = { Text("开启敏感诊断日志？") },
            text = {
                Text(
                    "将记录支付通知、支付页与 OCR 文字、金额、商户、备注、支付账号/方式、订单号、交易证据和完整异常。" +
                        "日志会长期保留，最多占用 10 MB，超过上限才删除最旧分段；设备内加密且不会上传。" +
                        "你可以随时关闭或清空。导出文件使用口令加密，但分享后仍有泄露风险。截图本身不会保存，认证秘密始终脱敏。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    repository.setEnabled(true, userConfirmed = true)
                    showEnableConfirmation = false
                }) { Text("理解并开启") }
            },
            dismissButton = { TextButton(onClick = { showEnableConfirmation = false }) { Text("取消") } }
        )
    }
    if (showRevealConfirmation) {
        AlertDialog(
            onDismissRequest = { showRevealConfirmation = false },
            title = { Text("显示敏感交易内容？") },
            text = { Text("仅本次页面会话显示；离开页面或应用进入后台后会立即重新遮罩，并禁止系统截图。") },
            confirmButton = {
                Button(onClick = {
                    showSensitive = true
                    showRevealConfirmation = false
                }) { Text("显示") }
            },
            dismissButton = { TextButton(onClick = { showRevealConfirmation = false }) { Text("取消") } }
        )
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("确认清空诊断日志？") },
            text = { Text("将删除全部日志分段和设备密钥，无法恢复；记录开关状态保持不变。") },
            confirmButton = {
                Button(onClick = {
                    showClearConfirmation = false
                    scope.launch {
                        repository.clear()
                        statusMessage = "诊断日志和设备密钥已清空。"
                    }
                }) { Text("确认清空") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text("取消") } }
        )
    }
    if (showExportDialog) {
        DiagnosticExportDialog(
            exporting = isExporting,
            onDismiss = {
                val wasExporting = isExporting
                exportJob?.cancel()
                exportJob = null
                isExporting = false
                showExportDialog = false
                if (wasExporting) {
                    exportResult = DiagnosticExportResult(
                        title = "导出已取消",
                        message = "未创建诊断日志导出文件。"
                    )
                }
            },
            onExport = { passphrase ->
                if (!isExporting) {
                    isExporting = true
                    exportJob = scope.launch {
                        try {
                            val encrypted = repository.exportEncrypted(passphrase)
                            val displayName = withContext(Dispatchers.IO) {
                                exportWriter(encrypted)
                            }
                            val message = "已导出 $displayName 到 Downloads；删除本机数据不会删除该文件，请自行保管或删除。"
                            statusMessage = message
                            exportResult = DiagnosticExportResult(
                                title = "导出完成",
                                message = message
                            )
                            showExportDialog = false
                        } catch (_: CancellationException) {
                            // The dismiss action owns the visible cancellation result.
                        } catch (_: Throwable) {
                            val message = "导出失败，请确认本机存储状态后重试。"
                            statusMessage = message
                            exportResult = DiagnosticExportResult(
                                title = "导出失败",
                                message = message
                            )
                            showExportDialog = false
                        } finally {
                            passphrase.fill('\u0000')
                            isExporting = false
                            exportJob = null
                        }
                    }
                }
            }
        )
    }
    exportResult?.let { result ->
        AlertDialog(
            onDismissRequest = { exportResult = null },
            title = { Text(result.title) },
            text = { Text(result.message, modifier = Modifier.testTag("diagnostic-export-result-message")) },
            confirmButton = {
                Button(onClick = { exportResult = null }) { Text("确定") }
            }
        )
    }
}

private data class DiagnosticExportResult(
    val title: String,
    val message: String
)

@Composable
private fun DiagnosticEventCard(event: DiagnosticEvent, showSensitive: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${event.metadata.level.name} · ${event.metadata.component.name} · ${event.metadata.event}", fontWeight = FontWeight.SemiBold)
            Text(DateFormat.getDateTimeInstance().format(Date(event.metadata.timestampEpochMillis)))
            Text("reason=${event.metadata.reason.orEmpty()} outcome=${event.metadata.outcome.orEmpty()}")
            if (event.metadata.count != null || event.metadata.durationMillis != null) {
                Text(
                    "count=${event.metadata.count ?: 0} durationMs=${event.metadata.durationMillis ?: 0}"
                )
            }
            Text("traceId=${event.metadata.traceId}")
            event.metadata.sessionId?.let { Text("sessionId=$it") }
            if (event.metadata.suppressedCount > 0) Text("合并重复 ${event.metadata.suppressedCount} 次")
            if (event.sensitivePayload.fields.isNotEmpty()) {
                if (showSensitive) {
                    event.sensitivePayload.fields.forEach { (field, value) ->
                        Text("${field.name}: $value")
                    }
                    if (event.truncatedFields.isNotEmpty()) {
                        Text("已截断：${event.truncatedFields.joinToString { it.name }}")
                    }
                } else {
                    Text("敏感内容：••••••")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticExportDialog(
    exporting: Boolean,
    onDismiss: () -> Unit,
    onExport: (CharArray) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val valid = passphrase.length >= MIN_EXPORT_PASSPHRASE_LENGTH && passphrase == confirmation
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("口令加密导出") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("口令至少 8 位，不会被保存。忘记口令将无法解密导出文件。")
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("导出口令") },
                    enabled = !exporting,
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.testTag("diagnostic-export-passphrase")
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("再次输入口令") },
                    enabled = !exporting,
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.testTag("diagnostic-export-confirmation")
                )
                if (exporting) Text("正在读取并加密日志，请稍候。")
            }
        },
        confirmButton = {
            Button(
                enabled = valid && !exporting,
                onClick = { onExport(passphrase.toCharArray()) },
                modifier = Modifier.testTag("diagnostic-export-confirm")
            ) { Text(if (exporting) "正在导出…" else "导出") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("diagnostic-export-cancel")
            ) { Text(if (exporting) "取消导出" else "取消") }
        }
    )
}

@Composable
private fun SecureWindowWhile(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        if (enabled) setDiagnosticSecureFlag(activity, true)
        onDispose {
            if (enabled) setDiagnosticSecureFlag(activity, false)
        }
    }
}

internal fun setDiagnosticSecureFlag(activity: Activity?, enabled: Boolean) {
    if (enabled) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun queryMatches(event: DiagnosticEvent, query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim()
    return listOf(
        event.metadata.event,
        event.metadata.reason,
        event.metadata.traceId,
        event.metadata.sessionId
    ).any { it?.contains(needle, ignoreCase = true) == true }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun writeDiagnosticExport(context: Context, encrypted: String): String {
    val displayName = "auto-accounting-diagnostics-${System.currentTimeMillis()}.$DIAGNOSTICS_EXPORT_EXTENSION"
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
        "Cannot create export file"
    }
    try {
        checkNotNull(context.contentResolver.openOutputStream(uri, "w")).use { output ->
            output.write(encrypted.toByteArray(Charsets.UTF_8))
        }
    } catch (error: Throwable) {
        context.contentResolver.delete(uri, null, null)
        throw error
    }
    return displayName
}
