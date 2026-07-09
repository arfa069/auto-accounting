package com.autoaccounting.feature.categorization

import android.net.Uri

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.autoaccounting.feature.account.AccountDeletionUiAction
import com.autoaccounting.feature.account.AccountDeletionUiState
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.account.reduceAccountDeletionState
import com.autoaccounting.feature.beta.InternalBetaReadinessScreen
import com.autoaccounting.feature.compliance.AUTO_ACCOUNTING_COMPLIANCE
import com.autoaccounting.feature.compliance.ComplianceMaterialsScreen
import com.autoaccounting.feature.compliance.PermissionExplanationId
import com.autoaccounting.feature.compliance.permissionPurpose
import com.autoaccounting.feature.ledger.LedgerUiEntry
import com.autoaccounting.feature.monitoring.ContinuousMonitoringAction
import com.autoaccounting.feature.monitoring.ContinuousMonitoringBlockReason
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.monitoring.reduceContinuousMonitoringState
import com.autoaccounting.feature.settings.DELETE_LOCAL_DATA_PHRASE
import com.autoaccounting.feature.settings.LocalDataDeletionAction
import com.autoaccounting.feature.settings.LocalDataDeletionState
import com.autoaccounting.feature.settings.exportLedgerCsv
import com.autoaccounting.feature.settings.reduceLocalDataDeletionState
import kotlinx.coroutines.launch

@Composable
fun CategorizationRulesScreen(
    modifier: Modifier = Modifier,
    showPermissionCenter: Boolean = false,
    aiSettings: AiCategorizationSettings = AiCategorizationSettings(),
    onAiSettingsChange: (AiCategorizationSettings) -> Unit = {},
    ledgerEntries: List<LedgerUiEntry> = emptyList(),
    onExportEncryptedBackup: suspend (String) -> String = {
        error("Backup repository unavailable")
    },
    onImportEncryptedBackup: suspend (String, String) -> Unit = { _, _ ->
        error("Backup repository unavailable")
    },
    onDeleteLocalData: () -> Unit = {},
    notificationListenerAccessGranted: Boolean = false,
    onOpenNotificationListenerSettings: () -> Unit = {},
    billSyncAccessibilityAccessGranted: Boolean = false,
    onOpenBillSyncAccessibilitySettings: () -> Unit = {},
    accountSession: AccountSession? = null,
    accountDeletionState: AccountDeletionUiState = AccountDeletionUiState(),
    onAccountDeletionStateChange: (AccountDeletionUiState) -> Unit = {},
    continuousMonitoringState: ContinuousMonitoringState = ContinuousMonitoringState(),
    continuousMonitoringPermissionHealth: ContinuousMonitoringPermissionHealth =
        ContinuousMonitoringPermissionHealth(),
    onContinuousMonitoringStateChange: (ContinuousMonitoringState) -> Unit = {},
    snackbarHostState: SnackbarHostState = SnackbarHostState(),
    onSaveBackupToDownloads: (String) -> String = { "" },
    onPickBackupFile: ((Uri) -> Unit) -> Unit = {},
    onReadBackupFile: (Uri) -> String = { "" }
) {
    var rules by remember { mutableStateOf(emptyList<CategorizationRule>()) }
    CategorizationRulesScreen(
        rules = rules,
        onRulesChange = { rules = it },
        modifier = modifier,
        showPermissionCenter = showPermissionCenter,
        aiSettings = aiSettings,
        onAiSettingsChange = onAiSettingsChange,
        ledgerEntries = ledgerEntries,
        onExportEncryptedBackup = onExportEncryptedBackup,
        onImportEncryptedBackup = onImportEncryptedBackup,
        onDeleteLocalData = onDeleteLocalData,
        notificationListenerAccessGranted = notificationListenerAccessGranted,
        onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
        billSyncAccessibilityAccessGranted = billSyncAccessibilityAccessGranted,
        onOpenBillSyncAccessibilitySettings = onOpenBillSyncAccessibilitySettings,
        accountSession = accountSession,
        accountDeletionState = accountDeletionState,
        onAccountDeletionStateChange = onAccountDeletionStateChange,
        continuousMonitoringState = continuousMonitoringState,
        continuousMonitoringPermissionHealth = continuousMonitoringPermissionHealth,
        onContinuousMonitoringStateChange = onContinuousMonitoringStateChange,
        snackbarHostState = snackbarHostState,
        onSaveBackupToDownloads = onSaveBackupToDownloads,
        onPickBackupFile = onPickBackupFile,
        onReadBackupFile = onReadBackupFile
    )
}

@Composable
fun CategorizationRulesScreen(
    rules: List<CategorizationRule>,
    onRulesChange: (List<CategorizationRule>) -> Unit,
    modifier: Modifier = Modifier,
    showPermissionCenter: Boolean = false,
    aiSettings: AiCategorizationSettings = AiCategorizationSettings(),
    onAiSettingsChange: (AiCategorizationSettings) -> Unit = {},
    ledgerEntries: List<LedgerUiEntry> = emptyList(),
    onExportEncryptedBackup: suspend (String) -> String = {
        error("Backup repository unavailable")
    },
    onImportEncryptedBackup: suspend (String, String) -> Unit = { _, _ ->
        error("Backup repository unavailable")
    },
    onDeleteLocalData: () -> Unit = {},
    notificationListenerAccessGranted: Boolean = false,
    onOpenNotificationListenerSettings: () -> Unit = {},
    billSyncAccessibilityAccessGranted: Boolean = false,
    onOpenBillSyncAccessibilitySettings: () -> Unit = {},
    accountSession: AccountSession? = null,
    accountDeletionState: AccountDeletionUiState = AccountDeletionUiState(),
    onAccountDeletionStateChange: (AccountDeletionUiState) -> Unit = {},
    continuousMonitoringState: ContinuousMonitoringState = ContinuousMonitoringState(),
    continuousMonitoringPermissionHealth: ContinuousMonitoringPermissionHealth =
        ContinuousMonitoringPermissionHealth(),
    onContinuousMonitoringStateChange: (ContinuousMonitoringState) -> Unit = {},
    snackbarHostState: SnackbarHostState = SnackbarHostState(),
    onSaveBackupToDownloads: (String) -> String = { "" },
    onPickBackupFile: ((Uri) -> Unit) -> Unit = {},
    onReadBackupFile: (Uri) -> String = { "" }
) {
    var editingRule by remember { mutableStateOf<CategorizationRule?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var currentAiSettings by remember(aiSettings) { mutableStateOf(aiSettings) }
    var localDataMessage by remember { mutableStateOf<String?>(null) }
    var exportedBackup by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showComplianceMaterials by remember { mutableStateOf(false) }
    var showInternalBetaReadiness by remember { mutableStateOf(false) }
    var currentAccountDeletionState by remember(accountDeletionState) { mutableStateOf(accountDeletionState) }
    var currentContinuousMonitoringState by remember(continuousMonitoringState) {
        mutableStateOf(continuousMonitoringState)
    }

    fun updateAiSettings(next: AiCategorizationSettings) {
        currentAiSettings = next
        onAiSettingsChange(next)
    }

    fun updateAccountDeletionState(next: AccountDeletionUiState) {
        currentAccountDeletionState = next
        onAccountDeletionStateChange(next)
    }

    fun updateContinuousMonitoringState(next: ContinuousMonitoringState) {
        currentContinuousMonitoringState = next
        onContinuousMonitoringStateChange(next)
    }

    if (showComplianceMaterials) {
        ComplianceMaterialsScreen(
            modifier = modifier,
            onBack = { showComplianceMaterials = false }
        )
        return
    }

    if (showInternalBetaReadiness) {
        InternalBetaReadinessScreen(
            modifier = modifier,
            onBack = { showInternalBetaReadiness = false }
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (showPermissionCenter) {
            AccountDeletionItem(
                session = accountSession,
                state = currentAccountDeletionState,
                onStateChange = ::updateAccountDeletionState
            )
            ComplianceMaterialsItem(
                onOpen = { showComplianceMaterials = true }
            )
            InternalBetaReadinessItem(
                onOpen = { showInternalBetaReadiness = true }
            )
            LocalDataToolsItem(
                ledgerEntries = ledgerEntries,
                message = localDataMessage,
                onMessageChange = { localDataMessage = it },
                onExportEncryptedBackup = onExportEncryptedBackup,
                onImportEncryptedBackup = onImportEncryptedBackup,
                onRequestDelete = { showDeleteDialog = true },
                snackbarHostState = snackbarHostState,
                onSaveBackupToDownloads = onSaveBackupToDownloads,
                onPickBackupFile = onPickBackupFile,
                onReadBackupFile = onReadBackupFile
            )
            PermissionCenterNotificationItem(
                accessGranted = notificationListenerAccessGranted,
                onOpenSettings = onOpenNotificationListenerSettings
            )
            PermissionCenterBillSyncItem(
                accessGranted = billSyncAccessibilityAccessGranted,
                onOpenSettings = onOpenBillSyncAccessibilitySettings
            )
            ContinuousMonitoringItem(
                state = currentContinuousMonitoringState,
                permissionHealth = continuousMonitoringPermissionHealth,
                onOpenNotificationSettings = onOpenNotificationListenerSettings,
                onOpenBillSyncAccessibilitySettings = onOpenBillSyncAccessibilitySettings,
                onStateChange = ::updateContinuousMonitoringState
            )
            AiConsentItem(
                settings = currentAiSettings,
                cloudWritesPaused = currentAccountDeletionState.isPending,
                onSettingsChange = ::updateAiSettings
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "分类规则",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "按商户、标题、来源和交易类型自动建议分类",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(onClick = {
                editingRule = null
                isCreating = true
            }) {
                Text("新建规则")
            }
        }

        if (rules.isEmpty()) {
            Text("还没有分类规则")
        } else {
            rules
                .sortedWith(compareByDescending<CategorizationRule> { it.priority }.thenByDescending { it.updatedAtEpochMillis })
                .forEach { rule ->
                    CategorizationRuleRow(
                        rule = rule,
                        onEdit = {
                            editingRule = rule
                            isCreating = false
                        },
                        onDelete = {
                            onRulesChange(rules.filterNot { it.id == rule.id })
                        }
                    )
                }
        }
    }

    if (isCreating || editingRule != null) {
        CategorizationRuleDialog(
            initialRule = editingRule,
            onDismiss = {
                isCreating = false
                editingRule = null
            },
            onSave = { savedRule ->
                val nextRules = if (editingRule == null) {
                    rules + savedRule.copy(
                        id = "rule-${rules.size + 1}",
                        updatedAtEpochMillis = rules.nextUpdatedAt()
                    )
                } else {
                    rules.map { existing ->
                        if (existing.id == savedRule.id) {
                            savedRule.copy(updatedAtEpochMillis = rules.nextUpdatedAt())
                        } else {
                            existing
                        }
                    }
                }
                onRulesChange(nextRules)
                isCreating = false
                editingRule = null
            }
        )
    }

    if (showDeleteDialog) {
        LocalDataDeletionDialog(
            onDismiss = { showDeleteDialog = false },
            onDelete = {
                onDeleteLocalData()
                localDataMessage = "本机数据已删除"
                showDeleteDialog = false
            }
        )
    }
}

@Composable
private fun PermissionCenterNotificationItem(
    accessGranted: Boolean,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("权限中心", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("通知监听", fontWeight = FontWeight.SemiBold)
            Text(
                AUTO_ACCOUNTING_COMPLIANCE.permissionPurpose(PermissionExplanationId.NotificationListening),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                if (accessGranted) "当前状态：已授权" else "当前状态：未授权",
                style = MaterialTheme.typography.bodySmall,
                color = if (accessGranted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("notification-listener-settings")
            ) {
                Text("打开系统设置")
            }
        }
    }
}

@Composable
private fun PermissionCenterBillSyncItem(
    accessGranted: Boolean,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("账单同步与监控权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                AUTO_ACCOUNTING_COMPLIANCE.permissionPurpose(PermissionExplanationId.AccessibilityBillSync),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                if (accessGranted) "当前状态：已授权" else "当前状态：未授权",
                style = MaterialTheme.typography.bodySmall,
                color = if (accessGranted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("bill-sync-accessibility-settings")
            ) {
                Text("打开无障碍设置")
            }
        }
    }
}

@Composable
private fun ComplianceMaterialsItem(
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("关于与合规", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "查看隐私政策、个人信息收集清单、第三方服务清单、权限说明和商店审核说明。",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(onClick = onOpen) {
                Text("隐私与合规材料")
            }
        }
    }
}

@Composable
private fun InternalBetaReadinessItem(
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("内测准备", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "查看崩溃/日志、设备矩阵、权限留存、捕获准确率、去重准确率、复核效率和合规复核。",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(onClick = onOpen) {
                Text("查看内测检查")
            }
        }
    }
}

@Composable
private fun ContinuousMonitoringItem(
    state: ContinuousMonitoringState,
    permissionHealth: ContinuousMonitoringPermissionHealth,
    onOpenNotificationSettings: () -> Unit,
    onOpenBillSyncAccessibilitySettings: () -> Unit,
    onStateChange: (ContinuousMonitoringState) -> Unit
) {
    val startBlockReason = when {
        !state.billSyncCompleted -> ContinuousMonitoringBlockReason.RequiresBillSyncFirst
        else -> permissionHealth.firstBlockReason
    }
    val canStartMonitoring = startBlockReason == null
    val statusText = when {
        state.enabled && permissionHealth.isHealthy -> "当前状态：已开启"
        state.enabled -> "当前状态：权限不完整，监控已暂停"
        startBlockReason == null -> "当前状态：可开启"
        else -> "当前状态：${continuousMonitoringBlockReasonLabel(startBlockReason)}"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("高级监控", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("连续监控", fontWeight = FontWeight.SemiBold)
            Text(
                AUTO_ACCOUNTING_COMPLIANCE.permissionPurpose(PermissionExplanationId.ContinuousMonitoring),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "只处理账单/支付记录，不处理聊天、消息、付款发起或转账。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "后台保活和自启动受手机系统限制，本应用只提示你检查，不保证一定可靠。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.enabled) {
                    OutlinedButton(
                        onClick = {
                            onStateChange(
                                reduceContinuousMonitoringState(
                                    state,
                                    ContinuousMonitoringAction.Disable
                                )
                            )
                        }
                    ) {
                        Text("关闭连续监控")
                    }
                } else {
                    Button(
                        onClick = {
                            onStateChange(
                                reduceContinuousMonitoringState(
                                    state,
                                    ContinuousMonitoringAction.Enable(permissionHealth)
                                )
                            )
                        },
                        enabled = canStartMonitoring
                    ) {
                        Text("开启连续监控")
                    }
                }
            }
            if (!permissionHealth.notificationListenerGranted) {
                OutlinedButton(
                    onClick = onOpenNotificationSettings,
                    modifier = Modifier.testTag("continuous-monitoring-notification-settings")
                ) {
                    Text("打开通知监听设置")
                }
            }
            if (!permissionHealth.billSyncAccessibilityGranted) {
                OutlinedButton(
                    onClick = onOpenBillSyncAccessibilitySettings,
                    modifier = Modifier.testTag("continuous-monitoring-accessibility-settings")
                ) {
                    Text("打开无障碍设置")
                }
            }
        }
    }
}

private fun continuousMonitoringBlockReasonLabel(
    reason: ContinuousMonitoringBlockReason
): String = when (reason) {
    ContinuousMonitoringBlockReason.RequiresBillSyncFirst -> "请先完成一次手动账单同步"
    ContinuousMonitoringBlockReason.RequiresNotificationListenerPermission -> "需要开启通知监听"
    ContinuousMonitoringBlockReason.RequiresBillSyncAccessibilityPermission -> "需要开启账单同步无障碍权限"
}

@Composable
private fun AccountDeletionItem(
    session: AccountSession?,
    state: AccountDeletionUiState,
    onStateChange: (AccountDeletionUiState) -> Unit
) {
    val signedIn = session as? AccountSession.SignedIn
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("账号注销", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (signedIn == null) {
                Text("本地模式没有云端账号可注销。", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text(
                if (state.isPending) {
                    "注销冷静期中"
                } else {
                    "注销会移除云端账号、注册设备、云端配置和 AI 分类日志；本机账本需单独删除。"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (state.isPending) {
                Text(
                    "云端 AI 和设备配置写入已暂停",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(
                    onClick = {
                        onStateChange(
                            reduceAccountDeletionState(
                                state,
                                AccountDeletionUiAction.CancelDeletion
                            )
                        )
                    }
                ) {
                    Text("取消注销")
                }
            } else {
                Button(
                    onClick = {
                        onStateChange(
                            reduceAccountDeletionState(
                                state,
                                AccountDeletionUiAction.RequestDeletion(System.currentTimeMillis())
                            )
                        )
                    }
                ) {
                    Text("申请注销账号")
                }
            }
        }
    }
}

@Composable
private fun AiConsentItem(
    settings: AiCategorizationSettings,
    cloudWritesPaused: Boolean,
    onSettingsChange: (AiCategorizationSettings) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("云端 AI 分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                AUTO_ACCOUNTING_COMPLIANCE.permissionPurpose(PermissionExplanationId.CloudAi),
                style = MaterialTheme.typography.bodyMedium
            )
            if (cloudWritesPaused) {
                Text(
                    "账号注销冷静期内，云端 AI 和设备配置写入已暂停。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSettingsChange(
                            settings.copy(aiConsentGranted = !settings.aiConsentGranted)
                        )
                    },
                    enabled = !cloudWritesPaused
                ) {
                    Text(if (settings.aiConsentGranted) "关闭云端 AI" else "开启云端 AI")
                }
                OutlinedButton(
                    onClick = {
                        onSettingsChange(
                            settings.copy(enhancedContextGranted = !settings.enhancedContextGranted)
                        )
                    },
                    enabled = !cloudWritesPaused
                ) {
                    Text(if (settings.enhancedContextGranted) "减少上下文" else "提供更多上下文")
                }
            }
        }
    }
}

@Composable
private fun LocalDataToolsItem(
    ledgerEntries: List<LedgerUiEntry>,
    message: String?,
    onMessageChange: (String) -> Unit,
    onExportEncryptedBackup: suspend (String) -> String,
    onImportEncryptedBackup: suspend (String, String) -> Unit,
    onRequestDelete: () -> Unit,
    snackbarHostState: SnackbarHostState,
    onSaveBackupToDownloads: (String) -> String,
    onPickBackupFile: ((Uri) -> Unit) -> Unit,
    onReadBackupFile: (Uri) -> String
) {
    val coroutineScope = rememberCoroutineScope()
    var backupPassphrase by remember { mutableStateOf("") }
    var isExporting by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("备份和导出", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "CSV 是明文表格；完整迁移请使用加密备份（保存到 Download 文件夹）。",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = backupPassphrase,
                onValueChange = { backupPassphrase = it },
                label = { Text("备份密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backup-passphrase")
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    exportLedgerCsv(ledgerEntries)
                    onMessageChange("CSV 已生成")
                }) {
                    Text(if (message == "CSV 已生成") "CSV 已生成" else "导出 CSV")
                }
                OutlinedButton(
                    onClick = {
                        isExporting = true
                        coroutineScope.launch {
                            runCatching {
                                val backupContent = onExportEncryptedBackup(backupPassphrase)
                                onSaveBackupToDownloads(backupContent)
                            }
                                .onSuccess { filename ->
                                    snackbarHostState.showSnackbar(
                                        "备份已保存到 Download/$filename"
                                    )
                                }
                                .onFailure {
                                    snackbarHostState.showSnackbar("加密备份失败")
                                }
                            isExporting = false
                        }
                    },
                    enabled = backupPassphrase.isNotBlank() && !isExporting
                ) {
                    Text("导出加密备份到文件")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onPickBackupFile { uri ->
                            coroutineScope.launch {
                                runCatching {
                                    val backupContent = onReadBackupFile(uri)
                                    onImportEncryptedBackup(backupContent, backupPassphrase)
                                }
                                    .onSuccess {
                                        snackbarHostState.showSnackbar("备份已恢复成功")
                                    }
                                    .onFailure {
                                        snackbarHostState.showSnackbar("备份恢复失败：密码错误或文件损坏")
                                    }
                            }
                        }
                    },
                    enabled = backupPassphrase.isNotBlank()
                ) {
                    Text("从文件导入备份")
                }
                OutlinedButton(onClick = onRequestDelete) {
                    Text("删除本机数据")
                }
            }
        }
    }
}

@Composable
private fun LocalDataDeletionDialog(
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    var deletionState by remember { mutableStateOf(LocalDataDeletionState()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除本机数据") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("确认删除前请先导出加密备份。")
                OutlinedButton(
                    onClick = {
                        deletionState = reduceLocalDataDeletionState(
                            deletionState,
                            LocalDataDeletionAction.SetBackupReminderAccepted(
                                !deletionState.backupReminderAccepted
                            )
                        )
                    }
                ) {
                    Text("我已了解并完成需要的备份")
                }
                OutlinedTextField(
                    value = deletionState.confirmationText,
                    onValueChange = {
                        deletionState = reduceLocalDataDeletionState(
                            deletionState,
                            LocalDataDeletionAction.UpdateConfirmationText(it)
                        )
                    },
                    label = { Text("输入 $DELETE_LOCAL_DATA_PHRASE") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDelete,
                enabled = deletionState.canDelete
            ) {
                Text("确认删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun CategorizationRuleRow(
    rule: CategorizationRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(rule.displayName(), fontWeight = FontWeight.SemiBold)
                Text(rule.category, style = MaterialTheme.typography.bodyMedium)
                Text(rule.scopeLabel(), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete-rule-${rule.id}")
            ) {
                Text("删除")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onEdit) {
                Text("编辑")
            }
        }
    }
}

@Composable
private fun CategorizationRuleDialog(
    initialRule: CategorizationRule?,
    onDismiss: () -> Unit,
    onSave: (CategorizationRule) -> Unit
) {
    var merchantContains by remember(initialRule?.id) { mutableStateOf(initialRule?.merchantContains.orEmpty()) }
    var titleContains by remember(initialRule?.id) { mutableStateOf(initialRule?.titleContains.orEmpty()) }
    var sourceLabel by remember(initialRule?.id) { mutableStateOf(initialRule?.sourceLabel.orEmpty()) }
    var transactionKind by remember(initialRule?.id) { mutableStateOf(initialRule?.transactionKind.orEmpty()) }
    var category by remember(initialRule?.id) { mutableStateOf(initialRule?.category.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialRule == null) "新建规则" else "编辑规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = merchantContains,
                    onValueChange = { merchantContains = it },
                    label = { Text("商户包含") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = titleContains,
                    onValueChange = { titleContains = it },
                    label = { Text("标题关键词") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sourceLabel,
                    onValueChange = { sourceLabel = it },
                    label = { Text("来源") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = transactionKind,
                    onValueChange = { transactionKind = it },
                    label = { Text("交易类型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("分类") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        CategorizationRule(
                            id = initialRule?.id.orEmpty(),
                            merchantContains = merchantContains.trim(),
                            titleContains = titleContains.trim(),
                            sourceLabel = sourceLabel.trim(),
                            transactionKind = transactionKind.trim(),
                            category = category.trim(),
                            priority = initialRule?.priority ?: 0,
                            enabled = initialRule?.enabled ?: true,
                            updatedAtEpochMillis = initialRule?.updatedAtEpochMillis ?: 0
                        )
                    )
                },
                enabled = category.isNotBlank()
            ) {
                Text("保存规则")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun CategorizationRule.displayName(): String = when {
    merchantContains.isNotBlank() -> merchantContains
    titleContains.isNotBlank() -> titleContains
    sourceLabel.isNotBlank() -> sourceLabel
    transactionKind.isNotBlank() -> transactionKind
    else -> "未命名规则"
}

private fun CategorizationRule.scopeLabel(): String {
    val parts = listOf(
        sourceLabel.takeIf { it.isNotBlank() },
        transactionKind.takeIf { it.isNotBlank() },
        titleContains.takeIf { it.isNotBlank() }
    ).filterNotNull()
    return if (parts.isEmpty()) "全部交易" else parts.joinToString(" / ")
}

private fun List<CategorizationRule>.nextUpdatedAt(): Long {
    return (maxOfOrNull { it.updatedAtEpochMillis } ?: 0L) + 1L
}
