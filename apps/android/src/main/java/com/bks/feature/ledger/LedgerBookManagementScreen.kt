package com.bks.feature.ledger

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bks.ui.components.Button
import com.bks.ui.components.EmptyStatePanel
import com.bks.ui.components.OutlinedButton
import com.bks.ui.components.OutlinedTextField
import com.bks.ui.components.TextButton
import kotlinx.coroutines.launch

@Composable
internal fun LedgerBookManagementContent(
    ledgerBooks: List<LedgerBookUiModel>,
    snackbarHostState: SnackbarHostState,
    actions: LedgerBookManagementActions
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<LedgerBookUiModel?>(null) }
    var blockedDeleteMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun deleteLedger(ledgerBook: LedgerBookUiModel) {
        scope.launch {
            val result = runCatching { actions.onDeleteLedger(ledgerBook.id) }
                .getOrElse {
                    pendingDelete = null
                    snackbarHostState.showSnackbar(it.userMessage())
                    return@launch
                }
            pendingDelete = null
            handleLedgerDeleteResult(
                result = result,
                ledgerBook = ledgerBook,
                snackbarHostState = snackbarHostState,
                onBlocked = { blockedDeleteMessage = it }
            )
        }
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
            "账本管理",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Button(
            onClick = {
                createError = null
                showCreateDialog = true
            },
            modifier = Modifier.testTag(LedgerTestTags.ADD_LEDGER)
        ) {
            Text("新建账本")
        }
        LedgerBookList(
            ledgerBooks = ledgerBooks,
            snackbarHostState = snackbarHostState,
            actions = actions,
            onRequestDelete = { ledgerBook ->
                when {
                    ledgerBooks.size <= 1 -> blockedDeleteMessage = lastLedgerMessage()
                    ledgerBook.totalEntryCount > 0 -> {
                        blockedDeleteMessage = nonEmptyLedgerMessage(
                            ledgerBook.activeEntryCount,
                            ledgerBook.deletedEntryCount
                        )
                    }
                    else -> {
                        blockedDeleteMessage = null
                        pendingDelete = ledgerBook
                    }
                }
            }
        )
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
                        runCatching { actions.onCreateLedger(normalizedName) }
                            .onSuccess {
                                showCreateDialog = false
                                actions.onBack()
                            }
                            .onFailure { createError = it.userMessage() }
                    }
                }
            }
        )
    }

    pendingDelete?.let { ledgerBook ->
        LedgerBookDeleteDialog(
            ledgerBook = ledgerBook,
            onDismiss = { pendingDelete = null },
            onConfirm = { deleteLedger(ledgerBook) }
        )
    }
    blockedDeleteMessage?.let { message ->
        LedgerBookBlockedDeleteDialog(
            message = message,
            onDismiss = { blockedDeleteMessage = null }
        )
    }
}

private suspend fun handleLedgerDeleteResult(
    result: LedgerBookDeleteResult,
    ledgerBook: LedgerBookUiModel,
    snackbarHostState: SnackbarHostState,
    onBlocked: (String) -> Unit
) {
    when (result) {
        LedgerBookDeleteResult.Deleted -> {
            snackbarHostState.showSnackbar("已删除账本「${ledgerBook.name}」")
        }
        LedgerBookDeleteResult.LastLedger -> onBlocked(lastLedgerMessage())
        is LedgerBookDeleteResult.NotEmpty -> onBlocked(
            nonEmptyLedgerMessage(result.activeEntryCount, result.deletedEntryCount)
        )
    }
}

@Composable
private fun LedgerBookList(
    ledgerBooks: List<LedgerBookUiModel>,
    snackbarHostState: SnackbarHostState,
    actions: LedgerBookManagementActions,
    onRequestDelete: (LedgerBookUiModel) -> Unit
) {
    if (ledgerBooks.isEmpty()) {
        EmptyStatePanel("暂无可用账本")
    } else {
        ledgerBooks.forEach { ledgerBook ->
            LedgerBookCard(
                ledgerBook = ledgerBook,
                snackbarHostState = snackbarHostState,
                actions = actions,
                onRequestDelete = { onRequestDelete(ledgerBook) }
            )
        }
    }
}

@Composable
private fun LedgerBookCard(
    ledgerBook: LedgerBookUiModel,
    snackbarHostState: SnackbarHostState,
    actions: LedgerBookManagementActions,
    onRequestDelete: () -> Unit
) {
    val scope = rememberCoroutineScope()

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
            fun selectLedger() {
                scope.launch {
                    runCatching { actions.onSelectLedger(ledgerBook.id) }
                        .onSuccess { actions.onBack() }
                        .onFailure { snackbarHostState.showSnackbar(it.userMessage()) }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(LedgerTestTags.ledgerBook(ledgerBook.id))
                    .clickable(enabled = !ledgerBook.isActive, onClick = ::selectLedger),
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
                        onClick = ::selectLedger,
                        modifier = Modifier.testTag(LedgerTestTags.selectLedger(ledgerBook.id))
                    ) {
                        Text("切换")
                    }
                }
                OutlinedButton(
                    onClick = onRequestDelete,
                    modifier = Modifier.testTag(LedgerTestTags.deleteLedger(ledgerBook.id))
                ) {
                    Text("删除")
                }
            }
        }
    }
}

@Composable
private fun LedgerBookDeleteDialog(
    ledgerBook: LedgerBookUiModel,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除账本？") },
        text = { Text("将删除空账本「${ledgerBook.name}」。此操作无法撤销。") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(LedgerTestTags.CONFIRM_DELETE_LEDGER)
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
private fun LedgerBookBlockedDeleteDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("无法删除账本") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}


@Composable
private fun LedgerBookCreateDialog(
    errorMessage: String?,
    onInputChanged: () -> Unit,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
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

private fun LedgerBookUiModel.entryCountText(): String = when {
    totalEntryCount == 0 -> "暂无账目"
    deletedEntryCount == 0 -> "共 $activeEntryCount 笔账目"
    else -> "当前 $activeEntryCount 笔，最近删除 $deletedEntryCount 笔"
}

private fun lastLedgerMessage(): String = "至少需要保留一个账本。"

private fun nonEmptyLedgerMessage(activeEntryCount: Int, deletedEntryCount: Int): String =
    "该账本仍有 $activeEntryCount 笔当前账目和 $deletedEntryCount 笔最近删除账目，清空后才能删除。"
