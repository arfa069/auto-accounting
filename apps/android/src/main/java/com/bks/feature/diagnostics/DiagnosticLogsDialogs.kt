package com.bks.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bks.ui.components.Button
import com.bks.ui.components.TextButton

@Composable
internal fun EnableDiagnosticsConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开启敏感诊断日志？") },
        text = {
            Text(
                "将记录支付通知、支付页与 OCR 文字、金额、商户、备注、支付账号/方式、订单号、交易证据和完整异常。" +
                    "日志会长期保留，最多占用 10 MB，超过上限才删除最旧分段；设备内加密且不会上传。" +
                    "你可以随时关闭或清空。导出文件使用口令加密，但分享后仍有泄露风险。截图本身不会保存，认证秘密始终脱敏。"
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("理解并开启") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun RevealSensitiveConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("显示敏感交易内容？") },
        text = { Text("仅本次页面会话显示；离开页面或应用进入后台后会立即重新遮罩，并禁止系统截图。") },
        confirmButton = {
            Button(onClick = onConfirm) { Text("显示") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun ClearDiagnosticsConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认清空诊断日志？") },
        text = { Text("将删除全部日志分段和设备密钥，无法恢复；记录开关状态保持不变。") },
        confirmButton = {
            Button(onClick = onConfirm) { Text("确认清空") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun DiagnosticExportDialog(
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
internal fun ExportResultDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, modifier = Modifier.testTag("diagnostic-export-result-message")) },
        confirmButton = {
            Button(onClick = onDismiss) { Text("确定") }
        }
    )
}
