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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.autoaccounting.ui.components.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
            flowDirections = listOf(FlowDirection.OUTFLOW, FlowDirection.INFLOW),
            allowCreateFundingAccount = false,
            saveLabel = "保存账目",
            onExit = onExit,
            onSave = onCreateEntry,
            onDelete = null,
            snackbarHostState = snackbarHostState
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
    flowDirections: List<FlowDirection>,
    allowCreateFundingAccount: Boolean,
    saveLabel: String,
    onExit: () -> Unit,
    onSave: suspend (LedgerEntryInput) -> Unit,
    onDelete: (() -> Unit)?,
    snackbarHostState: SnackbarHostState,
    leadingContent: @Composable (
        LedgerEntryFormState,
        (LedgerEntryFormState) -> Unit
    ) -> Unit = { _, _ -> }
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
        if (state != initial) confirmDiscard = true else onExit()
    }

    fun saveEntry() {
        scope.launch {
            runCatching { state.toInput(System.currentTimeMillis()) }
                .mapCatching { input -> onSave(input) }
                .onFailure { snackbarHostState.showSnackbar(it.userMessage()) }
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
            leadingContent(state) { state = it }
            ManualAmountCard(
                state = state,
                directions = flowDirections,
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
                allowCreateFundingAccount = allowCreateFundingAccount,
                onStateChange = { state = it }
            )
            if (onDelete != null) {
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
            saveLabel = saveLabel,
            onCancel = ::requestExit,
            onSave = ::saveEntry
        )
    }

    DiscardChangesDialog(
        visible = confirmDiscard,
        onDismiss = { confirmDiscard = false },
        onDiscard = onExit
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
                        onDelete?.invoke()
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
private fun ManualEntryHeader(title: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(42.dp)
                .testTag("manual-entry-back")
                .clickable(onClick = onBack),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("‹", style = MaterialTheme.typography.headlineSmall)
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ManualAmountCard(
    state: LedgerEntryFormState,
    directions: List<FlowDirection>,
    onStateChange: (LedgerEntryFormState) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            FlowDirectionSelector(
                selected = state.flowDirection,
                directions = directions,
                onSelected = { direction -> onStateChange(state.copy(flowDirection = direction)) }
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "金额（CNY）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BasicTextField(
                    value = state.amountText,
                    onValueChange = { onStateChange(state.copy(amountText = it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual-entry-amount"),
                    textStyle = MaterialTheme.typography.displaySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "¥ ",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                if (state.amountText.isEmpty()) {
                                    Text(
                                        text = "0.00",
                                        style = MaterialTheme.typography.displaySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                innerTextField()
                            }
                        }
                    }
                )
                HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ManualTransactionCard(
    state: LedgerEntryFormState,
    categoryOptions: List<CategoryEntity>,
    onStateChange: (LedgerEntryFormState) -> Unit,
    onSelectTime: () -> Unit
) {
    ManualEntryCard(title = "交易信息") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.merchantTitle,
                onValueChange = { onStateChange(state.copy(merchantTitle = it)) },
                label = { Text("商户（可选）") },
                maxLines = 2,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 72.dp)
                    .testTag("manual-entry-merchant")
            )
            ManualSelectionField(
                label = "交易类型",
                selected = state.transactionKind,
                options = TransactionKind.entries,
                itemLabel = TransactionKind::label,
                onSelected = { onStateChange(state.copy(transactionKind = it)) },
                modifier = Modifier.weight(1f),
                testTag = "manual-entry-transaction-kind"
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ManualSelectionField(
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
                        modifier = Modifier.size(28.dp)
                    )
                },
                onSelected = { onStateChange(state.copy(categoryId = it)) },
                modifier = Modifier.weight(1f),
                testTag = "manual-entry-category"
            )
            ManualValueField(
                label = "交易时间",
                value = formatLedgerEpoch(state.transactionTimeEpochMillis),
                onClick = onSelectTime,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ManualAccountCard(
    state: LedgerEntryFormState,
    fundingAccounts: List<FundingAccountEntity>,
    allowCreateFundingAccount: Boolean,
    onStateChange: (LedgerEntryFormState) -> Unit
) {
    ManualEntryCard(title = "账户与备注") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ManualSelectionField(
                label = "支付来源",
                selected = state.paymentSource,
                options = listOf(null, PaymentSource.WECHAT, PaymentSource.ALIPAY),
                itemLabel = { it.labelOrNone() },
                onSelected = { onStateChange(state.copy(paymentSource = it)) },
                modifier = Modifier.weight(1f),
                testTag = "manual-entry-payment-source"
            )
            if (state.creatingFundingAccount) {
                OutlinedTextField(
                    value = state.newFundingAccountLabel,
                    onValueChange = { onStateChange(state.copy(newFundingAccountLabel = it)) },
                    label = { Text("新资金账户名称") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            } else {
                ManualSelectionField(
                    label = "资金账户",
                    selected = state.fundingAccountId,
                    options = listOf<Long?>(null) + fundingAccounts.map { it.id },
                    itemLabel = { id ->
                        fundingAccounts.firstOrNull { it.id == id }?.label ?: "未选择"
                    },
                    onSelected = { onStateChange(state.copy(fundingAccountId = it)) },
                    modifier = Modifier.weight(1f),
                    testTag = "manual-entry-funding-account"
                )
            }
        }
        if (allowCreateFundingAccount) {
            TextButton(
                onClick = {
                    onStateChange(
                        if (state.creatingFundingAccount) {
                            state.copy(
                                creatingFundingAccount = false,
                                newFundingAccountLabel = ""
                            )
                        } else {
                            state.copy(
                                creatingFundingAccount = true,
                                fundingAccountId = null
                            )
                        }
                    )
                }
            ) {
                Text(
                    if (state.creatingFundingAccount) {
                        "改为选择已有账户"
                    } else {
                        "新建资金账户"
                    }
                )
            }
        }
        OutlinedTextField(
            value = state.note,
            onValueChange = { onStateChange(state.copy(note = it)) },
            label = { Text("备注（可选）") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("manual-entry-note")
        )
    }
}

@Composable
private fun ManualEntryCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun <T> ManualSelectionField(
    label: String,
    selected: T,
    options: List<T>,
    itemLabel: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable (T) -> Unit)? = null,
    testTag: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        ManualValueField(
            label = label,
            value = itemLabel(selected),
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .then(testTag?.let { Modifier.testTag(it) } ?: Modifier),
            leadingContent = leadingContent?.let { content -> { content(selected) } }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 420.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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

@Composable
private fun ManualValueField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                leadingContent?.invoke()
                if (leadingContent != null) Spacer(Modifier.width(8.dp))
                Text(
                    text = value,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ManualEntryActions(
    saveLabel: String,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .testTag("manual-entry-actions"),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) { Text("取消", fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = onSave,
                modifier = Modifier
                    .weight(1.65f)
                    .height(52.dp)
            ) { Text(saveLabel, fontWeight = FontWeight.SemiBold) }
        }
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
    onDelete: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    SharedLedgerEntryForm(
        title = title,
        initial = initial,
        categories = categories,
        fundingAccounts = fundingAccounts,
        flowDirections = listOf(FlowDirection.OUTFLOW, FlowDirection.INFLOW),
        allowCreateFundingAccount = false,
        saveLabel = "保存修改",
        onExit = onExit,
        onSave = onSave,
        onDelete = onDelete,
        snackbarHostState = snackbarHostState
    )
}

@Composable
private fun FlowDirectionSelector(
    selected: FlowDirection,
    directions: List<FlowDirection> = FlowDirection.entries,
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
        directions.forEachIndexed { index, direction ->
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
                    .clickable { onSelected(direction) }
                    .testTag("manual-direction-${direction.name}"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isSelected) "✓ ${direction.label()}" else direction.label(),
                    color = if (isSelected) contentColor else FlowSelectorInk,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
            if (index < directions.lastIndex) {
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
