package com.autoaccounting.feature.ledger

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.autoaccounting.ui.components.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.autoaccounting.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.DefaultCategories
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.LedgerEntryInput
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.EntryOrigin
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
internal fun LedgerEntryDetail(
    entry: LedgerUiEntry,
    fundingAccountLabel: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("返回账本") }
        Text(entry.title.ifBlank { "未填写标题" }, style = MaterialTheme.typography.headlineSmall)
        DetailLine("金额", formatMoney(entry.amountMinor))
        DetailLine("资金方向", entry.flowDirection.label())
        DetailLine("交易类型", entry.kindLabel)
        DetailLine("交易时间", entry.transactionTimeText)
        DetailLine("分类", entry.category)
        DetailLine("资金账户", fundingAccountLabel ?: "未选择")
        DetailLine("支付来源", entry.paymentSource.labelOrNone())
        DetailLine("备注", entry.note ?: "未填写")
        DetailLine("录入方式", entry.entryOrigin.label())
        DetailLine("创建/首次确认", formatLedgerEpoch(entry.confirmedAtEpochMillis))
        DetailLine("最后修改", formatLedgerEpoch(entry.updatedAtEpochMillis))
        if (entry.originalCaptureSource != null || !entry.evidenceSummary.isNullOrBlank()) {
            Text("原始采集信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            DetailLine("原始来源", entry.originalCaptureSource.labelOrNone())
            DetailLine("原待确认 ID", entry.originPendingEntryId ?: "无")
            DetailLine("采集证据", entry.evidenceSummary ?: "无")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onEdit) { Text("编辑") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("删除") }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这笔账？") },
            text = { Text("账目将移入最近删除，可在 30 天内恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) { Text("移入最近删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
}

@Composable
internal fun ManualLedgerEntryScreen(
    categories: List<CategoryEntity>,
    fundingAccounts: List<FundingAccountEntity>,
    onExit: () -> Unit,
    onCreateEntry: suspend (LedgerEntryInput) -> Unit,
    modifier: Modifier = Modifier
) {
    val initial = remember { LedgerEntryFormState.newEntry() }
    val snackbarHostState = remember { SnackbarHostState() }

    Box(modifier = modifier.fillMaxSize()) {
        LedgerEntryForm(
            title = "新增一笔",
            initial = initial,
            categories = categories,
            fundingAccounts = fundingAccounts,
            onExit = onExit,
            onSave = onCreateEntry,
            snackbarHostState = snackbarHostState
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
internal fun LedgerEntryForm(
    title: String,
    initial: LedgerEntryFormState,
    categories: List<CategoryEntity>,
    fundingAccounts: List<FundingAccountEntity>,
    onExit: () -> Unit,
    onSave: suspend (LedgerEntryInput) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    var state by remember(initial) { mutableStateOf(initial) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isDirty = state != initial

    fun requestExit() {
        if (isDirty) confirmDiscard = true else onExit()
    }

    BackHandler { requestExit() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("资金方向")
        FlowDirectionSelector(
            selected = state.flowDirection,
            onSelected = { direction -> state = state.copy(flowDirection = direction) }
        )
        SelectionMenu(
            label = "交易类型",
            selected = state.transactionKind,
            options = TransactionKind.entries,
            itemLabel = TransactionKind::label,
            onSelected = { state = state.copy(transactionKind = it) }
        )
        OutlinedTextField(
            value = state.amountText,
            onValueChange = { state = state.copy(amountText = it) },
            label = { Text("金额（CNY）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = {
                showDateTimePicker(context, state.transactionTimeEpochMillis) {
                    state = state.copy(transactionTimeEpochMillis = it)
                }
            }
        ) { Text("交易时间 ${formatLedgerEpoch(state.transactionTimeEpochMillis)}") }
        OutlinedTextField(
            value = state.merchantTitle,
            onValueChange = { state = state.copy(merchantTitle = it) },
            label = { Text("商户/标题（可选）") },
            modifier = Modifier.fillMaxWidth()
        )
        val categoryOptions = remember(categories, state.flowDirection, state.transactionKind) {
            val matchingCategories = categories.filter { category ->
                when {
                    category.kind == null -> true
                    state.transactionKind == TransactionKind.REFUND ->
                        category.kind == TransactionKind.REFUND
                    state.flowDirection == FlowDirection.INFLOW ->
                        category.kind == TransactionKind.INCOME
                    state.flowDirection == FlowDirection.OUTFLOW ->
                        category.kind == TransactionKind.EXPENSE
                    else -> true
                }
            }
            (matchingCategories + CategoryEntity(
                id = LocalLedgerRepository.DEFAULT_CATEGORY_ID,
                name = "未分类",
                kind = null,
                sortOrder = Int.MAX_VALUE,
                isSystem = true,
                createdAtEpochMillis = 0
            )).distinctBy { it.id }
        }
        LaunchedEffect(categoryOptions, state.categoryId) {
            if (categoryOptions.none { it.id == state.categoryId }) {
                state = state.copy(categoryId = LocalLedgerRepository.DEFAULT_CATEGORY_ID)
            }
        }
        SelectionMenu(
            label = "分类",
            selected = state.categoryId,
            options = categoryOptions.map { it.id },
            itemLabel = { id ->
                DefaultCategories.nameForId(id)
                    ?: categoryOptions.firstOrNull { it.id == id }?.name
                    ?: "未分类"
            },
            leadingContent = { id ->
                val category = categoryOptions.firstOrNull { it.id == id }
                CategoryArtwork(
                    categoryId = id,
                    categoryName = category?.name,
                    transactionKind = category?.kind,
                    modifier = Modifier.size(32.dp)
                )
            },
            onSelected = { state = state.copy(categoryId = it) }
        )
        SelectionMenu(
            label = "支付来源",
            selected = state.paymentSource,
            options = listOf(null, PaymentSource.WECHAT, PaymentSource.ALIPAY),
            itemLabel = { it.labelOrNone() },
            onSelected = { state = state.copy(paymentSource = it) }
        )
        if (state.creatingFundingAccount) {
            OutlinedTextField(
                value = state.newFundingAccountLabel,
                onValueChange = { state = state.copy(newFundingAccountLabel = it) },
                label = { Text("新资金账户名称") },
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(
                onClick = {
                    state = state.copy(creatingFundingAccount = false, newFundingAccountLabel = "")
                }
            ) { Text("改为选择已有账户") }
        } else {
            SelectionMenu(
                label = "资金账户",
                selected = state.fundingAccountId,
                options = listOf<Long?>(null) + fundingAccounts.map { it.id },
                itemLabel = { id -> fundingAccounts.firstOrNull { it.id == id }?.label ?: "未选择" },
                onSelected = { state = state.copy(fundingAccountId = it) }
            )
            TextButton(
                onClick = {
                    state = state.copy(
                        creatingFundingAccount = true,
                        fundingAccountId = null
                    )
                }
            ) { Text("新建资金账户") }
        }
        OutlinedTextField(
            value = state.note,
            onValueChange = { state = state.copy(note = it) },
            label = { Text("备注（可选）") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        runCatching { state.toInput(System.currentTimeMillis()) }
                            .mapCatching { input -> onSave(input) }
                            .onFailure { snackbarHostState.showSnackbar(it.userMessage()) }
                    }
                }
            ) { Text("保存") }
            OutlinedButton(onClick = ::requestExit) { Text("取消") }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("离开后，本次修改不会保存。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onExit()
                    }
                ) { Text("放弃修改") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } }
        )
    }
}

@Composable
private fun FlowDirectionSelector(
    selected: FlowDirection,
    onSelected: (FlowDirection) -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .border(1.5.dp, FlowSelectorInk, shape)
    ) {
        FlowDirection.entries.forEachIndexed { index, direction ->
            val isSelected = selected == direction
            val selectedColor = when (direction) {
                FlowDirection.INFLOW -> FlowSelectorIncome
                FlowDirection.OUTFLOW -> FlowSelectorExpense
                FlowDirection.NEUTRAL -> MaterialTheme.colorScheme.primaryContainer
            }
            val contentColor = when (direction) {
                FlowDirection.INFLOW -> FlowSelectorIncomeText
                FlowDirection.OUTFLOW -> FlowSelectorExpenseText
                FlowDirection.NEUTRAL -> MaterialTheme.colorScheme.primary
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (isSelected) selectedColor else FlowSelectorSurface)
                    .clickable { onSelected(direction) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isSelected) "✓ ${direction.label()}" else direction.label(),
                    color = if (isSelected) contentColor else FlowSelectorInk,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
            if (index < FlowDirection.entries.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .fillMaxHeight()
                        .background(FlowSelectorInk)
                )
            }
        }
    }
}

private val FlowSelectorInk = Color(0xFF202A44)
private val FlowSelectorSurface = Color(0xFFFFFEFA)
private val FlowSelectorIncome = Color(0xFFDDF7F1)
private val FlowSelectorIncomeText = Color(0xFF169B87)
private val FlowSelectorExpense = Color(0xFFFFE1DE)
private val FlowSelectorExpenseText = Color(0xFFEF5F56)

@Composable
internal fun RecentlyDeletedScreen(
    entries: List<LedgerUiEntry>,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onPermanentlyDelete: (String) -> Unit
) {
    var pendingPermanentDeleteId by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("返回账本") }
        Text("最近删除", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (entries.isEmpty()) {
            Text("最近删除中没有账目")
        }
        entries.forEach { entry ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.title.ifBlank { "未填写标题" }, fontWeight = FontWeight.SemiBold)
                    Text("删除时间 ${formatLedgerEpoch(entry.deletedAtEpochMillis ?: 0)}")
                    Text("剩余 ${entry.remainingRetentionDays(now)} 天")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onRestore(entry.id) }) { Text("恢复") }
                        OutlinedButton(onClick = { pendingPermanentDeleteId = entry.id }) {
                            Text("永久删除")
                        }
                    }
                }
            }
        }
    }
    pendingPermanentDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingPermanentDeleteId = null },
            title = { Text("永久删除这笔账？") },
            text = { Text("永久删除后无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingPermanentDeleteId = null
                        onPermanentlyDelete(id)
                    }
                ) { Text("永久删除") }
            },
            dismissButton = { TextButton(onClick = { pendingPermanentDeleteId = null }) { Text("取消") } }
        )
    }
}
