package com.autoaccounting.feature.ledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun LedgerScreen(
    entries: List<LedgerUiEntry>,
    modifier: Modifier = Modifier
) {
    var searchText by remember { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }
    var sourceFilter by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf("") }

    val monthKey = latestMonthKey(entries)
    val summary = monthlySummary(entries, monthKey)
    val filteredEntries = entries
        .filter { it.monthKey == monthKey }
        .filter {
            val searchableText = "${it.title} ${it.note.orEmpty()} ${it.category}"
            searchText.isBlank() || searchableText.contains(searchText.trim(), ignoreCase = true)
        }
        .filter { sourceFilter.isBlank() || it.sourceLabel.contains(sourceFilter.trim()) }
        .filter { categoryFilter.isBlank() || it.category.contains(categoryFilter.trim()) }
        .filter { kindFilter.isBlank() || it.kindLabel.contains(kindFilter.trim()) }
        .sortedByDescending { it.transactionTimeText }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("本地账本", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        LedgerSummary(summary)

        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            label = { Text("搜索商户或备注") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(onClick = { showFilters = !showFilters }) {
            Text("筛选")
        }

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

        Text("$monthKey 明细", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (filteredEntries.isEmpty()) {
            Text("当前没有已确认账目")
        } else {
            filteredEntries.forEach { entry ->
                LedgerEntryRow(entry)
            }
        }
    }
}

@Composable
private fun LedgerSummary(summary: MonthlySummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryChip("本月支出 ${formatMoney(summary.expenseMinor)}", Modifier.weight(1f))
        SummaryChip("本月收入 ${formatMoney(summary.incomeMinor)}", Modifier.weight(1f))
        SummaryChip("净额 ${formatSignedMoney(summary.netMinor)}", Modifier.weight(1f))
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
        OutlinedTextField(
            value = sourceFilter,
            onValueChange = onSourceChange,
            label = { Text("来源") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = categoryFilter,
            onValueChange = onCategoryChange,
            label = { Text("分类") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = kindFilter,
            onValueChange = onKindChange,
            label = { Text("交易类型") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LedgerEntryRow(entry: LedgerUiEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.title, fontWeight = FontWeight.SemiBold)
                Text(
                    "${entry.category} · ${entry.transactionTimeText} · ${entry.sourceLabel}",
                    style = MaterialTheme.typography.bodySmall
                )
                entry.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                text = formatMoney(entry.amountMinor),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
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
