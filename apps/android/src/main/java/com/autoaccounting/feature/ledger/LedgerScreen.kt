package com.autoaccounting.feature.ledger

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import com.autoaccounting.ui.components.Button
import com.autoaccounting.ui.components.EmptyStatePanel
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.autoaccounting.ui.components.HomeReturnButton
import androidx.compose.material3.MaterialTheme
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.OutlinedTextField
import com.autoaccounting.ui.components.SlidePageTransition
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autoaccounting.R
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.EntryOrigin
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.LedgerEntryInput
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.ui.visual.CategoryArtwork
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlinx.coroutines.launch

@Composable
fun LedgerScreen(
    entries: List<LedgerUiEntry>,
    entryListState: LazyListState = rememberLazyListState(),
    deletedEntries: List<LedgerUiEntry> = emptyList(),
    categories: List<CategoryEntity> = emptyList(),
    fundingAccounts: List<FundingAccountEntity> = emptyList(),
    ledgerBooks: List<LedgerBookUiModel> = emptyList(),
    activeLedgerName: String = "本地账本",
    onUpdateEntry: suspend (String, LedgerEntryInput) -> Unit = { _, _ -> },
    onDeleteEntry: suspend (String) -> Unit = {},
    onRestoreEntry: suspend (String) -> Unit = {},
    onPermanentlyDeleteEntry: suspend (String) -> Unit = {},
    onPurgeExpiredEntries: suspend () -> Unit = {},
    onCreateLedger: suspend (String) -> Unit = {},
    onSelectLedger: suspend (String) -> Unit = {},
    onDeleteLedger: suspend (String) -> LedgerBookDeleteResult = {
        LedgerBookDeleteResult.Deleted
    },
    onCreateFundingAccount: suspend (String, PaymentSource?) -> Unit = { _, _ -> },
    onUpdateFundingAccount: suspend (Long, String, PaymentSource?) -> Unit = { _, _, _ -> },
    onDeleteFundingAccount: suspend (Long) -> FundingAccountDeleteResult = {
        FundingAccountDeleteResult.Deleted
    },
    onNavigateHome: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var view by remember { mutableStateOf(LedgerView.LIST) }
    var selectedEntryId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val selectedEntry = remember(entries, selectedEntryId) {
        entries.firstOrNull { it.id == selectedEntryId }
    }
    val page = remember(view, selectedEntryId) { LedgerPage(view, selectedEntryId) }

    BackHandler(
        enabled = view == LedgerView.DELETED ||
            view == LedgerView.LEDGER_BOOKS ||
            view == LedgerView.FUNDING_ACCOUNTS
    ) {
        view = LedgerView.LIST
        selectedEntryId = null
    }

    LaunchedEffect(view) {
        if (view == LedgerView.DELETED) {
            onPurgeExpiredEntries()
        }
    }

    LaunchedEffect(view, selectedEntryId, selectedEntry) {
        if (
            view == LedgerView.EDIT &&
            selectedEntryId != null &&
            selectedEntry == null
        ) {
            view = LedgerView.LIST
            selectedEntryId = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SlidePageTransition(
            targetState = page,
            modifier = Modifier.fillMaxSize()
        ) { targetPage ->
            val targetEntry = remember(entries, targetPage.selectedEntryId) {
                entries.firstOrNull { it.id == targetPage.selectedEntryId }
            }
            when (targetPage.view) {
                LedgerView.LIST -> LedgerList(
                    entries = entries,
                    entryListState = entryListState,
                    activeLedgerName = activeLedgerName,
                    onEntryClick = {
                        selectedEntryId = it
                        view = LedgerView.EDIT
                    },
                    onLedgerBooksClick = { view = LedgerView.LEDGER_BOOKS },
                    onFundingAccountsClick = { view = LedgerView.FUNDING_ACCOUNTS },
                    onRecentlyDeletedClick = { view = LedgerView.DELETED },
                    onNavigateHome = onNavigateHome
                )

                LedgerView.EDIT -> targetEntry?.let { entry ->
                    val initialFormState = remember(entry) { LedgerEntryFormState.from(entry) }
                    LedgerEntryForm(
                        title = "编辑账目",
                        initial = initialFormState,
                        categories = categories,
                        fundingAccounts = fundingAccounts,
                        onExit = {
                            selectedEntryId = null
                            view = LedgerView.LIST
                        },
                        onSave = { input ->
                            onUpdateEntry(entry.id, input)
                            selectedEntryId = null
                            view = LedgerView.LIST
                        },
                        onDelete = {
                            scope.launch {
                                runCatching { onDeleteEntry(entry.id) }
                                    .onSuccess {
                                        selectedEntryId = null
                                        view = LedgerView.LIST
                                        val result = snackbarHostState.showSnackbar(
                                            message = "已移入最近删除",
                                            actionLabel = "撤销"
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            onRestoreEntry(entry.id)
                                        }
                                    }
                                    .onFailure { snackbarHostState.showSnackbar(it.userMessage()) }
                            }
                        },
                        snackbarHostState = snackbarHostState
                    )
                }

                LedgerView.DELETED -> RecentlyDeletedScreen(
                    entries = deletedEntries,
                    onBack = { view = LedgerView.LIST },
                    onRestore = { id ->
                        scope.launch {
                            runCatching { onRestoreEntry(id) }
                                .onFailure { snackbarHostState.showSnackbar(it.userMessage()) }
                        }
                    },
                    onPermanentlyDelete = { id ->
                        scope.launch {
                            runCatching { onPermanentlyDeleteEntry(id) }
                                .onFailure { snackbarHostState.showSnackbar(it.userMessage()) }
                        }
                    }
                )

                LedgerView.LEDGER_BOOKS -> LedgerBookManagementScreen(
                    ledgerBooks = ledgerBooks,
                    snackbarHostState = snackbarHostState,
                    onBack = { view = LedgerView.LIST },
                    onCreateLedger = onCreateLedger,
                    onSelectLedger = onSelectLedger,
                    onDeleteLedger = onDeleteLedger
                )

                LedgerView.FUNDING_ACCOUNTS -> FundingAccountManagementScreen(
                    fundingAccounts = fundingAccounts,
                    snackbarHostState = snackbarHostState,
                    onBack = { view = LedgerView.LIST },
                    onCreateFundingAccount = onCreateFundingAccount,
                    onUpdateFundingAccount = onUpdateFundingAccount,
                    onDeleteFundingAccount = onDeleteFundingAccount
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun LedgerList(
    entries: List<LedgerUiEntry>,
    entryListState: LazyListState,
    activeLedgerName: String,
    onEntryClick: (String) -> Unit,
    onLedgerBooksClick: () -> Unit,
    onFundingAccountsClick: () -> Unit,
    onRecentlyDeletedClick: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var searchText by remember { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var sourceFilter by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf("") }

    val availableMonthKeys = remember(entries) {
        entries.map { it.monthKey }.distinct().sorted()
    }
    var monthKey by remember { mutableStateOf(latestMonthKey(entries)) }
    LaunchedEffect(availableMonthKeys) {
        if (monthKey !in availableMonthKeys) {
            monthKey = availableMonthKeys.lastOrNull() ?: latestMonthKey(entries)
        }
    }
    val monthIndex = availableMonthKeys.indexOf(monthKey)
    val summary = remember(entries, monthKey) { monthlySummary(entries, monthKey) }
    val hasCurrentMonthEntries = remember(entries, monthKey) {
        entries.any { it.monthKey == monthKey }
    }
    val hasActiveFilters = searchText.isNotBlank() ||
        sourceFilter.isNotBlank() ||
        categoryFilter.isNotBlank() ||
        kindFilter.isNotBlank()
    val filteredEntries = remember(
        entries,
        monthKey,
        searchText,
        sourceFilter,
        categoryFilter,
        kindFilter
    ) {
        val searchQuery = searchText.trim()
        val sourceQuery = sourceFilter.trim()
        val categoryQuery = categoryFilter.trim()
        val kindQuery = kindFilter.trim()
        entries
            .asSequence()
            .filter { it.monthKey == monthKey }
            .filter {
                val searchableText = "${it.title} ${it.note.orEmpty()} ${it.category}"
                searchQuery.isBlank() || searchableText.contains(searchQuery, ignoreCase = true)
            }
            .filter { sourceQuery.isBlank() || it.sourceLabel.contains(sourceQuery) }
            .filter { categoryQuery.isBlank() || it.category.contains(categoryQuery) }
            .filter { kindQuery.isBlank() || it.kindLabel.contains(kindQuery) }
            .sortedByDescending { it.transactionTimeEpochMillis }
            .toList()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    activeLedgerName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.testTag(LedgerTestTags.MORE_MENU)
                        ) {
                            Text("更多")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                modifier = Modifier.testTag(LedgerTestTags.MANAGE_LEDGERS),
                                text = { Text("账本管理") },
                                onClick = {
                                    showMenu = false
                                    onLedgerBooksClick()
                                }
                            )
                            DropdownMenuItem(
                                modifier = Modifier.testTag(LedgerTestTags.MANAGE_FUNDING_ACCOUNTS),
                                text = { Text("资金账户") },
                                onClick = {
                                    showMenu = false
                                    onFundingAccountsClick()
                                }
                            )
                            DropdownMenuItem(
                                modifier = Modifier.testTag(LedgerTestTags.RECENTLY_DELETED),
                                text = { Text("最近删除") },
                                onClick = {
                                    showMenu = false
                                    onRecentlyDeletedClick()
                                }
                            )
                        }
                    }
                    HomeReturnButton(onClick = onNavigateHome)
                }
            }
            LedgerSummary(summary)
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text("搜索商户或备注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = { showFilters = !showFilters }) { Text("筛选") }
            if (showFilters) {
                FilterPanel(
                    sourceFilter = sourceFilter,
                    categoryFilter = categoryFilter,
                    kindFilter = kindFilter,
                    onSourceChange = { sourceFilter = it },
                    onCategoryChange = { categoryFilter = it },
                    onKindChange = { kindFilter = it }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { monthKey = availableMonthKeys[monthIndex - 1] },
                    enabled = monthIndex > 0
                ) {
                    Text("上一月")
                }
                Text(
                    "$monthKey 明细",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(
                    onClick = { monthKey = availableMonthKeys[monthIndex + 1] },
                    enabled = monthIndex in 0 until availableMonthKeys.lastIndex
                ) {
                    Text("下一月")
                }
            }
            LazyColumn(
                state = entryListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag(LedgerTestTags.ENTRY_LIST),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (filteredEntries.isEmpty()) {
                    item {
                        if (!hasCurrentMonthEntries && !hasActiveFilters) {
                            EmptyStatePanel("当前没有已确认账目")
                        } else {
                            EmptyStatePanel("没有符合当前筛选条件的账目")
                        }
                    }
                } else {
                    items(filteredEntries, key = { it.id }) { entry ->
                        LedgerEntryRow(entry) { onEntryClick(entry.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerSummary(summary: MonthlySummary) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryChip("本月支出 ${formatMoney(summary.expenseMinor)}", Modifier.weight(1f))
        SummaryChip("本月收入 ${formatMoney(summary.incomeMinor)}", Modifier.weight(1f))
        SummaryChip("净额\n${formatSignedMoney(summary.netMinor)}", Modifier.weight(1f))
    }
}

@Composable
private fun SummaryChip(text: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FilterPanel(
    sourceFilter: String,
    categoryFilter: String,
    kindFilter: String,
    onSourceChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onKindChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(sourceFilter, onSourceChange, label = { Text("来源") }, singleLine = true)
        OutlinedTextField(categoryFilter, onCategoryChange, label = { Text("分类") }, singleLine = true)
        OutlinedTextField(kindFilter, onKindChange, label = { Text("交易类型") }, singleLine = true)
    }
}

@Composable
private fun LedgerEntryRow(entry: LedgerUiEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryArtwork(
                categoryId = entry.categoryId,
                categoryName = entry.category,
                transactionKind = entry.transactionKind,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.title.ifBlank { "未填写标题" }, fontWeight = FontWeight.SemiBold)
                Text("${entry.category} · ${entry.sourceLabel} · ${entry.transactionTimeText}")
            }
            Spacer(Modifier.width(12.dp))
            val signedAmount = when (entry.flowType) {
                LedgerFlowType.INCOME -> "+${formatMoney(entry.amountMinor)}"
                LedgerFlowType.EXPENSE -> "-${formatMoney(entry.amountMinor)}"
                LedgerFlowType.NEUTRAL -> formatMoney(entry.amountMinor)
            }
            Text(signedAmount, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun <T> SelectionMenu(
    label: String,
    selected: T,
    options: List<T>,
    itemLabel: (T) -> String,
    leadingContent: (@Composable (T) -> Unit)? = null,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    leadingContent?.let {
                        it(selected)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("$label：${itemLabel(selected)}")
                }
                Text("⌄", style = MaterialTheme.typography.titleMedium)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            shape = RoundedCornerShape(8.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            leadingContent?.let {
                                it(option)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(itemLabel(option))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    }
                )
            }
        }
    }
}

private enum class LedgerView {
    LIST,
    EDIT,
    DELETED,
    LEDGER_BOOKS,
    FUNDING_ACCOUNTS
}

private data class LedgerPage(
    val view: LedgerView,
    val selectedEntryId: String?
)

internal data class LedgerEntryFormState(
    val flowDirection: FlowDirection,
    val transactionKind: TransactionKind,
    val amountText: String,
    val transactionTimeEpochMillis: Long,
    val merchantTitle: String,
    val categoryId: String,
    val fundingAccountId: Long?,
    val creatingFundingAccount: Boolean,
    val newFundingAccountLabel: String,
    val note: String,
    val paymentSource: PaymentSource?
) {
    fun toInput(nowEpochMillis: Long): LedgerEntryInput {
        val normalizedAmount = amountText.trim()
        require(AMOUNT_PATTERN.matches(normalizedAmount)) {
            "金额必须大于 0，且最多保留两位小数"
        }
        val amountMinor = try {
            BigDecimal(normalizedAmount).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("金额超出支持范围")
        }
        require(amountMinor > 0) { "金额必须大于 0" }
        require(transactionTimeEpochMillis <= nowEpochMillis) { "交易时间不能晚于当前时间" }
        if (creatingFundingAccount) {
            require(newFundingAccountLabel.isNotBlank()) { "请输入资金账户名称" }
        }
        return LedgerEntryInput(
            flowDirection = flowDirection,
            transactionKind = transactionKind,
            amountMinor = amountMinor,
            transactionTimeEpochMillis = transactionTimeEpochMillis,
            merchantTitle = merchantTitle,
            categoryId = categoryId,
            fundingAccountId = if (creatingFundingAccount) null else fundingAccountId,
            newFundingAccountLabel = if (creatingFundingAccount) newFundingAccountLabel else null,
            note = note,
            paymentSource = paymentSource
        )
    }

    companion object {
        val Saver = listSaver<LedgerEntryFormState, Any>(
            save = { state ->
                listOf(
                    state.flowDirection.name,
                    state.transactionKind.name,
                    state.amountText,
                    state.transactionTimeEpochMillis,
                    state.merchantTitle,
                    state.categoryId,
                    state.fundingAccountId ?: NO_FUNDING_ACCOUNT_ID,
                    state.creatingFundingAccount,
                    state.newFundingAccountLabel,
                    state.note,
                    state.paymentSource?.name.orEmpty()
                )
            },
            restore = { values ->
                LedgerEntryFormState(
                    flowDirection = FlowDirection.valueOf(values[0] as String),
                    transactionKind = TransactionKind.valueOf(values[1] as String),
                    amountText = values[2] as String,
                    transactionTimeEpochMillis = values[3] as Long,
                    merchantTitle = values[4] as String,
                    categoryId = values[5] as String,
                    fundingAccountId = (values[6] as Long)
                        .takeUnless { it == NO_FUNDING_ACCOUNT_ID },
                    creatingFundingAccount = values[7] as Boolean,
                    newFundingAccountLabel = values[8] as String,
                    note = values[9] as String,
                    paymentSource = (values[10] as String)
                        .takeIf(String::isNotEmpty)
                        ?.let(PaymentSource::valueOf)
                )
            }
        )

        fun newEntry(nowEpochMillis: Long = System.currentTimeMillis()): LedgerEntryFormState =
            LedgerEntryFormState(
                flowDirection = FlowDirection.OUTFLOW,
                transactionKind = TransactionKind.EXPENSE,
                amountText = "",
                transactionTimeEpochMillis = nowEpochMillis,
                merchantTitle = "",
                categoryId = LocalLedgerRepository.DEFAULT_CATEGORY_ID,
                fundingAccountId = null,
                creatingFundingAccount = false,
                newFundingAccountLabel = "",
                note = "",
                paymentSource = null
            )

        fun from(entry: LedgerUiEntry): LedgerEntryFormState = LedgerEntryFormState(
            flowDirection = entry.flowDirection,
            transactionKind = entry.transactionKind,
            amountText = BigDecimal(entry.amountMinor).movePointLeft(2).toPlainString(),
            transactionTimeEpochMillis = entry.transactionTimeEpochMillis,
            merchantTitle = entry.title,
            categoryId = entry.categoryId ?: LocalLedgerRepository.DEFAULT_CATEGORY_ID,
            fundingAccountId = entry.fundingAccountId,
            creatingFundingAccount = false,
            newFundingAccountLabel = "",
            note = entry.note.orEmpty(),
            paymentSource = entry.paymentSource
        )

        private const val NO_FUNDING_ACCOUNT_ID = Long.MIN_VALUE
    }
}

internal fun showDateTimePicker(
    context: android.content.Context,
    currentEpochMillis: Long,
    onSelected: (Long) -> Unit
) {
    val zoneId = ZoneId.systemDefault()
    val current = Instant.ofEpochMilli(currentEpochMillis).atZone(zoneId)
    val dateDialog = DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    onSelected(
                        LocalDateTime.of(year, month + 1, day, hour, minute)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli()
                    )
                },
                current.hour,
                current.minute,
                true
            ).show()
        },
        current.year,
        current.monthValue - 1,
        current.dayOfMonth
    )
    dateDialog.datePicker.maxDate = System.currentTimeMillis()
    dateDialog.show()
}

internal fun LedgerUiEntry.remainingRetentionDays(nowEpochMillis: Long): Long {
    val deletedAt = deletedAtEpochMillis ?: return 0
    val remaining = deletedAt + LocalLedgerRepository.DELETED_RETENTION_MILLIS - nowEpochMillis
    return max(0, (remaining + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY)
}

internal fun formatLedgerEpoch(epochMillis: Long): String =
    if (epochMillis <= 0) "—" else LEDGER_DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis))

internal fun PaymentSource?.labelOrNone(): String = when (this) {
    PaymentSource.WECHAT -> "微信"
    PaymentSource.ALIPAY -> "支付宝"
    null -> "未指定"
}

internal fun FlowDirection.label(): String = when (this) {
    FlowDirection.INFLOW -> "流入"
    FlowDirection.OUTFLOW -> "流出"
    FlowDirection.NEUTRAL -> "不计收支"
}

internal fun TransactionKind.label(): String = when (this) {
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

internal fun EntryOrigin.label(): String = when (this) {
    EntryOrigin.MANUAL -> "手动录入"
    EntryOrigin.NOTIFICATION -> "通知捕获"
    EntryOrigin.ACCESSIBILITY_AUTO -> "自动记账"
    EntryOrigin.BILL_SYNC -> "补录账单"
    EntryOrigin.DUPLICATE_MERGE -> "重复合并"
    EntryOrigin.LEGACY_CAPTURE -> "旧版采集（方式未知）"
}

internal fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: "操作失败，请重试"

private val AMOUNT_PATTERN = Regex("^\\d+(\\.\\d{1,2})?$")
private val LEDGER_DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
