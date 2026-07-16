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
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.ui.components.Button
import com.autoaccounting.ui.components.EmptyStatePanel
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.OutlinedTextField
import com.autoaccounting.ui.components.TextButton
import kotlinx.coroutines.launch

data class LedgerBookUiModel(
    val id: String,
    val name: String,
    val activeEntryCount: Int = 0,
    val deletedEntryCount: Int = 0,
    val isActive: Boolean = false
) {
    val totalEntryCount: Int
        get() = activeEntryCount + deletedEntryCount
}

sealed interface LedgerBookDeleteResult {
    data object Deleted : LedgerBookDeleteResult
    data object LastLedger : LedgerBookDeleteResult
    data class NotEmpty(
        val activeEntryCount: Int,
        val deletedEntryCount: Int
    ) : LedgerBookDeleteResult
}

sealed interface FundingAccountDeleteResult {
    data object Deleted : FundingAccountDeleteResult
    data class Referenced(
        val activeLedgerEntryCount: Int,
        val deletedLedgerEntryCount: Int,
        val pendingEntryCount: Int,
        val ignoredEntryCount: Int
    ) : FundingAccountDeleteResult
}

@Composable
internal fun LedgerBookManagementScreen(
    ledgerBooks: List<LedgerBookUiModel>,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onCreateLedger: suspend (String) -> Unit,
    onSelectLedger: suspend (String) -> Unit,
    onDeleteLedger: suspend (String) -> LedgerBookDeleteResult
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<LedgerBookUiModel?>(null) }
    var blockedDeleteMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("返回账本") }
        Text("账本管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Button(
            onClick = {
                createError = null
                showCreateDialog = true
            },
            modifier = Modifier.testTag(LedgerTestTags.ADD_LEDGER)
        ) {
            Text("新建账本")
        }
        if (ledgerBooks.isEmpty()) {
            EmptyStatePanel("暂无可用账本")
        } else {
            ledgerBooks.forEach { ledgerBook ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(LedgerTestTags.ledgerBook(ledgerBook.id))
                                .clickable(enabled = !ledgerBook.isActive) {
                                    scope.launch {
                                        runCatching { onSelectLedger(ledgerBook.id) }
                                            .onSuccess { onBack() }
                                            .onFailure {
                                                snackbarHostState.showSnackbar(it.userMessage())
                                            }
                                    }
                                },
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(ledgerBook.name, fontWeight = FontWeight.SemiBold)
                                if (ledgerBook.isActive) {
                                    Text("当前账本", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text(ledgerBook.entryCountText())
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!ledgerBook.isActive) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            runCatching { onSelectLedger(ledgerBook.id) }
                                                .onSuccess { onBack() }
                                                .onFailure {
                                                    snackbarHostState.showSnackbar(it.userMessage())
                                                }
                                        }
                                    },
                                    modifier = Modifier.testTag(
                                        LedgerTestTags.selectLedger(ledgerBook.id)
                                    )
                                ) {
                                    Text("切换")
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    blockedDeleteMessage = when {
                                        ledgerBooks.size <= 1 -> lastLedgerMessage()
                                        ledgerBook.totalEntryCount > 0 ->
                                            nonEmptyLedgerMessage(
                                                ledgerBook.activeEntryCount,
                                                ledgerBook.deletedEntryCount
                                            )

                                        else -> {
                                            pendingDelete = ledgerBook
                                            null
                                        }
                                    }
                                },
                                modifier = Modifier.testTag(
                                    LedgerTestTags.deleteLedger(ledgerBook.id)
                                )
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        LedgerBookCreateDialog(
            errorMessage = createError,
            onInputChanged = { createError = null },
            onDismiss = {
                showCreateDialog = false
                createError = null
            },
            onCreate = { enteredName ->
                val normalizedName = enteredName.trim()
                createError = when {
                    normalizedName.isEmpty() -> "请输入账本名称"
                    ledgerBooks.any { it.name == normalizedName } -> "已存在同名账本"
                    else -> null
                }
                if (createError == null) {
                    scope.launch {
                        runCatching { onCreateLedger(normalizedName) }
                            .onSuccess {
                                showCreateDialog = false
                                onBack()
                            }
                            .onFailure { createError = it.userMessage() }
                    }
                }
            }
        )
    }

    pendingDelete?.let { ledgerBook ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除账本？") },
            text = { Text("将删除空账本「${ledgerBook.name}」。此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            when (
                                val result = runCatching {
                                    onDeleteLedger(ledgerBook.id)
                                }.getOrElse {
                                    pendingDelete = null
                                    snackbarHostState.showSnackbar(it.userMessage())
                                    return@launch
                                }
                            ) {
                                LedgerBookDeleteResult.Deleted -> {
                                    pendingDelete = null
                                    snackbarHostState.showSnackbar("已删除账本「${ledgerBook.name}」")
                                }

                                LedgerBookDeleteResult.LastLedger -> {
                                    pendingDelete = null
                                    blockedDeleteMessage = lastLedgerMessage()
                                }

                                is LedgerBookDeleteResult.NotEmpty -> {
                                    pendingDelete = null
                                    blockedDeleteMessage = nonEmptyLedgerMessage(
                                        result.activeEntryCount,
                                        result.deletedEntryCount
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag(LedgerTestTags.CONFIRM_DELETE_LEDGER)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    blockedDeleteMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { blockedDeleteMessage = null },
            title = { Text("无法删除账本") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { blockedDeleteMessage = null }) { Text("知道了") }
            }
        )
    }
}

@Composable
private fun LedgerBookCreateDialog(
    errorMessage: String?,
    onInputChanged: () -> Unit,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建账本") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    onInputChanged()
                },
                label = { Text("账本名称") },
                supportingText = errorMessage?.let { message -> ({ Text(message) }) },
                isError = errorMessage != null,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(LedgerTestTags.LEDGER_NAME)
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                modifier = Modifier.testTag(LedgerTestTags.CONFIRM_ADD_LEDGER)
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
internal fun FundingAccountManagementScreen(
    fundingAccounts: List<FundingAccountEntity>,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onCreateFundingAccount: suspend (String, PaymentSource?) -> Unit,
    onUpdateFundingAccount: suspend (Long, String, PaymentSource?) -> Unit,
    onDeleteFundingAccount: suspend (Long) -> FundingAccountDeleteResult
) {
    var showEditor by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf<FundingAccountEntity?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<FundingAccountEntity?>(null) }
    var blockedDeleteMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("返回账本") }
        Text("资金账户", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Button(
            onClick = {
                editingAccount = null
                editorError = null
                showEditor = true
            },
            modifier = Modifier.testTag(LedgerTestTags.ADD_FUNDING_ACCOUNT)
        ) {
            Text("新增资金账户")
        }
        if (fundingAccounts.isEmpty()) {
            EmptyStatePanel("暂无资金账户")
        } else {
            fundingAccounts.forEach { account ->
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
                        Text("支付来源：${account.paymentSource.labelOrNone()}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    editingAccount = account
                                    editorError = null
                                    showEditor = true
                                },
                                modifier = Modifier.testTag(
                                    LedgerTestTags.editFundingAccount(account.id)
                                )
                            ) {
                                Text("编辑")
                            }
                            OutlinedButton(
                                onClick = { pendingDelete = account },
                                modifier = Modifier.testTag(
                                    LedgerTestTags.deleteFundingAccount(account.id)
                                )
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
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
                                onCreateFundingAccount(normalizedLabel, paymentSource)
                            } else {
                                onUpdateFundingAccount(account.id, normalizedLabel, paymentSource)
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
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除资金账户？") },
            text = { Text("将删除资金账户「${account.label}」。已使用的账户不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            when (
                                val result = runCatching {
                                    onDeleteFundingAccount(account.id)
                                }.getOrElse {
                                    pendingDelete = null
                                    snackbarHostState.showSnackbar(it.userMessage())
                                    return@launch
                                }
                            ) {
                                FundingAccountDeleteResult.Deleted -> {
                                    pendingDelete = null
                                    snackbarHostState.showSnackbar("已删除资金账户「${account.label}」")
                                }

                                is FundingAccountDeleteResult.Referenced -> {
                                    pendingDelete = null
                                    blockedDeleteMessage = result.referenceMessage()
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag(
                        LedgerTestTags.CONFIRM_DELETE_FUNDING_ACCOUNT
                    )
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    blockedDeleteMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { blockedDeleteMessage = null },
            title = { Text("无法删除资金账户") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { blockedDeleteMessage = null }) { Text("知道了") }
            }
        )
    }
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

private fun LedgerBookUiModel.entryCountText(): String = when {
    totalEntryCount == 0 -> "暂无账目"
    deletedEntryCount == 0 -> "共 $activeEntryCount 笔账目"
    else -> "当前 $activeEntryCount 笔，最近删除 $deletedEntryCount 笔"
}

private fun lastLedgerMessage(): String = "至少需要保留一个账本。"

private fun nonEmptyLedgerMessage(activeEntryCount: Int, deletedEntryCount: Int): String =
    "该账本仍有 $activeEntryCount 笔当前账目和 $deletedEntryCount 笔最近删除账目，清空后才能删除。"

private fun FundingAccountDeleteResult.Referenced.referenceMessage(): String =
    "该账户仍被 $activeLedgerEntryCount 笔当前账目、$deletedLedgerEntryCount 笔最近删除账目、" +
        "$pendingEntryCount 条待确认记录和 $ignoredEntryCount 条忽略记录引用。"

internal object LedgerTestTags {
    const val ENTRY_LIST = "ledger-entry-list"
    const val MORE_MENU = "ledger-more"
    const val MANAGE_LEDGERS = "ledger-manage-ledgers"
    const val MANAGE_FUNDING_ACCOUNTS = "ledger-manage-funding-accounts"
    const val RECENTLY_DELETED = "ledger-recently-deleted"
    const val ADD_LEDGER = "ledger-add"
    const val LEDGER_NAME = "ledger-name"
    const val CONFIRM_ADD_LEDGER = "ledger-confirm-add"
    const val CONFIRM_DELETE_LEDGER = "ledger-confirm-delete"
    const val ADD_FUNDING_ACCOUNT = "funding-account-add"
    const val FUNDING_ACCOUNT_LABEL = "funding-account-label"
    const val FUNDING_ACCOUNT_SOURCE = "funding-account-source"
    const val SAVE_FUNDING_ACCOUNT = "funding-account-save"
    const val CONFIRM_DELETE_FUNDING_ACCOUNT = "funding-account-confirm-delete"

    fun ledgerBook(id: String): String = "ledger-book-$id"
    fun selectLedger(id: String): String = "ledger-select-$id"
    fun deleteLedger(id: String): String = "ledger-delete-$id"
    fun fundingAccount(id: Long): String = "funding-account-$id"
    fun editFundingAccount(id: Long): String = "funding-account-edit-$id"
    fun deleteFundingAccount(id: Long): String = "funding-account-delete-$id"
}
