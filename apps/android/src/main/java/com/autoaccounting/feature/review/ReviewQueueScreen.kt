package com.autoaccounting.feature.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import com.autoaccounting.ui.components.Button
import com.autoaccounting.ui.components.EmptyStatePanel
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.autoaccounting.ui.components.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import com.autoaccounting.ui.components.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoaccounting.R
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.data.local.DefaultCategories
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.LedgerEntryInput
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.data.local.defaultFlowDirection
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.categorization.AiCategorizationClient
import com.autoaccounting.feature.categorization.AiCategorizationGateway
import com.autoaccounting.feature.categorization.AiCategorizationResult
import com.autoaccounting.feature.categorization.AiCategorizationSettings
import com.autoaccounting.feature.categorization.AiCategorizationSkipReason
import com.autoaccounting.feature.categorization.CategorizationRule
import com.autoaccounting.feature.ledger.LedgerEntryFormState
import com.autoaccounting.feature.ledger.SharedLedgerEntryForm
import com.autoaccounting.ui.visual.CategoryArtwork
import com.autoaccounting.ui.components.HomeReturnButton
import kotlin.math.abs

@Composable
fun ReviewQueueScreen(
    modifier: Modifier = Modifier,
    initialState: ReviewQueueState = ReviewQueueState(
        pendingEntries = sampleReviewQueueEntries()
    ),
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
    var editingEntry by remember { mutableStateOf<ReviewQueueEntry?>(null) }
    var pendingRuleSave by remember { mutableStateOf<PendingCategoryRuleSave?>(null) }
    var showIgnoredList by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(openPendingEntryRequestId, openPendingEntryId, state.pendingEntries) {
        if (openPendingEntryRequestId > 0 && openPendingEntryId != null) {
            editingEntry = state.pendingEntries.firstOrNull { it.id == openPendingEntryId }
        }
    }

    fun dispatch(action: ReviewQueueAction) {
        onStateChange(reduceReviewQueue(state, action))
    }

    fun confirmEdit(entry: ReviewQueueEntry, edit: PendingReviewEdit) {
        val editedState = reduceReviewQueue(state, edit.toSaveAction(entry.id))
        onStateChange(reduceReviewQueue(editedState, ReviewQueueAction.Confirm(entry.id)))
        pendingRuleSave = null
        editingEntry = null
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
            ReviewQueueContent(
                state = state,
                targetLedgerName = targetLedgerName,
                onAction = ::dispatch,
                onEdit = { editingEntry = it },
                onShowIgnoredList = { showIgnoredList = true },
                onOpenBillImport = onOpenBillImport,
                onNavigateHome = onNavigateHome,
                modifier = Modifier.padding(innerPadding)
            )
        }
    } else {
        ReviewPendingEntryEditor(
            entry = activeEdit,
            categories = categories,
            fundingAccounts = fundingAccounts,
            modifier = modifier,
            snackbarHostState = snackbarHostState,
            onExit = { editingEntry = null },
            onAiSuggest = { draft ->
                val gateway = aiCategorizationGateway
                    ?: return@ReviewPendingEntryEditor AiCategorizationResult(
                        skipReason = AiCategorizationSkipReason.REQUIRES_SIGNED_IN_ACCOUNT
                    )
                AiCategorizationClient(gateway).suggestCategory(
                    entry = draft,
                    session = accountSession,
                    settings = aiSettings
                )
            },
            onConfirm = { edit ->
                if (activeEdit.hasCategoryCorrection(edit.category)) {
                    pendingRuleSave = PendingCategoryRuleSave(activeEdit, edit)
                } else {
                    confirmEdit(activeEdit, edit)
                }
            }
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

@Composable
private fun ReviewQueueContent(
    state: ReviewQueueState,
    targetLedgerName: String,
    onAction: (ReviewQueueAction) -> Unit,
    onEdit: (ReviewQueueEntry) -> Unit,
    onShowIgnoredList: () -> Unit,
    onOpenBillImport: () -> Unit,
    onNavigateHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sortedEntries = state.sortedPendingEntries

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("review-queue-list"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "review-header") {
            ReviewHeader(
                targetLedgerName = targetLedgerName,
                onShowIgnoredList = onShowIgnoredList,
                onNavigateHome = onNavigateHome
            )
        }

        item(key = "review-summary") {
            ReviewSummaryCard(state)
        }

        item(key = "bill-import") {
            BillImportEntry(onClick = onOpenBillImport)
        }

        item(key = "review-list-header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "待确认记录",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "向右滑确认 · 向左滑忽略",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (sortedEntries.isEmpty()) {
            item(key = "review-empty") {
                EmptyStatePanel("暂无待确认记录")
            }
        } else {
            items(
                items = sortedEntries,
                key = { entry -> entry.id }
            ) { entry ->
                ReviewEntryRow(
                    entry = entry,
                    onConfirm = { onAction(ReviewQueueAction.Confirm(entry.id)) },
                    onIgnore = { onAction(ReviewQueueAction.Ignore(entry.id)) },
                    onEdit = { onEdit(entry) }
                )
            }
        }

        item(key = "review-list-bottom-space") {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ReviewHeader(
    targetLedgerName: String,
    onShowIgnoredList: () -> Unit,
    onNavigateHome: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("review-header-row"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "待确认",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onShowIgnoredList) {
                    Text("忽略记录")
                }
                HomeReturnButton(onClick = onNavigateHome)
            }
        }
        Text(
            text = "确认后记入「$targetLedgerName」",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReviewSummaryCard(state: ReviewQueueState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "当前任务",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${state.pendingEntries.size} 条待确认",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelPill(
                        text = "疑似重复 ${state.duplicateSuspectCount}",
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                    LabelPill(
                        text = "今日待确认 ${state.todaysNewlyCapturedCount}",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun BillImportEntry(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bill-import-entry")
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "账",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "补录账单",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "从微信或支付宝账单页导入",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun LabelPill(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewEntryRow(
    entry: ReviewQueueEntry,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    onEdit: () -> Unit
) {
    var rowWidthPx by remember { mutableIntStateOf(0) }
    lateinit var dismissState: SwipeToDismissBoxState
    dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * SWIPE_ACTION_THRESHOLD_FRACTION },
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.Settled -> true
                SwipeToDismissBoxValue.StartToEnd,
                SwipeToDismissBoxValue.EndToStart -> {
                    val reachedThreshold = rowWidthPx > 0 &&
                        abs(dismissState.requireOffset()) >=
                        rowWidthPx * SWIPE_ACTION_THRESHOLD_FRACTION
                    if (reachedThreshold) {
                        when (value) {
                            SwipeToDismissBoxValue.StartToEnd -> onConfirm()
                            SwipeToDismissBoxValue.EndToStart -> onIgnore()
                            SwipeToDismissBoxValue.Settled -> Unit
                        }
                    }
                    reachedThreshold
                }
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.onSizeChanged { rowWidthPx = it.width },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = { SwipeBackground(dismissState.dismissDirection) },
        content = {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("detail-${entry.id}")
                    .clickable(onClick = onEdit),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        CategoryArtwork(
                            categoryName = entry.category,
                            transactionKind = entry.kindLabel.toTransactionKind(),
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(entry.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "${entry.transactionTimeText} · ${entry.sourceLabel} · " +
                                    entry.fundingAccountLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val confidenceColors = confidenceColors(entry.confidence)
                                LabelPill(
                                    text = confidenceLabel(entry.confidence),
                                    containerColor = confidenceColors.first,
                                    contentColor = confidenceColors.second
                                )
                                LabelPill(
                                    text = entry.category.ifBlank { "未分类" },
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Text(
                                text = entry.captureReasonLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            entry.note?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(
                            text = formatAmount(entry.amountMinor),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onIgnore,
                            modifier = Modifier.testTag("ignore-${entry.id}")
                        ) {
                            Text("忽略")
                        }
                        Button(
                            onClick = onConfirm,
                            modifier = Modifier.testTag("confirm-${entry.id}")
                        ) {
                            Text("确认")
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun confidenceColors(confidence: ConfidenceState): Pair<Color, Color> = when (confidence) {
    ConfidenceState.DUPLICATE_SUSPECT ->
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer

    ConfidenceState.NEEDS_REVIEW ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer

    ConfidenceState.HIGH ->
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
}

private const val SWIPE_ACTION_THRESHOLD_FRACTION = 0.4f

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val text = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> "滑动确认"
        SwipeToDismissBoxValue.EndToStart -> "滑动忽略"
        SwipeToDismissBoxValue.Settled -> ""
    }
    val color = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Color(0xFFE6F4EA)
        SwipeToDismissBoxValue.EndToStart -> Color(0xFFFFECE8)
        SwipeToDismissBoxValue.Settled -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = 20.dp),
        contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
            Alignment.CenterStart
        } else {
            Alignment.CenterEnd
        }
    ) {
        Text(text = text, fontWeight = FontWeight.SemiBold)
    }
}

private data class PendingCategoryRuleSave(
    val entry: ReviewQueueEntry,
    val edit: PendingReviewEdit
) {
    fun toCategorizationRule(): CategorizationRule = CategorizationRule(
        id = "rule-${entry.id}-${entry.capturedAtEpochMillis}",
        merchantContains = edit.title.trim(),
        sourceLabel = entry.sourceLabel,
        transactionKind = edit.transactionKind.trim(),
        category = edit.category.trim(),
        updatedAtEpochMillis = entry.capturedAtEpochMillis
    )
}

private data class PendingReviewEdit(
    val title: String,
    val amountText: String,
    val timeText: String,
    val transactionKind: String,
    val categoryId: String?,
    val category: String,
    val fundingAccountId: Long?,
    val fundingAccount: String,
    val note: String
) {
    fun toSaveAction(entryId: String): ReviewQueueAction.SaveEdit = ReviewQueueAction.SaveEdit(
        entryId = entryId,
        title = title,
        amountText = amountText,
        timeText = timeText,
        transactionKind = transactionKind,
        categoryId = categoryId,
        category = category,
        fundingAccountId = fundingAccountId,
        fundingAccount = fundingAccount,
        note = note
    )

    companion object {
        fun from(
            input: LedgerEntryInput,
            categories: List<CategoryEntity>,
            fundingAccounts: List<FundingAccountEntity>
        ): PendingReviewEdit = PendingReviewEdit(
            title = input.merchantTitle,
            amountText = amountMinorToText(input.amountMinor),
            timeText = formatReviewDateTime(
                input.transactionTimeEpochMillis,
                java.time.ZoneId.systemDefault()
            ),
            transactionKind = input.transactionKind.toReviewLabel(),
            categoryId = input.categoryId,
            category = input.categoryId.toReviewCategoryName(categories),
            fundingAccountId = input.fundingAccountId,
            fundingAccount = fundingAccounts
                .firstOrNull { it.id == input.fundingAccountId }
                ?.label
                .orEmpty(),
            note = input.note.orEmpty()
        )
    }
}

private fun ReviewQueueEntry.hasCategoryCorrection(category: String): Boolean {
    return category.trim().isNotBlank() && category.trim() != this.category
}

private fun CategoryEntity.reviewDisplayName(): String =
    DefaultCategories.nameForId(id) ?: name

@Composable
private fun ReviewPendingEntryEditor(
    entry: ReviewQueueEntry,
    categories: List<CategoryEntity>,
    fundingAccounts: List<FundingAccountEntity>,
    modifier: Modifier,
    snackbarHostState: SnackbarHostState,
    onExit: () -> Unit,
    onAiSuggest: (ReviewQueueEntry) -> AiCategorizationResult,
    onConfirm: (PendingReviewEdit) -> Unit
) {
    val availableCategories = remember(categories) {
        categories.ifEmpty { DefaultCategories.systemDefaults(0) }
    }
    val initial = remember(entry, availableCategories, fundingAccounts) {
        entry.toLedgerEntryFormState(availableCategories, fundingAccounts)
    }
    Box(modifier = modifier.fillMaxSize()) {
        SharedLedgerEntryForm(
            title = "编辑待确认账目",
            initial = initial,
            categories = availableCategories,
            fundingAccounts = fundingAccounts,
            flowDirections = listOf(FlowDirection.OUTFLOW, FlowDirection.INFLOW),
            allowCreateFundingAccount = false,
            saveLabel = "确认入账",
            onExit = onExit,
            onSave = { input ->
                onConfirm(PendingReviewEdit.from(input, availableCategories, fundingAccounts))
            },
            onDelete = null,
            snackbarHostState = snackbarHostState,
            leadingContent = { draft, onDraftChange ->
                ReviewEditorTools(
                    entry = entry,
                    draft = draft,
                    categories = availableCategories,
                    fundingAccounts = fundingAccounts,
                    onDraftChange = onDraftChange,
                    onAiSuggest = onAiSuggest
                )
            }
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
private fun ReviewEditorTools(
    entry: ReviewQueueEntry,
    draft: LedgerEntryFormState,
    categories: List<CategoryEntity>,
    fundingAccounts: List<FundingAccountEntity>,
    onDraftChange: (LedgerEntryFormState) -> Unit,
    onAiSuggest: (ReviewQueueEntry) -> AiCategorizationResult
) {
    var showEvidence by remember(entry.id) { mutableStateOf(false) }
    var aiMessage by remember(entry.id) { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showEvidence = !showEvidence }) {
                    Text(if (showEvidence) "收起证据" else "查看证据")
                }
                OutlinedButton(
                    onClick = {
                        val input = runCatching { draft.toInput(System.currentTimeMillis()) }
                            .getOrElse {
                                aiMessage = it.message ?: "当前内容无法获取分类建议"
                                return@OutlinedButton
                            }
                        val result = onAiSuggest(
                            entry.copy(
                                title = input.merchantTitle,
                                amountMinor = input.amountMinor,
                                transactionTimeText = formatReviewDateTime(
                                    input.transactionTimeEpochMillis,
                                    java.time.ZoneId.systemDefault()
                                ),
                                kindLabel = input.transactionKind.toReviewLabel(),
                                categoryId = input.categoryId,
                                category = input.categoryId.toReviewCategoryName(categories),
                                fundingAccountId = input.fundingAccountId,
                                fundingAccountLabel = fundingAccounts
                                    .firstOrNull { it.id == input.fundingAccountId }
                                    ?.label
                                    .orEmpty(),
                                note = input.note
                            )
                        )
                        result.suggestion?.let { suggestion ->
                            val categoryId = categories.firstOrNull {
                                it.reviewDisplayName() == suggestion.category
                            }?.id ?: DefaultCategories.idForName(
                                suggestion.category,
                                input.transactionKind
                            )
                            if (categoryId == null) {
                                aiMessage = "AI 建议“${suggestion.category}”不在现有分类中"
                            } else {
                                onDraftChange(draft.copy(categoryId = categoryId))
                                aiMessage = "AI 建议：${suggestion.category}"
                            }
                        } ?: run {
                            aiMessage = when (result.skipReason) {
                                AiCategorizationSkipReason.REQUIRES_SIGNED_IN_ACCOUNT -> "登录后才能使用云端 AI 分类"
                                AiCategorizationSkipReason.REQUIRES_AI_CONSENT -> "开启云端 AI 后才能获取分类建议"
                                null -> "暂时没有 AI 分类建议"
                            }
                        }
                    }
                ) {
                    Text("AI 建议分类")
                }
            }
            aiMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (showEvidence) EvidenceSection(entry)
        }
    }
}

private fun ReviewQueueEntry.toLedgerEntryFormState(
    categories: List<CategoryEntity>,
    fundingAccounts: List<FundingAccountEntity>
): LedgerEntryFormState {
    val transactionKind = kindLabel.toTransactionKind()
    val selectedCategoryId = categoryId
        ?: categories.firstOrNull { it.reviewDisplayName() == category }?.id
        ?: DefaultCategories.idForName(category, transactionKind)
        ?: LocalLedgerRepository.DEFAULT_CATEGORY_ID
    val selectedFundingAccountId = fundingAccountId
        ?: fundingAccounts.firstOrNull { it.label == fundingAccountLabel }?.id
    return LedgerEntryFormState(
        flowDirection = transactionKind.defaultFlowDirection(),
        transactionKind = transactionKind,
        amountText = amountMinorToText(amountMinor),
        transactionTimeEpochMillis = parseReviewDateTime(
            transactionTimeText,
            java.time.ZoneId.systemDefault()
        ) ?: capturedAtEpochMillis,
        merchantTitle = title,
        categoryId = selectedCategoryId,
        fundingAccountId = selectedFundingAccountId,
        creatingFundingAccount = false,
        newFundingAccountLabel = "",
        note = note.orEmpty(),
        paymentSource = sourceLabel.toReviewPaymentSource()
    )
}

private fun String?.toReviewCategoryName(categories: List<CategoryEntity>): String =
    this?.let { id ->
        DefaultCategories.nameForId(id)
            ?: categories.firstOrNull { it.id == id }?.name
    } ?: "未分类"

private fun TransactionKind.toReviewLabel(): String = when (this) {
    TransactionKind.EXPENSE -> "支出"
    TransactionKind.INCOME -> "收入"
    TransactionKind.REFUND -> "退款"
    TransactionKind.TRANSFER -> "转账"
    TransactionKind.RED_PACKET -> "红包"
    TransactionKind.REPAYMENT -> "还款"
    TransactionKind.INVESTMENT -> "理财"
    TransactionKind.FEE -> "手续费"
    TransactionKind.OTHER -> "其他"
}

private fun String.toReviewPaymentSource(): PaymentSource? = when (trim()) {
    "微信" -> PaymentSource.WECHAT
    "支付宝" -> PaymentSource.ALIPAY
    else -> null
}

@Composable
private fun EvidenceSection(entry: ReviewQueueEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("证据", fontWeight = FontWeight.SemiBold)
        Text("来源：${entry.sourceLabel}", style = MaterialTheme.typography.bodySmall)
        Text("捕获时间：${entry.captureTimeText}", style = MaterialTheme.typography.bodySmall)
        Text("解析字段", fontWeight = FontWeight.SemiBold)
        entry.parsedFields.forEach { field ->
            Text(field, style = MaterialTheme.typography.bodySmall)
        }
        Text("原始文本", fontWeight = FontWeight.SemiBold)
        Text(entry.rawEvidenceText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun IgnoredEntriesDialog(
    ignoredEntries: List<ReviewQueueIgnoredEntry>,
    onDismiss: () -> Unit,
    onRecover: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("忽略列表") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ignoredEntries.isEmpty()) {
                    Text("没有可恢复的忽略记录")
                } else {
                    ignoredEntries.forEach { ignored ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ignored.entry.title, fontWeight = FontWeight.SemiBold)
                                Text(formatAmount(ignored.entry.amountMinor))
                            }
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(
                                onClick = { onRecover(ignored.id) },
                                modifier = Modifier.testTag("recover-${ignored.id}")
                            ) {
                                Text("恢复")
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

fun confidenceLabel(confidence: ConfidenceState): String = when (confidence) {
    ConfidenceState.HIGH -> "高置信"
    ConfidenceState.NEEDS_REVIEW -> "需复核"
    ConfidenceState.DUPLICATE_SUSPECT -> "疑似重复"
}
