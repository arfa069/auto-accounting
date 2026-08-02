package com.autoaccounting

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.sync.LedgerSyncOperationResult
import com.autoaccounting.feature.sync.LedgerSyncScheduler
import com.autoaccounting.ui.components.Button
import com.autoaccounting.ui.components.TextButton
import kotlinx.coroutines.launch

@Composable
internal fun AutoAccountingLedgerSyncAccountSwitchDialog(context: AutoAccountingRouteContext) {
    val runtime = context.runtime
    if (!runtime.showLedgerSyncAccountSwitch) return
    val signedIn = runtime.accountSession as? AccountSession.SignedIn
    AlertDialog(
        onDismissRequest = {},
        title = { Text("切换账户同步数据") },
        text = {
            Text(
                if (runtime.ledgerSyncUiState.pendingCount > 0) {
                    "原账户仍有 ${runtime.ledgerSyncUiState.pendingCount} 项待上传。为避免丢失，请先恢复原账户完成同步或导出加密备份。"
                } else {
                    "确认后，本机正式账本将切换为当前账户的云端数据。待确认记录和设备设置会保留，原账户数据仍保存在其云端。"
                }
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (signedIn != null) {
                        runtime.ledgerSyncAccountSwitchBusy = true
                        context.coroutineScope.launch {
                            when (
                                val result = context.dependencies.sync.coordinator.switchAccount(signedIn.token)
                            ) {
                                is LedgerSyncOperationResult.Success -> {
                                    runtime.showLedgerSyncAccountSwitch = false
                                    LedgerSyncScheduler.ensurePeriodic(context.dependencies.context)
                                    context.appState.snackbarHostState.showSnackbar("账户同步数据已切换")
                                }
                                is LedgerSyncOperationResult.Failure ->
                                    context.appState.snackbarHostState.showSnackbar(result.message)
                            }
                            runtime.ledgerSyncAccountSwitchBusy = false
                        }
                    }
                },
                enabled = signedIn != null &&
                    runtime.ledgerSyncUiState.pendingCount == 0 &&
                    !runtime.ledgerSyncAccountSwitchBusy
            ) {
                Text(if (runtime.ledgerSyncAccountSwitchBusy) "切换中" else "确认切换")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (signedIn != null) {
                        context.coroutineScope.launch {
                            context.dependencies.account.accountRepository.signOut(signedIn.token)
                        }
                    }
                    runtime.showLedgerSyncAccountSwitch = false
                    context.actions.moveAccountToLocalMode()
                },
                enabled = !runtime.ledgerSyncAccountSwitchBusy
            ) {
                Text("取消并退出当前账户")
            }
        }
    )
}
