package com.autoaccounting.feature.monitoring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoaccounting.feature.billsync.BillSyncSource
import com.autoaccounting.feature.compliance.AUTO_ACCOUNTING_COMPLIANCE
import com.autoaccounting.feature.compliance.PermissionExplanationId
import com.autoaccounting.feature.compliance.permissionPurpose

@Composable
fun AutomaticBookkeepingScreen(
    notificationListenerAccessGranted: Boolean = false,
    onOpenNotificationListenerSettings: () -> Unit = {},
    billSyncAccessibilityAccessGranted: Boolean = false,
    onOpenBillSyncAccessibilitySettings: () -> Unit = {},
    resultNotificationPermissionGranted: Boolean = false,
    onRequestResultNotificationPermission: () -> Unit = {},
    continuousMonitoringState: ContinuousMonitoringState = ContinuousMonitoringState(),
    continuousMonitoringPermissionHealth: ContinuousMonitoringPermissionHealth =
        ContinuousMonitoringPermissionHealth(),
    onContinuousMonitoringStateChange: (ContinuousMonitoringState) -> Unit = {},
    onStartManualBillSync: (BillSyncSource) -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val status = summarizeAutomaticBookkeeping(
        state = continuousMonitoringState,
        notificationListenerAccessGranted = notificationListenerAccessGranted,
        permissionHealth = continuousMonitoringPermissionHealth
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) {
            Text("返回")
        }
        Text(
            text = "自动记账",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        AutomaticBookkeepingStatusCard(
            state = continuousMonitoringState,
            permissionHealth = continuousMonitoringPermissionHealth,
            status = status,
            onStateChange = onContinuousMonitoringStateChange
        )
        Text("必要权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        NotificationListenerPermissionCard(
            accessGranted = notificationListenerAccessGranted,
            onOpenSettings = onOpenNotificationListenerSettings
        )
        AccessibilityPermissionCard(
            accessGranted = billSyncAccessibilityAccessGranted,
            onOpenSettings = onOpenBillSyncAccessibilitySettings
        )
        ResultNotificationPermissionCard(
            accessGranted = resultNotificationPermissionGranted,
            onRequestPermission = onRequestResultNotificationPermission
        )
        Text("持续监控和健康状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        MonitoringHealthCard(
            permissionHealth = continuousMonitoringPermissionHealth,
            state = continuousMonitoringState
        )
        Text("手动账单同步", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ManualBillSyncCard(
            accessibilityGranted = billSyncAccessibilityAccessGranted,
            onOpenAccessibilitySettings = onOpenBillSyncAccessibilitySettings,
            onStart = onStartManualBillSync
        )
    }
}

@Composable
private fun AutomaticBookkeepingStatusCard(
    state: ContinuousMonitoringState,
    permissionHealth: ContinuousMonitoringPermissionHealth,
    status: AutomaticBookkeepingStatus,
    onStateChange: (ContinuousMonitoringState) -> Unit
) {
    SettingsCard {
        Text("自动记账状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(status.label(), style = MaterialTheme.typography.bodyMedium)
        if (status is AutomaticBookkeepingStatus.RequiresAttention) {
            Text(
                text = "需要处理：${status.reason.label()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
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
                Text("关闭自动记账")
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
                enabled = permissionHealth.isHealthy
            ) {
                Text("开启自动记账")
            }
        }
    }
}

@Composable
private fun NotificationListenerPermissionCard(
    accessGranted: Boolean,
    onOpenSettings: () -> Unit
) {
    SettingsCard {
        Text("通知监听", fontWeight = FontWeight.SemiBold)
        Text(
            AUTO_ACCOUNTING_COMPLIANCE.permissionPurpose(PermissionExplanationId.NotificationListening),
            style = MaterialTheme.typography.bodyMedium
        )
        PermissionStatus(accessGranted)
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.testTag("notification-listener-settings")
        ) {
            Text("打开系统设置")
        }
    }
}

@Composable
private fun AccessibilityPermissionCard(
    accessGranted: Boolean,
    onOpenSettings: () -> Unit
) {
    SettingsCard {
        Text("自动记账无障碍权限", fontWeight = FontWeight.SemiBold)
        Text(
            AUTO_ACCOUNTING_COMPLIANCE.permissionPurpose(PermissionExplanationId.AccessibilityBillSync),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "只处理支付结果和支付记录，不处理聊天、普通消息、付款发起或转账发送。",
            style = MaterialTheme.typography.bodySmall
        )
        PermissionStatus(accessGranted)
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.testTag("bill-sync-accessibility-settings")
        ) {
            Text("打开无障碍设置")
        }
    }
}

@Composable
private fun ResultNotificationPermissionCard(
    accessGranted: Boolean,
    onRequestPermission: () -> Unit
) {
    SettingsCard {
        Text("记账结果通知", fontWeight = FontWeight.SemiBold)
        Text(
            AUTO_ACCOUNTING_COMPLIANCE.permissionPurpose(PermissionExplanationId.ResultNotifications),
            style = MaterialTheme.typography.bodyMedium
        )
        PermissionStatus(accessGranted)
        if (!accessGranted) {
            Text("未授权不会影响本地采集和待确认入队", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = onRequestPermission,
                modifier = Modifier.testTag("result-notification-permission")
            ) {
                Text("授权结果通知")
            }
        }
    }
}

@Composable
private fun MonitoringHealthCard(
    permissionHealth: ContinuousMonitoringPermissionHealth,
    state: ContinuousMonitoringState
) {
    SettingsCard {
        Text(
            if (state.enabled && permissionHealth.isHealthy) "持续监控健康" else "持续监控需要处理",
            fontWeight = FontWeight.SemiBold
        )
        Text(
            AUTO_ACCOUNTING_COMPLIANCE.permissionPurpose(PermissionExplanationId.ContinuousMonitoring),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "后台保活和自启动受手机系统限制，本应用只提示你检查，不保证一定可靠。",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ManualBillSyncCard(
    accessibilityGranted: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onStart: (BillSyncSource) -> Unit
) {
    SettingsCard {
        Text("补录遗漏交易", fontWeight = FontWeight.SemiBold)
        Text(
            "只在你主动发起时读取支付来源的账单页，用于补录；不是常驻权限。",
            style = MaterialTheme.typography.bodyMedium
        )
        if (accessibilityGranted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onStart(BillSyncSource.WeChat) },
                    modifier = Modifier.testTag("manual-bill-sync-WeChat")
                ) {
                    Text("同步微信账单")
                }
                Button(
                    onClick = { onStart(BillSyncSource.Alipay) },
                    modifier = Modifier.testTag("manual-bill-sync-Alipay")
                ) {
                    Text("同步支付宝账单")
                }
            }
        } else {
            Text("请先开启自动记账无障碍权限", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onOpenAccessibilitySettings) {
                Text("打开无障碍设置")
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = { content() }
        )
    }
}

@Composable
private fun PermissionStatus(accessGranted: Boolean) {
    Text(
        if (accessGranted) "当前状态：已授权" else "当前状态：未授权",
        style = MaterialTheme.typography.bodySmall,
        color = if (accessGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
}

private fun AutomaticBookkeepingStatus.label(): String = when (this) {
    AutomaticBookkeepingStatus.Ready -> "状态：已就绪"
    AutomaticBookkeepingStatus.Disabled -> "状态：已关闭"
    is AutomaticBookkeepingStatus.RequiresAttention -> "状态：需要处理"
}

private fun AutomaticBookkeepingAttentionReason.label(): String = when (this) {
    AutomaticBookkeepingAttentionReason.RequiresNotificationListenerAccess -> "需要开启通知监听权限"
    AutomaticBookkeepingAttentionReason.RequiresAccessibilityPermission -> "需要开启自动记账无障碍权限"
    AutomaticBookkeepingAttentionReason.RequiresAccessibilityServiceConnection ->
        "自动记账无障碍服务未连接，请重新打开授权"
}
