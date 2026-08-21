package com.bks.feature.ledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bks.ui.components.Button
import com.bks.ui.components.EmptyStatePanel
import com.bks.ui.components.OutlinedTextField
import com.bks.ui.components.TextButton
import kotlinx.coroutines.launch
import com.bks.data.local.FundingAccountEntity
import com.bks.data.local.PaymentSource

@Composable
internal fun FundingAccountManagementContent(
    fundingAccounts: List<FundingAccountEntity>,
    defaultFundingAccountSyncId: String? = null,
    snackbarHostState: SnackbarHostState,
    actions: FundingAccountManagementActions
) {
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editingAccount = remember(fundingAccounts, editingAccountId) {
        fundingAccounts.firstOrNull { it.id == editingAccountId }
    }
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
        editingAccountId = account?.id
        editorError = null
        showEditor = true
    }

    FundingAccountGrid(
        fundingAccounts = fundingAccounts,
        defaultFundingAccountSyncId = defaultFundingAccountSyncId,
        actions = actions,
        onEdit = ::openEditor,
        onRequestDelete = { pendingDelete = it }
    )

    if (showEditor) {
        FundingAccountEditorDialog(
            account = editingAccount,
            errorMessage = editorError,
            onInputChanged = { editorError = null },
            onDismiss = {
                showEditor = false
                editingAccountId = null
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
                                editingAccountId = null
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

@Composable
private fun FundingAccountGrid(
    fundingAccounts: List<FundingAccountEntity>,
    defaultFundingAccountSyncId: String?,
    actions: FundingAccountManagementActions,
    onEdit: (FundingAccountEntity?) -> Unit,
    onRequestDelete: (FundingAccountEntity) -> Unit
) {
    val scope = rememberCoroutineScope()
    val defaultAccount = remember(fundingAccounts, defaultFundingAccountSyncId) {
        fundingAccounts.firstOrNull { it.syncId == defaultFundingAccountSyncId }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(320.dp),
        modifier = Modifier
            .fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            FundingAccountHeader(
                accountCount = fundingAccounts.size,
                onBack = actions.onBack,
                onAdd = { onEdit(null) }
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            FundingAccountOverview(
                accountCount = fundingAccounts.size,
                defaultAccount = defaultAccount
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "全部账户",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (fundingAccounts.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyStatePanel("还没有资金账户。新增后可在记账时直接选择。")
            }
        } else {
            items(fundingAccounts, key = { it.id }) { account ->
                FundingAccountCard(
                    account = account,
                    isDefault = account.syncId == defaultFundingAccountSyncId,
                    onSetDefault = {
                        scope.launch {
                            val id = if (account.syncId == defaultFundingAccountSyncId) null else account.id
                            actions.onSetDefaultFundingAccount(id)
                        }
                    },
                    onEdit = { onEdit(account) },
                    onRequestDelete = { onRequestDelete(account) }
                )
            }
        }
    }
}

@Composable
private fun FundingAccountHeader(
    accountCount: Int,
    onBack: () -> Unit,
    onAdd: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Button(
                onClick = onAdd,
                modifier = Modifier.testTag(LedgerTestTags.ADD_FUNDING_ACCOUNT)
            ) {
                Text("＋ 新增账户")
            }
        }
        Text(
            "资金账户",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "跨账本共享 · $accountCount 个账户",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FundingAccountOverview(
    accountCount: Int,
    defaultAccount: FundingAccountEntity?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "账户总数",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        accountCount.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        "默认账户",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        defaultAccount?.label ?: "未设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                "默认账户仅用于新账目和待确认入账",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
@Suppress("LongMethod")
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
        border = BorderStroke(
            if (isDefault) 1.5.dp else 1.dp,
            if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isDefault) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            when (account.paymentSource) {
                                PaymentSource.WECHAT -> "微"
                                PaymentSource.ALIPAY -> "支"
                                PaymentSource.OTHER -> "其"
                                null -> "账"
                            },
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        account.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "支付来源 · ${account.paymentSource.labelOrNone()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isDefault) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "默认",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSetDefault) { Text(if (isDefault) "取消默认" else "设为默认") }
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag(LedgerTestTags.editFundingAccount(account.id))
                ) {
                    Text("编辑")
                }
                TextButton(
                    onClick = onRequestDelete,
                    modifier = Modifier.testTag(LedgerTestTags.deleteFundingAccount(account.id))
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
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
    var label by rememberSaveable(account?.id) { mutableStateOf(account?.label.orEmpty()) }
    var paymentSource by rememberSaveable(account?.id) { mutableStateOf(account?.paymentSource) }
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
                    options = listOf(null, PaymentSource.WECHAT, PaymentSource.ALIPAY, PaymentSource.OTHER),
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
