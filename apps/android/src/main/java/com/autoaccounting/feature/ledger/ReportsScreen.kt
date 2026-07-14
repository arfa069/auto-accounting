package com.autoaccounting.feature.ledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.EmptyStatePanel
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.ui.visual.CategoryArtwork

@Composable
fun ReportsScreen(
    entries: List<LedgerUiEntry>,
    modifier: Modifier = Modifier
) {
    val monthKey = latestMonthKey(entries)
    val summary = monthlySummary(entries, monthKey)
    val categoryTotals = categoryExpenseTotals(entries, monthKey)
    var selectedCategory by remember(categoryTotals) {
        mutableStateOf(categoryTotals.firstOrNull()?.category.orEmpty())
    }
    val trend = if (selectedCategory.isBlank()) {
        emptyList()
    } else {
        categoryTrend(entries, selectedCategory, monthKey)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("报表", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        ReportOverview(summary)
        if (categoryTotals.isEmpty()) {
            ReportEmptyState()
        } else {
            CategorySharePlaceholder()
            CategoryRanking(
                totals = categoryTotals,
                selectedCategory = selectedCategory,
                onSelectedCategoryChange = { selectedCategory = it }
            )
            TrendPanel(
                selectedCategory = selectedCategory,
                trend = trend
            )
        }
    }
}

@Composable
private fun ReportEmptyState() {
    EmptyStatePanel("本月暂无可分析的支出")
}

@Composable
private fun ReportOverview(summary: MonthlySummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ReportMetric("本月支出 ${formatMoney(summary.expenseMinor)}", Modifier.weight(1f))
        ReportMetric("本月收入 ${formatMoney(summary.incomeMinor)}", Modifier.weight(1f))
    }
}

@Composable
private fun ReportMetric(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun CategorySharePlaceholder() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = CircleShape,
                border = BorderStroke(10.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            ) {
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .fillMaxWidth(0.45f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("图表占位", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CategoryRanking(
    totals: List<CategoryTotal>,
    selectedCategory: String,
    onSelectedCategoryChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("分类排行", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (totals.isEmpty()) {
            Text("本月暂无分类支出")
        } else {
            totals.forEach { total ->
                OutlinedButton(
                    onClick = { onSelectedCategoryChange(total.category) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CategoryArtwork(
                        categoryName = total.category,
                        transactionKind = TransactionKind.EXPENSE,
                        modifier = Modifier.size(32.dp)
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    Text("${total.category} ${formatMoney(total.amountMinor)}")
                }
            }
        }
    }
}

@Composable
private fun TrendPanel(
    selectedCategory: String,
    trend: List<MonthlyCategoryTotal>
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("近 6 个月趋势", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (selectedCategory.isBlank()) {
            Text("选择分类后显示趋势")
        } else {
            Text("当前分类：$selectedCategory")
            trend.forEach { item ->
                Text("${item.monthKey} ${formatMoney(item.amountMinor)}")
            }
        }
    }
}
