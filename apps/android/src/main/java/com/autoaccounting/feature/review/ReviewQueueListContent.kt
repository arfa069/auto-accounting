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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import com.autoaccounting.ui.components.Button
import com.autoaccounting.ui.components.EmptyStatePanel
import com.autoaccounting.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.ui.components.HomeReturnButton
import com.autoaccounting.ui.visual.CategoryArtwork
import kotlin.math.abs

@Composable
internal fun ReviewQueueListContent(
    state: ReviewQueueState,
    targetLedgerName: String,
    actions: ReviewQueueListActions,
    modifier: Modifier = Modifier
) {
    val sortedEntries = remember(state.pendingEntries) { state.sortedPendingEntries }

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
                onShowIgnoredList = actions.onShowIgnoredList,
                onNavigateHome = actions.onNavigateHome
            )
        }

        item(key = "review-summary") {
            ReviewSummaryCard(state)
        }

        item(key = "bill-import") {
            BillImportEntry(onClick = actions.onOpenBillImport)
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
                    onConfirm = { actions.onAction(ReviewQueueAction.Confirm(entry.id)) },
                    onIgnore = { actions.onAction(ReviewQueueAction.Ignore(entry.id)) },
                    onEdit = { actions.onEdit(entry) }
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
            ReviewEntryCard(
                entry = entry,
                onConfirm = onConfirm,
                onIgnore = onIgnore,
                onEdit = onEdit
            )
        }
    )
}

@Composable
private fun ReviewEntryCard(
    entry: ReviewQueueEntry,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("detail-${entry.id}")
            .clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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





fun confidenceLabel(confidence: ConfidenceState): String = when (confidence) {
    ConfidenceState.HIGH -> "高置信"
    ConfidenceState.NEEDS_REVIEW -> "需复核"
    ConfidenceState.DUPLICATE_SUSPECT -> "疑似重复"
}
