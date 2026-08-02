package com.autoaccounting.feature.ledger

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.LedgerEntryInput
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.TransactionKind
import kotlinx.coroutines.launch

@Composable
internal fun ManualLedgerEntryScreen(
    categories: List<CategoryEntity>,
    fundingAccounts: List<FundingAccountEntity>,
    onExit: () -> Unit,
    onCreateEntry: suspend (LedgerEntryInput) -> Unit,
    modifier: Modifier = Modifier
) {
    val initial = rememberSaveable(saver = LedgerEntryFormState.Saver) {
        LedgerEntryFormState.newEntry()
    }
    val snackbarHostState = remember { SnackbarHostState() }

    Box(modifier = modifier.fillMaxSize()) {
        SharedLedgerEntryForm(
            title = "新增一笔",
            initial = initial,
            categories = categories,
            fundingAccounts = fundingAccounts,
            config = LedgerEntryFormConfig(
                flowDirections = listOf(FlowDirection.OUTFLOW, FlowDirection.INFLOW),
                allowCreateFundingAccount = false,
                saveLabel = "保存账目",
                onExit = onExit,
                onSave = onCreateEntry,
                onDelete = null,
                snackbarHostState = snackbarHostState
            )
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp)
        )
    }
}

@Composable
internal fun SharedLedgerEntryForm(
    title: String,
    initial: LedgerEntryFormState,
    categories: List<CategoryEntity>,
    fundingAccounts: List<FundingAccountEntity>,
    config: LedgerEntryFormConfig
) {
    var state by rememberSaveable(initial, stateSaver = LedgerEntryFormState.Saver) {
        mutableStateOf(initial)
    }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val categoryOptions = remember(categories, state.flowDirection, state.transactionKind) {
        ledgerCategoryOptions(categories, state.flowDirection, state.transactionKind)
    }

    LaunchedEffect(categoryOptions, state.categoryId) {
        if (categoryOptions.none { it.id == state.categoryId }) {
            state = state.copy(categoryId = LocalLedgerRepository.DEFAULT_CATEGORY_ID)
        }
    }

    fun requestExit() {
        if (state != initial) confirmDiscard = true else config.onExit()
    }

    fun saveEntry() {
        scope.launch {
            runCatching { state.toInput(System.currentTimeMillis()) }
                .mapCatching { input -> config.onSave(input) }
                .onFailure { config.snackbarHostState.showSnackbar(it.userMessage()) }
        }
    }

    BackHandler { requestExit() }

    Column(modifier = Modifier.fillMaxSize()) {
        ManualEntryHeader(title = title, onBack = ::requestExit)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            config.leadingContent(state) { state = it }
            ManualAmountCard(
                state = state,
                directions = config.flowDirections,
                onStateChange = { state = it }
            )
            ManualTransactionCard(
                state = state,
                categoryOptions = categoryOptions,
                onStateChange = { state = it },
                onSelectTime = {
                    showDateTimePicker(context, state.transactionTimeEpochMillis) {
                        state = state.copy(transactionTimeEpochMillis = it)
                    }
                }
            )
            ManualAccountCard(
                state = state,
                fundingAccounts = fundingAccounts,
                allowCreateFundingAccount = config.allowCreateFundingAccount,
                onStateChange = { state = it }
            )
            if (config.onDelete != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit-entry-delete")
                ) {
                    Text("删除账目")
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        ManualEntryActions(
            saveLabel = config.saveLabel,
            onCancel = ::requestExit,
            onSave = ::saveEntry
        )
    }

    DiscardChangesDialog(
        visible = confirmDiscard,
        onDismiss = { confirmDiscard = false },
        onDiscard = config.onExit
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这笔账？") },
            text = { Text("账目将移入最近删除，可在 30 天内恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        config.onDelete?.invoke()
                    }
                ) { Text("移入最近删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
internal fun LedgerEntryForm(
    title: String,
    initial: LedgerEntryFormState,
    categories: List<CategoryEntity>,
    fundingAccounts: List<FundingAccountEntity>,
    config: LedgerEntryFormConfig
) {
    SharedLedgerEntryForm(
        title = title,
        initial = initial,
        categories = categories,
        fundingAccounts = fundingAccounts,
        config = config.copy(
            flowDirections = listOf(FlowDirection.OUTFLOW, FlowDirection.INFLOW),
            allowCreateFundingAccount = false,
            saveLabel = "保存修改",
            leadingContent = { _, _ -> }
        )
    )
}

internal fun ledgerCategoryOptions(
    categories: List<CategoryEntity>,
    flowDirection: FlowDirection,
    transactionKind: TransactionKind
): List<CategoryEntity> {
    val matchingCategories = categories.filter { category ->
        when {
            category.kind == null -> true
            transactionKind == TransactionKind.REFUND -> category.kind == TransactionKind.REFUND
            flowDirection == FlowDirection.INFLOW -> category.kind == TransactionKind.INCOME
            flowDirection == FlowDirection.OUTFLOW -> category.kind == TransactionKind.EXPENSE
            else -> true
        }
    }
    return (matchingCategories + CategoryEntity(
        id = LocalLedgerRepository.DEFAULT_CATEGORY_ID,
        name = "未分类",
        kind = null,
        sortOrder = Int.MAX_VALUE,
        isSystem = true,
        createdAtEpochMillis = 0
    )).distinctBy { it.id }
}

@Composable
private fun DiscardChangesDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("放弃未保存的修改？") },
        text = { Text("离开后，本次修改不会保存。") },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onDiscard()
                }
            ) { Text("放弃修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("继续编辑") } }
    )
}
