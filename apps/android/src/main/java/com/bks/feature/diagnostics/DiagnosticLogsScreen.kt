package com.bks.feature.diagnostics

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
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
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var exportResult by remember { mutableStateOf<DiagnosticExportResult?>(null) }
    val exportWriter = remember(context, exportWriterOverride) {
        exportWriterOverride ?: { encrypted: String -> writeDiagnosticExport(context, encrypted) }
    }
    val exportHandlers = DiagnosticExportHandlers({ isExporting = it }, { exportJob = it }, { showExportDialog = false }, { exportResult = it }, { statusMessage = it })

    DiagnosticLogsEffects(lifecycleOwner, repository, showSensitive, applySecureWindowFlag) { showSensitive = false }

    DiagnosticLogsContent(
        state = DiagnosticLogsContentState(enabled, stats, showSensitive, statusMessage),
        events = events,
        actions = DiagnosticLogsContentActions(
            onBack = onBack,
            onEnabledChange = { checked ->
                if (checked && !isDebugBuild) showEnableConfirmation = true else repository.setEnabled(checked, userConfirmed = isDebugBuild)
            },
            onRefresh = { scope.launch { repository.refresh() } },
            onExportClick = { showExportDialog = true },
            onClearClick = { showClearConfirmation = true },
            onToggleSensitive = { if (showSensitive) showSensitive = false else showRevealConfirmation = true }
        ),
        modifier = modifier
    )

    if (showEnableConfirmation) {
        EnableDiagnosticsConfirmationDialog(
            onConfirm = {
                repository.setEnabled(true, userConfirmed = true)
                showEnableConfirmation = false
            },
            onDismiss = { showEnableConfirmation = false }
        )
    }
    if (showRevealConfirmation) {
        RevealSensitiveConfirmationDialog(
            onConfirm = {
                showSensitive = true
                showRevealConfirmation = false
            },
            onDismiss = { showRevealConfirmation = false }
        )
    }
    if (showClearConfirmation) {
        ClearDiagnosticsConfirmationDialog(
            onConfirm = {
                showClearConfirmation = false
                scope.launch {
                    repository.clear()
                    statusMessage = "诊断日志和设备密钥已清空。"
                }
            },
            onDismiss = { showClearConfirmation = false }
        )
    }
    if (showExportDialog) {
        DiagnosticExportDialog(
            exporting = isExporting,
            onDismiss = { cancelDiagnosticExport(exportJob, isExporting, exportHandlers) },
            onExport = { passphrase ->
                if (!isExporting) {
                    isExporting = true
                    exportJob = scope.launch { runDiagnosticExport(passphrase, repository, exportWriter, exportHandlers) }
                }
            }
        )
    }
    exportResult?.let { result ->
        ExportResultDialog(result.title, result.message) { exportResult = null }
    }
}

private data class DiagnosticExportResult(
    val title: String,
    val message: String
)

private class DiagnosticExportHandlers(
    val onExportingChange: (Boolean) -> Unit,
    val onJobChange: (Job?) -> Unit,
    val onDialogClosed: () -> Unit,
    val onResult: (DiagnosticExportResult) -> Unit,
    val onMessage: (String) -> Unit
)

private fun cancelDiagnosticExport(
    job: Job?,
    wasExporting: Boolean,
    handlers: DiagnosticExportHandlers
) {
    job?.cancel()
    handlers.onJobChange(null)
    handlers.onExportingChange(false)
    handlers.onDialogClosed()
    if (wasExporting) {
        handlers.onResult(
            DiagnosticExportResult(
                title = "导出已取消",
                message = "未创建诊断日志导出文件。"
            )
        )
    }
}

private suspend fun runDiagnosticExport(
    passphrase: CharArray,
    repository: DiagnosticLogRepository,
    exportWriter: (String) -> String,
    handlers: DiagnosticExportHandlers
) {
    try {
        val encrypted = repository.exportEncrypted(passphrase)
        val displayName = withContext(Dispatchers.IO) {
            exportWriter(encrypted)
        }
        val message = "已导出 $displayName 到 Downloads；删除本机数据不会删除该文件，请自行保管或删除。"
        handlers.onMessage(message)
        handlers.onResult(
            DiagnosticExportResult(
                title = "导出完成",
                message = message
            )
        )
        handlers.onDialogClosed()
    } catch (_: CancellationException) {
        // The dismiss action owns the visible cancellation result.
    } catch (_: Throwable) {
        val message = "导出失败，请确认本机存储状态后重试。"
        handlers.onMessage(message)
        handlers.onResult(
            DiagnosticExportResult(
                title = "导出失败",
                message = message
            )
        )
        handlers.onDialogClosed()
    } finally {
        passphrase.fill('\u0000')
        handlers.onJobChange(null)
        handlers.onExportingChange(false)
    }
}

@Composable
private fun DiagnosticLogsEffects(
    lifecycleOwner: LifecycleOwner,
    repository: DiagnosticLogRepository,
    showSensitive: Boolean,
    applySecureWindowFlag: Boolean,
    onBackground: () -> Unit
) {
    LaunchedEffect(Unit) { repository.refresh() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) onBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    SecureWindowWhile(showSensitive && applySecureWindowFlag)
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

private fun writeDiagnosticExport(context: Context, encrypted: String): String {
    val displayName = "bks-diagnostics-${System.currentTimeMillis()}.$DIAGNOSTICS_EXPORT_EXTENSION"
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
