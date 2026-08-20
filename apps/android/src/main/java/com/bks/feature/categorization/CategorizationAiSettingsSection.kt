package com.bks.feature.categorization

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun CategorizationAiSettingsSection(
    uiState: CategorizationAiUiState,
    onSettingsChange: (AiCategorizationSettings) -> Unit
) {
    if (uiState.signedIn) {
        AiConsentItem(
            settings = uiState.settings,
            cloudWritesPaused = uiState.cloudWritesPaused,
            settingsSyncInFlight = uiState.settingsSyncInFlight,
            onSettingsChange = onSettingsChange
        )
    } else {
        SignedOutAiItem()
    }
}

@Composable
private fun AiConsentItem(
    settings: AiCategorizationSettings,
    cloudWritesPaused: Boolean,
    settingsSyncInFlight: Boolean,
    onSettingsChange: (AiCategorizationSettings) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AiSettingsHeader(
                checked = settings.aiConsentGranted,
                enabled = !cloudWritesPaused && !settingsSyncInFlight,
                onCheckedChange = { enabled ->
                    onSettingsChange(
                        reduceAiCategorizationSettings(
                            settings,
                            if (enabled) {
                                AiCategorizationSettingsAction.EnableAi
                            } else {
                                AiCategorizationSettingsAction.DisableAi
                            }
                        )
                    )
                }
            )
            Text(
                text = "需要时帮你判断分类，结果会先留在待确认里，由你决定是否采用。",
                style = MaterialTheme.typography.bodyMedium
            )
            if (cloudWritesPaused) {
                Text(
                    "账号注销冷静期内，智能分类暂时不可用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (settingsSyncInFlight) {
                Text(
                    "正在同步云端 AI 设置…",
                    modifier = Modifier.testTag("ai-settings-syncing"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("更多判断依据", fontWeight = FontWeight.SemiBold)
                        Text(
                            "允许使用备注和识别到的交易文字",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.enhancedContextGranted,
                        onCheckedChange = { enabled ->
                            onSettingsChange(
                                reduceAiCategorizationSettings(
                                    settings,
                                    AiCategorizationSettingsAction.SetEnhancedContext(enabled)
                                )
                            )
                        },
                        modifier = Modifier.testTag("enhanced-context-switch"),
                        enabled = !cloudWritesPaused &&
                            !settingsSyncInFlight &&
                            settings.aiConsentGranted
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = "🔒 关闭时只使用基础交易信息",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AiSettingsHeader(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primary
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("✦", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(
            text = "智能分类",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            shape = RoundedCornerShape(50)
        ) {
            Text(
                text = "试用",
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("ai-consent-switch"),
            enabled = enabled
        )
    }
}

@Composable
private fun SignedOutAiItem() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("✦", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                Text("智能分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        "登录后可用",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                "登录后可以使用智能分类；本地规则照常生效。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
