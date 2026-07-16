package com.autoaccounting.feature.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.autoaccounting.ui.components.Button
import com.autoaccounting.ui.components.EmptyStatePanel
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.OutlinedTextField
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.billsync.BillSyncSessionController
import com.autoaccounting.feature.billsync.BillSyncSessionPhase
import com.autoaccounting.feature.billsync.BillSyncSessionState
import com.autoaccounting.feature.billsync.BillSyncSessions
import com.autoaccounting.feature.billsync.BillSyncSource
import com.autoaccounting.feature.categorization.AiCategorizationClient
import com.autoaccounting.feature.categorization.AiCategorizationGateway
import com.autoaccounting.feature.categorization.AiCategorizationResult
import com.autoaccounting.feature.categorization.AiCategorizationSettings
import com.autoaccounting.feature.categorization.AiCategorizationSkipReason
import com.autoaccounting.feature.categorization.CategorizationRule
import com.autoaccounting.feature.monitoring.ContinuousMonitoringAction
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.monitoring.reduceContinuousMonitoringState
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
    onCategorizationRuleRequested: (CategorizationRule) -> Unit = {},
    accountSession: AccountSession? = null,
    aiSettings: AiCategorizationSettings = AiCategorizationSettings(),
    aiCategorizationGateway: AiCategorizationGateway? = null,
    billSyncAccessibilityAccessGranted: Boolean = false,
    onOpenBillSyncAccessibilitySettings: () -> Unit = {},
    onLaunchBillSyncSource: (BillSyncSource) -> Boolean = { false },
    openPendingEntryId: String? = null,
    openPendingEntryRequestId: Long = 0,
    billSyncSessionController: BillSyncSessionController = BillSyncSessions.controller,
    continuousMonitoringState: ContinuousMonitoringState = ContinuousMonitoringState(),
    continuousMonitoringPermissionHealth: ContinuousMonitoringPermissionHealth =
        ContinuousMonitoringPermissionHealth(),
    onContinuousMonitoringStateChange: (ContinuousMonitoringState) -> Unit = {},
    onNavigateHome: () -> Unit = {}
) {
    var state by remember { mutableStateOf(initialState) }
    ReviewQueueScreen(
        state = state,
        onStateChange = { state = it },
        targetLedgerName = targetLedgerName,
        modifier = modifier,
        onCategorizationRuleRequested = onCategorizationRuleRequested,
        accountSession = accountSession,
        aiSettings = aiSettings,
        aiCategorizationGateway = aiCategorizationGateway,
        billSyncAccessibilityAccessGranted = billSyncAccessibilityAccessGranted,
        onOpenBillSyncAccessibilitySettings = onOpenBillSyncAccessibilitySettings,
        onLaunchBillSyncSource = onLaunchBillSyncSource,
        openPendingEntryId = openPendingEntryId,
        openPendingEntryRequestId = openPendingEntryRequestId,
        billSyncSessionController = billSyncSessionController,
        continuousMonitoringState = continuousMonitoringState,
        continuousMonitoringPermissionHealth = continuousMonitoringPermissionHealth,
        onContinuousMonitoringStateChange = onContinuousMonitoringStateChange,
        onNavigateHome = onNavigateHome
    )
}

@Composable
fun ReviewQueueScreen(
    state: ReviewQueueState,
    onStateChange: (ReviewQueueState) -> Unit,
    targetLedgerName: String = "默认账本",
    modifier: Modifier = Modifier,
    onCategorizationRuleRequested: (CategorizationRule) -> Unit = {},
    accountSession: AccountSession? = null,
    aiSettings: AiCategorizationSettings = AiCategorizationSettings(),
    aiCategorizationGateway: AiCategorizationGateway? = null,
    billSyncAccessibilityAccessGranted: Boolean = false,
    onOpenBillSyncAccessibilitySettings: () -> Unit = {},
    onLaunchBillSyncSource: (BillSyncSource) -> Boolean = { false },
    openPendingEntryId: String? = null,
    openPendingEntryRequestId: Long = 0,
    billSyncSessionController: BillSyncSessionController = BillSyncSessions.controller,
    continuousMonitoringState: ContinuousMonitoringState = ContinuousMonitoringState(),
    continuousMonitoringPermissionHealth: ContinuousMonitoringPermissionHealth =
        ContinuousMonitoringPermissionHealth(),
    onContinuousMonitoringStateChange: (ContinuousMonitoringState) -> Unit = {},
    onNavigateHome: () -> Unit = {}
) {
    var editingEntry by remember { mutableStateOf<ReviewQueueEntry?>(null) }
    var pendingRuleSave by remember { mutableStateOf<PendingCategoryRuleSave?>(null) }
    var showIgnoredList by remember { mutableStateOf(false) }
    var showBillSyncDialog by remember { mutableStateOf(false) }
    val billSyncSessionState by billSyncSessionController.state.collectAsState()
    var handledBillSyncSessionId by remember { mutableStateOf<Long?>(null) }
    var showPostBillSyncMonitoringPrompt by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(openPendingEntryRequestId, openPendingEntryId, state.pendingEntries) {
        if (openPendingEntryRequestId > 0 && openPendingEntryId != null) {
            editingEntry = state.pendingEntries.firstOrNull { it.id == openPendingEntryId }
        }
    }

    fun dispatch(action: ReviewQueueAction) {
        onStateChange(reduceReviewQueue(state, action))
    }

    fun applyEdit(pending: PendingCategoryRuleSave) {
        dispatch(pending.edit.toSaveAction(pending.entry.id))
        syncMessage = pending.edit.category.trim()
        pendingRuleSave = null
        editingEntry = null
    }

    fun startBillSync(source: BillSyncSource) {
        if (!billSyncAccessibilityAccessGranted) {
            syncMessage = "请先授权账单同步无障碍服务"
            onOpenBillSyncAccessibilitySettings()
            return
        }
        billSyncSessionController.start(source)
        if (!onLaunchBillSyncSource(source)) {
            billSyncSessionController.fail("未找到${source.label}，无法打开账单页面")
        }
    }

    LaunchedEffect(billSyncSessionState.sessionId, billSyncSessionState.phase) {
        if (
            billSyncSessionState.phase != BillSyncSessionPhase.Completed ||
            handledBillSyncSessionId == billSyncSessionState.sessionId
        ) {
            return@LaunchedEffect
        }
        val result = billSyncSessionState.result ?: return@LaunchedEffect
        val nextState = (result.mergedEntries + result.createdEntries)
            .fold(state) { currentState, entry ->
                reduceReviewQueue(currentState, ReviewQueueAction.AddPending(entry))
            }
        onStateChange(nextState)
        handledBillSyncSessionId = billSyncSessionState.sessionId
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

    LaunchedEffect(syncMessage) {
        val message = syncMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            duration = SnackbarDuration.Short
        )
        syncMessage = null
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        ReviewQueueContent(
            state = state,
            targetLedgerName = targetLedgerName,
            onAction = ::dispatch,
            onEdit = { editingEntry = it },
            onShowIgnoredList = { showIgnoredList = true },
            onStartBillSync = {
                billSyncSessionController.reset()
                showBillSyncDialog = true
            },
            showPostBillSyncMonitoringPrompt = showPostBillSyncMonitoringPrompt &&
                !continuousMonitoringState.enabled,
            onEnableContinuousMonitoring = {
                val nextMonitoringState = reduceContinuousMonitoringState(
                    continuousMonitoringState,
                    ContinuousMonitoringAction.Enable(continuousMonitoringPermissionHealth)
                )
                onContinuousMonitoringStateChange(nextMonitoringState)
                if (nextMonitoringState.enabled) {
                    showPostBillSyncMonitoringPrompt = false
                } else {
                    syncMessage = "请先授权自动记账无障碍服务"
                }
            },
            onDismissContinuousMonitoringPrompt = {
                showPostBillSyncMonitoringPrompt = false
            },
            onNavigateHome = onNavigateHome,
            modifier = Modifier.padding(innerPadding)
        )
    }

    editingEntry?.let { entry ->
        ReviewEditDialog(
            entry = entry,
            onDismiss = { editingEntry = null },
            onConfirm = {
                dispatch(ReviewQueueAction.Confirm(entry.id))
                editingEntry = null
            },
            onIgnore = {
                dispatch(ReviewQueueAction.Ignore(entry.id))
                editingEntry = null
            },
            onAiSuggest = { draft ->
                val gateway = aiCategorizationGateway
                    ?: return@ReviewEditDialog AiCategorizationResult(
                        skipReason = AiCategorizationSkipReason.REQUIRES_SIGNED_IN_ACCOUNT
                    )
                AiCategorizationClient(gateway).suggestCategory(
                    entry = draft,
                    session = accountSession,
                    settings = aiSettings
                )
            },
            onSave = { title, amountText, timeText, transactionKind, category, fundingAccount, note ->
                val edit = PendingReviewEdit(
                    title = title,
                    amountText = amountText,
                    timeText = timeText,
                    transactionKind = transactionKind,
                    category = category,
                    fundingAccount = fundingAccount,
                    note = note
                )
                if (entry.hasCategoryCorrection(edit.category)) {
                    pendingRuleSave = PendingCategoryRuleSave(entry, edit)
                } else {
                    dispatch(edit.toSaveAction(entry.id))
                    editingEntry = null
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

    if (showBillSyncDialog) {
        BillSyncDialog(
            sessionState = billSyncSessionState,
            onSourceSelected = ::startBillSync,
            onCancel = {
                billSyncSessionController.cancel()
                showBillSyncDialog = false
            },
            onDismiss = {
                if (billSyncSessionState.phase == BillSyncSessionPhase.Completed) {
                    showPostBillSyncMonitoringPrompt = true
                }
                showBillSyncDialog = false
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
    onStartBillSync: () -> Unit,
    showPostBillSyncMonitoringPrompt: Boolean,
    onEnableContinuousMonitoring: () -> Unit,
    onDismissContinuousMonitoringPrompt: () -> Unit,
    onNavigateHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sortedEntries = state.sortedPendingEntries

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "待确认队列",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "确认后记入「$targetLedgerName」，误操作可撤销",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onShowIgnoredList) {
                    Text("忽略列表")
                }
                HomeReturnButton(onClick = onNavigateHome)
            }
        }

        ReviewSummary(state, onStartBillSync)

        if (showPostBillSyncMonitoringPrompt) {
            PostBillSyncMonitoringPrompt(
                onEnable = onEnableContinuousMonitoring,
                onDismiss = onDismissContinuousMonitoringPrompt
            )
        }

        ReviewEntryGroup(
            title = "快速确认",
            subtitle = "所有待确认记录，可快速滑动或点确认",
            entries = sortedEntries,
            emptyText = "快速确认列表为空",
            onAction = onAction,
            onEdit = onEdit
        )
    }
}

@Composable
private fun PostBillSyncMonitoringPrompt(
    onEnable: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("开启自动记账", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("支付完成后自动识别结果页并生成待确认记录，可随时关闭。", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onEnable,
                    modifier = Modifier.testTag("enable-automatic-capture-after-sync")
                ) {
                    Text("开启自动记账")
                }
                OutlinedButton(onClick = onDismiss) {
                    Text("暂不开启")
                }
            }
        }
    }
}

@Composable
private fun ReviewSummary(
    state: ReviewQueueState,
    onStartBillSync: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SummaryChip("待确认 ${state.pendingEntries.size}", Modifier.weight(1f))
            SummaryChip("疑似重复 ${state.duplicateSuspectCount}", Modifier.weight(1f))
            SummaryChip("今日新增 ${state.todaysNewlyCapturedCount}", Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SummaryChip("已确认 ${state.confirmedEntries.size}", Modifier.weight(1f))
            Button(onClick = onStartBillSync, modifier = Modifier.weight(1f)) {
                Text("账单同步")
            }
        }
    }
}

@Composable
private fun SummaryChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun ReviewEntryGroup(
    title: String,
    subtitle: String,
    entries: List<ReviewQueueEntry>,
    emptyText: String,
    onAction: (ReviewQueueAction) -> Unit,
    onEdit: (ReviewQueueEntry) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
        if (entries.isEmpty()) {
            EmptyStatePanel(emptyText)
        } else {
            entries.forEach { entry ->
                key(entry.id) {
                    ReviewEntryRow(
                        entry = entry,
                        onConfirm = { onAction(ReviewQueueAction.Confirm(entry.id)) },
                        onIgnore = { onAction(ReviewQueueAction.Ignore(entry.id)) },
                        onEdit = { onEdit(entry) }
                    )
                }
            }
        }
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
                shape = RoundedCornerShape(8.dp)
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
                            transactionKind = entry.kindLabel.toCategoryTransactionKind(),
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${entry.sourceLabel} · ${entry.kindLabel} · ${entry.category}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    entry.captureReasonLabel,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    confidenceLabel(entry.confidence),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
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

@Composable
private fun BillSyncDialog(
    sessionState: BillSyncSessionState,
    onSourceSelected: (BillSyncSource) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val sourceSelection = sessionState.phase == BillSyncSessionPhase.Idle
    AlertDialog(
        onDismissRequest = {
            if (sessionState.isActive) onCancel() else onDismiss()
        },
        title = {
            Text(if (sourceSelection) "选择同步来源" else "账单同步进度")
        },
        text = {
            if (sourceSelection) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("选择后会读取当前账单页，并把新交易加入待确认队列。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSourceSelected(BillSyncSource.WeChat) }) {
                            Text("微信")
                        }
                        Button(onClick = { onSourceSelected(BillSyncSource.Alipay) }) {
                            Text("支付宝")
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sessionState.steps.forEach { step ->
                        Text(syncStepDisplayLabel(step.label))
                    }
                    sessionState.message?.let { message ->
                        Text(message)
                    }
                    sessionState.result?.summary?.let { summary ->
                        Text(summary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {
            if (
                sessionState.phase == BillSyncSessionPhase.Completed ||
                sessionState.phase == BillSyncSessionPhase.Failed ||
                sessionState.phase == BillSyncSessionPhase.Cancelled
            ) {
                Button(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        },
        dismissButton = {
            if (sourceSelection) {
                TextButton(onClick = onCancel) {
                    Text("取消")
                }
            } else if (sessionState.isActive) {
                TextButton(onClick = onCancel) {
                    Text("取消同步")
                }
            }
        }
    )
}

private fun syncStepDisplayLabel(label: String): String =
    if (label == "完成") "同步完成" else label

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
    val category: String,
    val fundingAccount: String,
    val note: String
) {
    fun toSaveAction(entryId: String): ReviewQueueAction.SaveEdit = ReviewQueueAction.SaveEdit(
        entryId = entryId,
        title = title,
        amountText = amountText,
        timeText = timeText,
        transactionKind = transactionKind,
        category = category,
        fundingAccount = fundingAccount,
        note = note
    )
}

private fun ReviewQueueEntry.hasCategoryCorrection(category: String): Boolean {
    return category.trim().isNotBlank() && category.trim() != this.category
}

private fun String.toCategoryTransactionKind(): TransactionKind = when (trim()) {
    "收入" -> TransactionKind.INCOME
    "退款" -> TransactionKind.REFUND
    else -> TransactionKind.EXPENSE
}

@Composable
private fun ReviewEditDialog(
    entry: ReviewQueueEntry,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    onAiSuggest: (ReviewQueueEntry) -> AiCategorizationResult,
    onSave: (
        title: String,
        amountText: String,
        timeText: String,
        transactionKind: String,
        category: String,
        fundingAccount: String,
        note: String
    ) -> Unit
) {
    var title by remember(entry.id) { mutableStateOf(entry.title) }
    var amountText by remember(entry.id) { mutableStateOf(amountMinorToText(entry.amountMinor)) }
    var timeText by remember(entry.id) { mutableStateOf(entry.transactionTimeText) }
    var transactionKind by remember(entry.id) { mutableStateOf(entry.kindLabel) }
    var category by remember(entry.id) { mutableStateOf(entry.category) }
    var fundingAccount by remember(entry.id) { mutableStateOf(entry.fundingAccountLabel) }
    var note by remember(entry.id) { mutableStateOf(entry.note.orEmpty()) }
    var showEvidence by remember(entry.id) { mutableStateOf(false) }
    var amountError by remember(entry.id) { mutableStateOf<String?>(null) }
    var aiMessage by remember(entry.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑待确认记录") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextButton(onClick = { showEvidence = !showEvidence }) {
                    Text(if (showEvidence) "收起证据" else "查看证据")
                }
                if (showEvidence) {
                    EvidenceSection(entry)
                }
                OutlinedButton(
                    onClick = {
                        val amountMinor = parseReviewAmountMinor(amountText)
                        if (amountMinor == null) {
                            amountError = "金额格式不正确"
                            return@OutlinedButton
                        }
                        val result = onAiSuggest(
                            entry.copy(
                                title = title,
                                amountMinor = amountMinor,
                                transactionTimeText = timeText,
                                kindLabel = transactionKind,
                                category = category,
                                fundingAccountLabel = fundingAccount,
                                note = note.ifBlank { null }
                            )
                        )
                        result.suggestion?.let { suggestion ->
                            category = suggestion.category
                            aiMessage = "AI 建议：${suggestion.category}"
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
                aiMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("商户/标题") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit-title")
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it
                        amountError = null
                    },
                    label = { Text("金额") },
                    singleLine = true,
                    isError = amountError != null,
                    supportingText = {
                        amountError?.let {
                            Text(it)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit-amount")
                )
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it },
                    label = { Text("交易时间") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit-time")
                )
                OutlinedTextField(
                    value = transactionKind,
                    onValueChange = { transactionKind = it },
                    label = { Text("交易类型") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit-kind")
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("分类") },
                    leadingIcon = {
                        CategoryArtwork(
                            categoryName = category,
                            transactionKind = transactionKind.toCategoryTransactionKind(),
                            modifier = Modifier.size(32.dp)
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit-category")
                )
                OutlinedTextField(
                    value = fundingAccount,
                    onValueChange = { fundingAccount = it },
                    label = { Text("资金账户") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit-funding-account")
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit-note")
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onIgnore) {
                        Text("忽略此条")
                    }
                    Button(onClick = onConfirm) {
                        Text("确认入账")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (parseReviewAmountMinor(amountText) == null) {
                            amountError = "金额格式不正确"
                            return@Button
                        }
                        onSave(
                            title,
                            amountText,
                            timeText,
                            transactionKind,
                            category,
                            fundingAccount,
                            note
                        )
                    }
                ) {
                    Text("保存")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
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
