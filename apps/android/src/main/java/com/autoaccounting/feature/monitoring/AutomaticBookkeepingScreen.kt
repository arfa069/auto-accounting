package com.autoaccounting.feature.monitoring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.autoaccounting.ui.components.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.autoaccounting.ui.components.OutlinedButton
import androidx.compose.material3.Text
import com.autoaccounting.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AutomaticBookkeepingScreen(
    notificationListenerAccessGranted: Boolean = false,
    onOpenNotificationListenerSettings: () -> Unit = {},
    billSyncAccessibilityAccessGranted: Boolean = false,
    onOpenBillSyncAccessibilitySettings: () -> Unit = {},
    resultNotificationPermissionGranted: Boolean = false,
    onRequestResultNotificationPermission: () -> Unit = {},
    backgroundReliabilityState: BackgroundReliabilityState = BackgroundReliabilityState(),
    onOpenBackgroundRunningSettings: () -> Unit = {},
    onOpenAutoStartSettings: () -> Unit = {},
    onOpenBatteryOptimizationSettings: () -> Unit = {},
    onOpenBatterySaverSettings: () -> Unit = {},
    continuousMonitoringState: ContinuousMonitoringState = ContinuousMonitoringState(),
    continuousMonitoringPermissionHealth: ContinuousMonitoringPermissionHealth =
        ContinuousMonitoringPermissionHealth(),
    onContinuousMonitoringStateChange: (ContinuousMonitoringState) -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val status = remember(
        continuousMonitoringState,
        notificationListenerAccessGranted,
        continuousMonitoringPermissionHealth
    ) {
        summarizeAutomaticBookkeeping(
            state = continuousMonitoringState,
            notificationListenerAccessGranted = notificationListenerAccessGranted,
            permissionHealth = continuousMonitoringPermissionHealth
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("返回") }
        Text("自动记账", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        AutomaticBookkeepingStatusCard(
            state = continuousMonitoringState,
            permissionHealth = continuousMonitoringPermissionHealth,
            status = status,
            onStateChange = { nextState ->
                onContinuousMonitoringStateChange(nextState)
                if (nextState.enabled && !resultNotificationPermissionGranted) {
                    onRequestResultNotificationPermission()
                }
            }
        )
        Text("权限与后台设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column {
                PermissionSettingRow(
                    title = "通知监听（重要）",
                    description = "用于识别微信、支付宝支付通知",
                    status = if (notificationListenerAccessGranted) "已开启" else "去设置",
                    testTag = "notification-listener-settings",
                    onClick = onOpenNotificationListenerSettings
                )
                HorizontalDivider()
                PermissionSettingRow(
                    title = "自动记账无障碍权限（重要）",
                    description = "用于识别支付结果页和支付记录",
                    status = if (billSyncAccessibilityAccessGranted) "已开启" else "去设置",
                    testTag = "bill-sync-accessibility-settings",
                    onClick = onOpenBillSyncAccessibilitySettings
                )
                HorizontalDivider()
                PermissionSettingRow(
                    title = "允许后台运行（建议）",
                    description = "避免系统关闭后台导致自动记账失效",
                    status = "请检查",
                    testTag = "background-running-settings",
                    onClick = onOpenBackgroundRunningSettings
                )
                HorizontalDivider()
                PermissionSettingRow(
                    title = "允许应用自启动（建议）",
                    description = "允许手机重启后恢复自动记账服务\n${backgroundReliabilityState.manufacturer.autoStartGuidance}",
                    status = "请检查",
                    testTag = "auto-start-settings",
                    onClick = onOpenAutoStartSettings
                )
                HorizontalDivider()
                PermissionSettingRow(
                    title = "忽略电池优化（建议）",
                    description = "避免系统休眠导致自动记账中断",
                    status = if (backgroundReliabilityState.batteryOptimizationIgnored) "已开启" else "去设置",
                    testTag = "battery-optimization-settings",
                    onClick = onOpenBatteryOptimizationSettings
                )
                HorizontalDivider()
                PermissionSettingRow(
                    title = "关闭省电模式（建议）",
                    description = "避免省电策略限制后台自动记账",
                    status = if (backgroundReliabilityState.powerSaveModeEnabled) "去设置" else "已关闭",
                    testTag = "battery-saver-settings",
                    onClick = onOpenBatterySaverSettings
                )
            }
        }
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
        Text("开启后自动识别受支持的支付通知和支付结果页", style = MaterialTheme.typography.bodyMedium)
        Text(status.label(), style = MaterialTheme.typography.bodySmall)
        if (status is AutomaticBookkeepingStatus.RequiresAttention) {
            Text(status.reason.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.enabled) {
            OutlinedButton(onClick = {
                onStateChange(reduceContinuousMonitoringState(state, ContinuousMonitoringAction.Disable))
            }) { Text("关闭自动记账") }
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
            ) { Text("开启自动记账") }
        }
    }
}

@Composable
private fun PermissionSettingRow(
    title: String,
    description: String,
    status: String,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Text("›", style = MaterialTheme.typography.titleLarge)
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
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = { content() })
    }
}

private fun AutomaticBookkeepingStatus.label(): String = when (this) {
    AutomaticBookkeepingStatus.Ready -> "状态：已就绪"
    AutomaticBookkeepingStatus.Disabled -> "状态：已关闭"
    is AutomaticBookkeepingStatus.RequiresAttention -> "状态：需要处理"
}

private fun AutomaticBookkeepingAttentionReason.label(): String = when (this) {
    AutomaticBookkeepingAttentionReason.RequiresNotificationListenerAccess -> "请开启通知监听权限"
    AutomaticBookkeepingAttentionReason.RequiresAccessibilityPermission -> "请开启自动记账无障碍权限"
    AutomaticBookkeepingAttentionReason.RequiresAccessibilityServiceConnection -> "自动记账无障碍服务未连接"
}
