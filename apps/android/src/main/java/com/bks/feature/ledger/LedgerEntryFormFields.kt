package com.bks.feature.ledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bks.data.local.CategoryEntity
import com.bks.data.local.DefaultCategories
import com.bks.data.local.FlowDirection
import com.bks.data.local.FundingAccountEntity
import com.bks.data.local.PaymentSource
import com.bks.data.local.TransactionKind
import com.bks.ui.components.Button
import com.bks.ui.components.OutlinedButton
import com.bks.ui.components.OutlinedTextField
import com.bks.ui.components.TextButton
import com.bks.ui.visual.CategoryArtwork

@Composable
internal fun ManualEntryHeader(title: String, onBack: () -> Unit) {
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
internal fun ManualAmountCard(
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
internal fun ManualTransactionCard(
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
            Box(Modifier.weight(1f)) {
                ManualSelectionField(
                    label = "交易类型",
                    selection = ManualSelection(
                        selected = state.transactionKind,
                        options = TransactionKind.entries,
                        itemLabel = TransactionKind::label,
                        testTag = "manual-entry-transaction-kind"
                    ),
                    onSelected = { onStateChange(state.copy(transactionKind = it)) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.weight(1f)) {
                ManualSelectionField(
                    label = "分类",
                    selection = ManualSelection(
                        selected = state.categoryId,
                        options = categoryOptions.map { it.id },
                        itemLabel = { id ->
                            DefaultCategories.nameForId(id)
                                ?: categoryOptions.firstOrNull { it.id == id }?.name
                                ?: "未分类"
                        },
                        testTag = "manual-entry-category"
                    ),
                    leadingContent = { id ->
                        val category = categoryOptions.firstOrNull { it.id == id }
                        CategoryArtwork(
                            categoryId = id,
                            categoryName = category?.name,
                            transactionKind = category?.kind,
                            modifier = Modifier.size(28.dp)
                        )
                    },
                    onSelected = { onStateChange(state.copy(categoryId = it)) }
                )
            }
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
internal fun ManualAccountCard(
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
            Box(Modifier.weight(1f)) {
                ManualSelectionField(
                    label = "支付来源",
                    selection = ManualSelection(
                        selected = state.paymentSource,
                        options = listOf(null, PaymentSource.WECHAT, PaymentSource.ALIPAY, PaymentSource.OTHER),
                        itemLabel = { it.labelOrNone() },
                        testTag = "manual-entry-payment-source"
                    ),
                    onSelected = { onStateChange(state.copy(paymentSource = it)) }
                )
            }
            if (state.creatingFundingAccount) {
                OutlinedTextField(
                    value = state.newFundingAccountLabel,
                    onValueChange = { onStateChange(state.copy(newFundingAccountLabel = it)) },
                    label = { Text("新资金账户名称") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Box(Modifier.weight(1f)) {
                    ManualSelectionField(
                        label = "资金账户",
                        selection = ManualSelection(
                            selected = state.fundingAccountId,
                            options = listOf<Long?>(null) + fundingAccounts.map { it.id },
                            itemLabel = { id ->
                                fundingAccounts.firstOrNull { it.id == id }?.label ?: "未选择"
                            },
                            testTag = "manual-entry-funding-account"
                        ),
                        onSelected = { onStateChange(state.copy(fundingAccountId = it)) }
                    )
                }
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
internal fun ManualEntryCard(
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

internal data class ManualSelection<T>(
    val selected: T,
    val options: List<T>,
    val itemLabel: (T) -> String,
    val testTag: String? = null
)

@Composable
internal fun <T> ManualSelectionField(
    label: String,
    selection: ManualSelection<T>,
    onSelected: (T) -> Unit,
    leadingContent: (@Composable (T) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ManualValueField(
            label = label,
            value = selection.itemLabel(selection.selected),
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .then(selection.testTag?.let { Modifier.testTag(it) } ?: Modifier),
            leadingContent = leadingContent?.let { content -> { content(selection.selected) } }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 420.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            selection.options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            leadingContent?.let {
                                it(option)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(selection.itemLabel(option))
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
internal fun ManualValueField(
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
internal fun ManualEntryActions(
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
internal fun FlowDirectionSelector(
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

private val FlowSelectorInk = androidx.compose.ui.graphics.Color(0xFF202A44)
private val FlowSelectorSurface = androidx.compose.ui.graphics.Color(0xFFFFFEFA)
private val FlowSelectorIncome = androidx.compose.ui.graphics.Color(0xFFDDF7F1)
private val FlowSelectorIncomeText = androidx.compose.ui.graphics.Color(0xFF169B87)
private val FlowSelectorExpense = androidx.compose.ui.graphics.Color(0xFFFFE1DE)
private val FlowSelectorExpenseText = androidx.compose.ui.graphics.Color(0xFFEF5F56)
