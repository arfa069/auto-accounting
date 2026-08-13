package com.autoaccounting.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoaccounting.api.LedgerSyncConflictChoiceContract
import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncJsonContracts
import com.autoaccounting.api.LedgerSyncPayloadContract
import com.autoaccounting.data.local.AccountSyncConflictEntity
import com.autoaccounting.feature.sync.LedgerSyncInitialMode
import com.autoaccounting.feature.sync.LedgerSyncOperationResult
import com.autoaccounting.feature.sync.LedgerSyncPreview
import com.autoaccounting.feature.sync.LedgerSyncUiState
import com.autoaccounting.ui.components.Button
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.TextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
internal fun LedgerSyncSettingsSection(
    ledgerSyncState: LedgerSyncUiState,
    actions: LedgerSyncSectionActions,
    snackbarHostState: SnackbarHostState
) {
    val coroutineScope = rememberCoroutineScope()
    var syncPreview by remember { mutableStateOf<LedgerSyncPreview?>(null) }

    CardSection(title = "账户同步") {
        when {
            !ledgerSyncState.signedIn -> Text("登录账户后可在多台设备间同步正式账本数据。")
            !ledgerSyncState.enabled -> EnableSyncContent(
                coroutineScope = coroutineScope,
                actions = actions,
                snackbarHostState = snackbarHostState,
                onPreviewed = { syncPreview = it }
            )
            else -> EnabledSyncContent(
                ledgerSyncState = ledgerSyncState,
                coroutineScope = coroutineScope,
                actions = actions,
                snackbarHostState = snackbarHostState
            )
        }
    }

    syncPreview?.let { preview ->
        FirstSyncConfirmationDialog(
            preview = preview,
            coroutineScope = coroutineScope,
            actions = actions,
            snackbarHostState = snackbarHostState,
            onDismiss = { syncPreview = null }
        )
    }
}

@Composable
private fun EnableSyncContent(
    coroutineScope: CoroutineScope,
    actions: LedgerSyncSectionActions,
    snackbarHostState: SnackbarHostState,
    onPreviewed: (LedgerSyncPreview) -> Unit
) {
    var syncBusy by remember { mutableStateOf(false) }
    Text("同步账本、正式及最近删除账目、分类、资金账户和分类规则。默认资金账户仅按账号同步，不进入账本同步。")
    Button(
        onClick = {
            syncBusy = true
            coroutineScope.launch {
                when (val result = actions.onPreview()) {
                    is LedgerSyncOperationResult.Success -> onPreviewed(result.value)
                    is LedgerSyncOperationResult.Failure -> snackbarHostState.showSnackbar(result.message)
                }
                syncBusy = false
            }
        },
        enabled = !syncBusy,
        modifier = Modifier.testTag("ledger-sync-enable")
    ) { Text(if (syncBusy) "正在检查" else "启用账户同步") }
}

@Composable
private fun EnabledSyncContent(
    ledgerSyncState: LedgerSyncUiState,
    coroutineScope: CoroutineScope,
    actions: LedgerSyncSectionActions,
    snackbarHostState: SnackbarHostState
) {
    var syncBusy by remember { mutableStateOf(false) }
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
                    val result = actions.onSyncNow()
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
            onClick = { coroutineScope.launch { actions.onDisable() } },
            enabled = !syncBusy
        ) { Text("关闭同步") }
    }
    ledgerSyncState.conflicts.forEach { conflict ->
        SyncConflictCard(
            conflict = conflict,
            coroutineScope = coroutineScope,
            actions = actions
        )
    }
}

@Composable
private fun SyncConflictCard(
    conflict: AccountSyncConflictEntity,
    coroutineScope: CoroutineScope,
    actions: LedgerSyncSectionActions
) {
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
                        actions.onResolveConflict(
                            conflict.conflictId,
                            conflict.canonicalVersion,
                            LedgerSyncConflictChoiceContract.CANONICAL
                        )
                    }
                }) { Text("保留云端") }
                Button(onClick = {
                    coroutineScope.launch {
                        actions.onResolveConflict(
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

@Composable
private fun FirstSyncConfirmationDialog(
    preview: LedgerSyncPreview,
    coroutineScope: CoroutineScope,
    actions: LedgerSyncSectionActions,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit
) {
    var syncBusy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!syncBusy) onDismiss() },
        title = { Text("启用账户同步") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本机 ${preview.localRecordCount} 项，云端 ${preview.cloudRecordCount} 项。")
                Text("正式账本数据将上传并以服务端可读取的形式保存；待确认记录和诊断日志不会上传。默认资金账户单独按账号保存。")
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
                        val result = actions.onEnable(mode)
                        if (result is LedgerSyncOperationResult.Success) onDismiss()
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
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

internal class LedgerSyncSectionActions(
    val onPreview: suspend () -> LedgerSyncOperationResult<LedgerSyncPreview>,
    val onEnable: suspend (LedgerSyncInitialMode) -> LedgerSyncOperationResult<Unit>,
    val onSyncNow: suspend () -> LedgerSyncOperationResult<Unit>,
    val onDisable: suspend () -> Unit,
    val onResolveConflict: suspend (String, Long, LedgerSyncConflictChoiceContract) ->
        LedgerSyncOperationResult<Unit>
)
