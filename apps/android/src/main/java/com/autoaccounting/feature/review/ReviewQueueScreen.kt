package com.autoaccounting.feature.review

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import com.autoaccounting.ui.components.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import com.autoaccounting.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.DefaultCategories
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.categorization.AiCategorizationClient
import com.autoaccounting.feature.categorization.AiCategorizationGateway
import com.autoaccounting.feature.categorization.AiCategorizationResult
import com.autoaccounting.feature.categorization.AiCategorizationSettings
import com.autoaccounting.feature.categorization.AiCategorizationSkipReason
import com.autoaccounting.feature.categorization.CategorizationRule

@Composable
@Suppress("LongParameterList")
fun ReviewQueueScreen(
    modifier: Modifier = Modifier,
    initialState: ReviewQueueState = ReviewQueueState(),
    targetLedgerName: String = "默认账本",
    categories: List<CategoryEntity> = emptyList(),
    fundingAccounts: List<FundingAccountEntity> = emptyList(),
    onCategorizationRuleRequested: (CategorizationRule) -> Unit = {},
    accountSession: AccountSession? = null,
    aiSettings: AiCategorizationSettings = AiCategorizationSettings(),
    aiCategorizationGateway: AiCategorizationGateway? = null,
    onOpenBillImport: () -> Unit = {},
    openPendingEntryId: String? = null,
    openPendingEntryRequestId: Long = 0,
    onNavigateHome: () -> Unit = {}
) {
    var state by remember { mutableStateOf(initialState) }
    ReviewQueueScreen(
        state = state,
        onStateChange = { state = it },
        targetLedgerName = targetLedgerName,
        categories = categories,
        fundingAccounts = fundingAccounts,
        modifier = modifier,
        onCategorizationRuleRequested = onCategorizationRuleRequested,
        accountSession = accountSession,
        aiSettings = aiSettings,
        aiCategorizationGateway = aiCategorizationGateway,
        onOpenBillImport = onOpenBillImport,
        openPendingEntryId = openPendingEntryId,
        openPendingEntryRequestId = openPendingEntryRequestId,
        onNavigateHome = onNavigateHome
    )
}

@Composable
fun ReviewQueueScreen(
    state: ReviewQueueState,
    onStateChange: (ReviewQueueState) -> Unit,
    targetLedgerName: String = "默认账本",
    categories: List<CategoryEntity> = emptyList(),
    fundingAccounts: List<FundingAccountEntity> = emptyList(),
    modifier: Modifier = Modifier,
    onCategorizationRuleRequested: (CategorizationRule) -> Unit = {},
    accountSession: AccountSession? = null,
    aiSettings: AiCategorizationSettings = AiCategorizationSettings(),
    aiCategorizationGateway: AiCategorizationGateway? = null,
    onOpenBillImport: () -> Unit = {},
    openPendingEntryId: String? = null,
    openPendingEntryRequestId: Long = 0,
    onNavigateHome: () -> Unit = {}
) {
    var editingEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingEntry = remember(state.pendingEntries, editingEntryId) {
        state.pendingEntries.firstOrNull { it.id == editingEntryId }
    }
    var pendingRuleSave by remember { mutableStateOf<PendingCategoryRuleSave?>(null) }
    var showIgnoredList by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(openPendingEntryRequestId, openPendingEntryId, state.pendingEntries) {
        if (openPendingEntryRequestId > 0 && openPendingEntryId != null) {
            editingEntryId = state.pendingEntries.firstOrNull { it.id == openPendingEntryId }?.id
        }
    }

    fun dispatch(action: ReviewQueueAction) {
        onStateChange(reduceReviewQueue(state, action))
    }

    fun confirmEdit(entry: ReviewQueueEntry, edit: PendingReviewEdit) {
        val editedState = reduceReviewQueue(state, edit.toSaveAction(entry.id))
        onStateChange(reduceReviewQueue(editedState, ReviewQueueAction.Confirm(entry.id)))
        pendingRuleSave = null
        editingEntryId = null
    }

    fun applyEdit(pending: PendingCategoryRuleSave) {
        confirmEdit(pending.entry, pending.edit)
    }

    val lastAction = state.lastAction
    LaunchedEffect(lastAction?.eventId) {
        val undoAction = lastAction ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = undoAction.message,
            actionLabel = "撤销",
            duration = SnackbarDuration.Short
        )
        dispatch(
            if (result == SnackbarResult.ActionPerformed) {
                ReviewQueueAction.UndoLastAction
            } else {
                ReviewQueueAction.DismissUndo
            }
        )
    }

    val activeEdit = editingEntry
    if (activeEdit == null) {
        Scaffold(
            modifier = modifier,
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            ReviewQueueListContent(
                state = state,
                targetLedgerName = targetLedgerName,
                actions = ReviewQueueListActions(
                    onAction = ::dispatch,
                    onEdit = { editingEntryId = it.id },
                    onShowIgnoredList = { showIgnoredList = true },
                    onOpenBillImport = onOpenBillImport,
                    onNavigateHome = onNavigateHome
                ),
                modifier = Modifier.padding(innerPadding)
            )
        }
    } else {
        val availableCategories = remember(categories) {
            categories.ifEmpty { DefaultCategories.systemDefaults(0) }
        }
        ReviewPendingEntryEditor(
            entry = activeEdit,
            availableCategories = availableCategories,
            fundingAccounts = fundingAccounts,
            config = ReviewPendingEntryEditorConfig(
                modifier = modifier,
                snackbarHostState = snackbarHostState,
                onExit = { editingEntryId = null },
                onAiSuggest = { draft, categoryCandidates ->
                    val gateway = aiCategorizationGateway
                    if (gateway == null) {
                        AiCategorizationResult(
                            skipReason = AiCategorizationSkipReason.REQUIRES_SIGNED_IN_ACCOUNT
                        )
                    } else {
                        AiCategorizationClient(gateway).suggestCategory(
                            entry = draft,
                            session = accountSession,
                            settings = aiSettings,
                            categoryCandidates = categoryCandidates
                        )
                    }
                },
                onConfirm = { edit ->
                    if (activeEdit.hasCategoryCorrection(edit.category)) {
                        pendingRuleSave = PendingCategoryRuleSave(activeEdit, edit)
                    } else {
                        confirmEdit(activeEdit, edit)
                    }
                }
            )
        )
    }

    pendingRuleSave?.let { pending ->
        AlertDialog(
            onDismissRequest = { applyEdit(pending) },
            title = { Text("保存为分类规则？") },
            text = {
                Text("以后遇到相同商户、来源和交易类型时，自动建议“${pending.edit.category.trim()}”。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCategorizationRuleRequested(pending.toCategorizationRule())
                        applyEdit(pending)
                    }
                ) {
                    Text("保存规则")
                }
            },
            dismissButton = {
                TextButton(onClick = { applyEdit(pending) }) {
                    Text("这次不保存")
                }
            }
        )
    }

    if (showIgnoredList) {
        IgnoredEntriesDialog(
            ignoredEntries = state.recoverableIgnoredEntries,
            onDismiss = { showIgnoredList = false },
            onRecover = {
                dispatch(ReviewQueueAction.RecoverIgnored(it))
                showIgnoredList = false
            }
        )
    }

}
