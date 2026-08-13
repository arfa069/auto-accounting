package com.autoaccounting.feature.ledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.autoaccounting.ui.components.Button
import com.autoaccounting.ui.components.EmptyStatePanel
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.OutlinedTextField
import com.autoaccounting.ui.components.TextButton
import kotlinx.coroutines.launch
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.PaymentSource

@Composable
internal fun FundingAccountManagementContent(
    fundingAccounts: List<FundingAccountEntity>,
    defaultFundingAccountSyncId: String? = null,
    snackbarHostState: SnackbarHostState,
    actions: FundingAccountManagementActions
) {
    var showEditor by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf<FundingAccountEntity?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<FundingAccountEntity?>(null) }
    var blockedDeleteMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun deleteFundingAccount(account: FundingAccountEntity) {
        scope.launch {
            val result = runCatching { actions.onDeleteFundingAccount(account.id) }
                .getOrElse {
                    pendingDelete = null
                    snackbarHostState.showSnackbar(it.userMessage())
                    return@launch
                }
            pendingDelete = null
            handleFundingAccountDeleteResult(
                result = result,
                account = account,
                snackbarHostState = snackbarHostState,
                onBlocked = { blockedDeleteMessage = it }
            )
        }
    }

    fun openEditor(account: FundingAccountEntity?) {
        editingAccount = account
        editorError = null
        showEditor = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = actions.onBack) { Text("返回账本") }
        Text(
            "资金账户",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Button(
            onClick = { openEditor(null) },
            modifier = Modifier.testTag(LedgerTestTags.ADD_FUNDING_ACCOUNT)
        ) {
            Text("新增资金账户")
        }
        FundingAccountList(
            fundingAccounts = fundingAccounts,
            defaultFundingAccountSyncId = defaultFundingAccountSyncId,
            onSetDefault = { id -> scope.launch { actions.onSetDefaultFundingAccount(id) } },
            onEdit = ::openEditor,
            onRequestDelete = { pendingDelete = it }
        )
    }

    if (showEditor) {
        FundingAccountEditorDialog(
            account = editingAccount,
            errorMessage = editorError,
            onInputChanged = { editorError = null },
            onDismiss = {
                showEditor = false
                editingAccount = null
                editorError = null
            },
            onSave = { enteredLabel, paymentSource ->
                val normalizedLabel = enteredLabel.trim()
                editorError = when {
                    normalizedLabel.isEmpty() -> "请输入资金账户名称"
                    fundingAccounts.any { account ->
                        account.id != editingAccount?.id &&
                            account.paymentSource == paymentSource &&
                            account.label.trim() == normalizedLabel
                    } -> "同一支付来源下已存在同名资金账户"
                    else -> null
                }
                if (editorError == null) {
                    scope.launch {
                        runCatching {
                            val account = editingAccount
                            if (account == null) {
                                actions.onCreateFundingAccount(normalizedLabel, paymentSource)
                            } else {
                                actions.onUpdateFundingAccount(account.id, normalizedLabel, paymentSource)
                            }
                        }
                            .onSuccess {
                                showEditor = false
                                editingAccount = null
                            }
                            .onFailure { editorError = it.userMessage() }
                    }
                }
            }
        )
    }

    pendingDelete?.let { account ->
        FundingAccountDeleteDialog(
            account = account,
            onDismiss = { pendingDelete = null },
            onConfirm = { deleteFundingAccount(account) }
        )
    }
    blockedDeleteMessage?.let { message ->
        FundingAccountBlockedDeleteDialog(
            message = message,
            onDismiss = { blockedDeleteMessage = null }
        )
    }
}

private suspend fun handleFundingAccountDeleteResult(
    result: FundingAccountDeleteResult,
    account: FundingAccountEntity,
    snackbarHostState: SnackbarHostState,
    onBlocked: (String) -> Unit
) {
    when (result) {
        FundingAccountDeleteResult.Deleted -> {
            snackbarHostState.showSnackbar("已删除资金账户「${account.label}」")
        }
        is FundingAccountDeleteResult.Referenced -> onBlocked(result.referenceMessage())
    }
}

@Composable
private fun FundingAccountList(
    fundingAccounts: List<FundingAccountEntity>,
    defaultFundingAccountSyncId: String?,
    onSetDefault: (Long?) -> Unit,
    onEdit: (FundingAccountEntity) -> Unit,
    onRequestDelete: (FundingAccountEntity) -> Unit
) {
    if (fundingAccounts.isEmpty()) {
        EmptyStatePanel("暂无资金账户")
    } else {
        fundingAccounts.forEach { account ->
            FundingAccountCard(
                account = account,
                isDefault = account.syncId == defaultFundingAccountSyncId,
                onSetDefault = { onSetDefault(if (account.syncId == defaultFundingAccountSyncId) null else account.id) },
                onEdit = { onEdit(account) },
                onRequestDelete = { onRequestDelete(account) }
            )
        }
    }
}

@Composable
private fun FundingAccountCard(
    account: FundingAccountEntity,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LedgerTestTags.fundingAccount(account.id)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(account.label, fontWeight = FontWeight.SemiBold)
            if (isDefault) Text("默认", color = MaterialTheme.colorScheme.primary)
            Text("支付来源：${account.paymentSource.labelOrNone()}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSetDefault) { Text(if (isDefault) "取消默认" else "设为默认") }
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag(LedgerTestTags.editFundingAccount(account.id))
                ) {
                    Text("编辑")
                }
                OutlinedButton(
                    onClick = onRequestDelete,
                    modifier = Modifier.testTag(LedgerTestTags.deleteFundingAccount(account.id))
                ) {
                    Text("删除")
                }
            }
        }
    }
}

@Composable
private fun FundingAccountDeleteDialog(
    account: FundingAccountEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除资金账户？") },
        text = { Text("将删除资金账户「${account.label}」。已使用的账户不会被删除。") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(
                    LedgerTestTags.CONFIRM_DELETE_FUNDING_ACCOUNT
                )
            ) {
                Text("确认删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun FundingAccountBlockedDeleteDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("无法删除资金账户") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}


@Composable
private fun FundingAccountEditorDialog(
    account: FundingAccountEntity?,
    errorMessage: String?,
    onInputChanged: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, PaymentSource?) -> Unit
) {
    var label by remember(account?.id) { mutableStateOf(account?.label.orEmpty()) }
    var paymentSource by remember(account?.id) { mutableStateOf(account?.paymentSource) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (account == null) "新增资金账户" else "编辑资金账户") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = {
                        label = it
                        onInputChanged()
                    },
                    label = { Text("账户名称") },
                    supportingText = errorMessage?.let { message -> ({ Text(message) }) },
                    isError = errorMessage != null,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(LedgerTestTags.FUNDING_ACCOUNT_LABEL)
                )
                SelectionMenu(
                    label = "支付来源",
                    selected = paymentSource,
                    options = listOf(null, PaymentSource.WECHAT, PaymentSource.ALIPAY),
                    itemLabel = { it.labelOrNone() },
                    onSelected = {
                        paymentSource = it
                        onInputChanged()
                    },
                    modifier = Modifier.testTag(LedgerTestTags.FUNDING_ACCOUNT_SOURCE)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label, paymentSource) },
                modifier = Modifier.testTag(LedgerTestTags.SAVE_FUNDING_ACCOUNT)
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun FundingAccountDeleteResult.Referenced.referenceMessage(): String =
    "该账户仍被 $activeLedgerEntryCount 笔当前账目、$deletedLedgerEntryCount 笔最近删除账目、" +
        "$pendingEntryCount 条待确认记录和 $ignoredEntryCount 条忽略记录引用。"
