package com.autoaccounting.feature.billsync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoaccounting.feature.monitoring.ContinuousMonitoringAction
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.monitoring.reduceContinuousMonitoringState
import com.autoaccounting.ui.components.Button
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.TextButton
import kotlinx.coroutines.delay

const val MANUAL_BILL_IMPORT_TIMEOUT_MILLIS = 90_000L

@Composable
fun ManualBillImportHost(
    openRequestId: Long,
    accessibilityAccessGranted: Boolean,
    accessibilityServiceConnected: Boolean,
    onOpenAccessibilitySettings: () -> Unit = {},
    onLaunchSource: (BillSyncSource) -> Boolean = { false },
    onNavigateToReview: () -> Unit = {},
    continuousMonitoringState: ContinuousMonitoringState = ContinuousMonitoringState(),
    continuousMonitoringPermissionHealth: ContinuousMonitoringPermissionHealth =
        ContinuousMonitoringPermissionHealth(),
    onContinuousMonitoringStateChange: (ContinuousMonitoringState) -> Unit = {},
    sessionController: BillSyncSessionController = BillSyncSessions.controller,
    waitingTimeoutMillis: Long = MANUAL_BILL_IMPORT_TIMEOUT_MILLIS
) {
    var dialogOpen by remember { mutableStateOf(false) }
    var handledOpenRequestId by remember { mutableLongStateOf(0L) }
    var precheckFailure by remember {
        mutableStateOf<ManualBillImportPrecheckFailure?>(null)
    }
    val sessionState by sessionController.state.collectAsState()

    fun currentPrecheckFailure(): ManualBillImportPrecheckFailure? = when {
        !accessibilityAccessGranted -> ManualBillImportPrecheckFailure.PermissionMissing
        !accessibilityServiceConnected -> ManualBillImportPrecheckFailure.ServiceDisconnected
        else -> null
    }

    fun retry() {
        sessionController.reset()
        precheckFailure = currentPrecheckFailure()
    }

    LaunchedEffect(openRequestId) {
        if (openRequestId <= 0L || openRequestId == handledOpenRequestId) {
            return@LaunchedEffect
        }
        handledOpenRequestId = openRequestId
        retry()
        dialogOpen = true
    }

    LaunchedEffect(sessionState.sessionId, sessionState.phase, waitingTimeoutMillis) {
        if (sessionState.phase != BillSyncSessionPhase.AwaitingBillPage) {
            return@LaunchedEffect
        }
        val waitingSessionId = sessionState.sessionId
        delay(waitingTimeoutMillis)
        sessionController.timeoutAwaitingBillPage(waitingSessionId)
    }

    if (!dialogOpen) return

    AlertDialog(
        modifier = Modifier.testTag("manual-bill-import-host"),
        onDismissRequest = {
            if (sessionState.isActive) {
                sessionController.cancel()
            } else {
                dialogOpen = false
            }
        },
        title = {
            Text(
                when {
                    precheckFailure != null -> precheckFailure!!.title
                    sessionState.phase == BillSyncSessionPhase.Idle -> "选择账单来源"
                    else -> "补录账单"
                }
            )
        },
        text = {
            ManualBillImportDialogContent(
                precheckFailure = precheckFailure,
                sessionState = sessionState,
                onSourceSelected = { source ->
                    val failure = currentPrecheckFailure()
                    if (failure != null) {
                        precheckFailure = failure
                    } else {
                        startManualBillSync(
                            source = source,
                            manualOcrAllowed = true,
                            launchSource = onLaunchSource,
                            controller = sessionController
                        )
                    }
                }
            )
        },
        confirmButton = {
            when {
                precheckFailure != null -> Button(onClick = onOpenAccessibilitySettings) {
                    Text("去设置")
                }

                sessionState.phase == BillSyncSessionPhase.Completed -> Button(
                    onClick = {
                        dialogOpen = false
                        onNavigateToReview()
                    }
                ) {
                    Text("查看待确认")
                }

                sessionState.phase == BillSyncSessionPhase.Failed ||
                    sessionState.phase == BillSyncSessionPhase.Cancelled -> Button(onClick = ::retry) {
                        Text("重试")
                    }

                else -> Unit
            }
        },
        dismissButton = {
            when {
                precheckFailure != null -> Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { precheckFailure = currentPrecheckFailure() }) {
                        Text("重新检查")
                    }
                    TextButton(onClick = { dialogOpen = false }) {
                        Text("关闭")
                    }
                }

                sessionState.isActive -> TextButton(onClick = sessionController::cancel) {
                    Text("取消补录")
                }

                sessionState.phase == BillSyncSessionPhase.Completed -> Column(
                    horizontalAlignment = Alignment.End
                ) {
                    if (!continuousMonitoringState.enabled) {
                        OutlinedButton(
                            onClick = {
                                onContinuousMonitoringStateChange(
                                    reduceContinuousMonitoringState(
                                        continuousMonitoringState,
                                        ContinuousMonitoringAction.Enable(
                                            continuousMonitoringPermissionHealth
                                        )
                                    )
                                )
                            }
                        ) {
                            Text("开启自动记账")
                        }
                    }
                    TextButton(onClick = { dialogOpen = false }) {
                        Text("关闭")
                    }
                }

                else -> TextButton(onClick = { dialogOpen = false }) {
                    Text(if (sessionState.phase == BillSyncSessionPhase.Idle) "取消" else "关闭")
                }
            }
        }
    )
}

@Composable
private fun ManualBillImportDialogContent(
    precheckFailure: ManualBillImportPrecheckFailure?,
    sessionState: BillSyncSessionState,
    onSourceSelected: (BillSyncSource) -> Unit
) {
    if (precheckFailure != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(precheckFailure.message)
            Text(
                "仅在你主动补录时读取当前可见的微信或支付宝账单页。",
                style = MaterialTheme.typography.bodySmall
            )
        }
        return
    }

    when (sessionState.phase) {
        BillSyncSessionPhase.Idle -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("每次仅读取当前可见页面，不会自动滚动、翻页或扫描全部历史。")
            Text("识别结果只会加入待确认，不会直接记入账本。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSourceSelected(BillSyncSource.WeChat) }) {
                    Text("微信")
                }
                Button(onClick = { onSourceSelected(BillSyncSource.Alipay) }) {
                    Text("支付宝")
                }
            }
        }

        BillSyncSessionPhase.AwaitingBillPage -> Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("已打开${sessionState.source?.label.orEmpty()}", fontWeight = FontWeight.SemiBold)
            Text("请在 90 秒内进入账单、交易详情或支付结果页面。")
            if (sessionState.manualOcrAllowed) {
                Text("本次已允许本机 OCR；离开此次补录后自动失效。")
            }
            Text("停留片刻，识别完成后返回本应用。")
            ManualBillImportSteps(sessionState)
        }

        BillSyncSessionPhase.Processing -> Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("正在读取、解析和去重，请稍候。")
            ManualBillImportSteps(sessionState)
        }

        BillSyncSessionPhase.Completed -> Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val result = sessionState.result
            Text("补录完成", fontWeight = FontWeight.SemiBold)
            Text("新增 ${result?.createdEntries?.size ?: 0} 条")
            Text("去重 ${result?.duplicateSkippedCount ?: 0} 条")
            Text("结果已保存到待确认队列。")
        }

        BillSyncSessionPhase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ManualBillImportSteps(sessionState)
            Text(sessionState.message ?: "补录失败")
        }

        BillSyncSessionPhase.Cancelled -> Text(sessionState.message ?: "补录已取消")
    }
}

@Composable
private fun ManualBillImportSteps(sessionState: BillSyncSessionState) {
    sessionState.steps.forEach { step ->
        Text(
            when (step) {
                BillSyncStep.Failed -> "补录失败"
                BillSyncStep.Cancelled -> "补录已取消"
                BillSyncStep.Completed -> "补录完成"
                else -> step.label
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private enum class ManualBillImportPrecheckFailure(
    val title: String,
    val message: String
) {
    PermissionMissing(
        title = "需要无障碍权限",
        message = "补录账单需要无障碍权限来读取你主动打开的账单页面。"
    ),
    ServiceDisconnected(
        title = "无障碍服务未连接",
        message = "权限已经开启，但服务尚未连接。请重新检查，仍未恢复时进入设置重新授权。"
    )
}
