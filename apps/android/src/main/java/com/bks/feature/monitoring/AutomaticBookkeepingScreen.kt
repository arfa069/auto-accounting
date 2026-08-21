package com.bks.feature.monitoring

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bks.ui.components.Button
import com.bks.ui.components.TextButton

@Composable
@Suppress("LongParameterList")
fun AutomaticBookkeepingScreen(
    enabled: Boolean = false,
    accessibilityAccessGranted: Boolean = false,
    accessibilityServiceConnected: Boolean = false,
    onEnabledChange: (Boolean) -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val status = when {
        !enabled -> "已关闭"
        !accessibilityAccessGranted -> "等待无障碍授权"
        !accessibilityServiceConnected -> "等待服务连接"
        else -> "正在监听"
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("automatic-bookkeeping-page"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("返回") }
        Text(
            text = "自动记账",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("自动识别", style = MaterialTheme.typography.titleMedium)
                        Text(status, modifier = Modifier.testTag("automatic-bookkeeping-status"))
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = onEnabledChange,
                        modifier = Modifier.testTag("automatic-bookkeeping-toggle")
                    )
                }
                Button(onClick = onOpenAccessibilitySettings) {
                    Text(if (accessibilityAccessGranted) "管理无障碍授权" else "打开无障碍设置")
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("隐私说明", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("开启后仅在设备本地读取当前活动窗口中可见、非输入框的文字。")
                Text("不会截图、不会操作其他应用、不会保存或上传原始页面文字。")
                Text("识别结果只进入待确认，必须由你确认后才会入账。")
            }
        }
    }
}
