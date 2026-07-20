package com.autoaccounting.feature.ledger

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.ui.components.EmptyStatePanel
import com.autoaccounting.ui.components.HomeReturnButton
import com.autoaccounting.ui.components.SlidePageTransition
import com.autoaccounting.ui.components.TextButton
import com.autoaccounting.ui.visual.CategoryArtwork
import kotlin.math.min

private val ReportChartPalette = listOf(
    Color(0xFF5B5BD6),
    Color(0xFF56C7B7),
    Color(0xFFFF7B7B),
    Color(0xFFFFD45A),
    Color(0xFFB7A8E8)
)
private val ReportChartInk = Color(0xFF252536)
private val ReportExpenseAccent = Color(0xFFC23F36)
private val ReportIncomeAccent = Color(0xFF087F70)

@Composable
fun ReportsScreen(
    entries: List<LedgerUiEntry>,
    reportUiModel: LedgerReportUiModel? = null,
    categoryRankingListState: LazyListState = rememberLazyListState(),
    onNavigateHome: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val report = reportUiModel ?: remember(entries) { buildLedgerReportUiModel(entries) }
    var showFullCategoryRanking by remember { mutableStateOf(false) }

    BackHandler(enabled = showFullCategoryRanking) {
        showFullCategoryRanking = false
    }

    SlidePageTransition(
        targetState = showFullCategoryRanking,
        modifier = modifier.fillMaxSize()
    ) { showFullRanking ->
        if (showFullRanking) {
            FullCategoryRankingScreen(
                totals = report.categoryTotals,
                listState = categoryRankingListState,
                onBack = { showFullCategoryRanking = false }
            )
        } else {
            ReportsOverviewScreen(
                report = report,
                onShowFullCategoryRanking = { showFullCategoryRanking = true },
                onNavigateHome = onNavigateHome
            )
        }
    }
}

@Composable
private fun ReportsOverviewScreen(
    report: LedgerReportUiModel,
    onShowFullCategoryRanking: () -> Unit,
    onNavigateHome: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("报表", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            HomeReturnButton(onClick = onNavigateHome)
        }

        val anchorMonthKey = report.anchorMonthKey
        val summary = report.summary
        if (anchorMonthKey == null || summary == null) {
            ReportEmptyState()
            return@Column
        }

        ReportOverview(summary)
        CategoryShareDonut(
            expenseMinor = summary.expenseMinor,
            slices = report.categorySlices,
            modifier = Modifier.testTag(ReportTestTags.CATEGORY_CHART)
        )
        CategoryRankingPreview(
            totals = report.categoryTotals,
            onShowMore = onShowFullCategoryRanking,
            modifier = Modifier.weight(1f)
        )
        CashFlowPanel(
            anchorMonthKey = anchorMonthKey,
            totals = report.cashFlowTotals,
            modifier = Modifier.testTag(ReportTestTags.CASH_FLOW)
        )
    }
}

@Composable
private fun ReportEmptyState() {
    EmptyStatePanel("当前账本暂无可分析的收支")
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
private fun CategoryShareDonut(
    expenseMinor: Long,
    slices: List<CategoryShareSlice>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        if (slices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 112.dp)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "本月暂无支出分类",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val semantics = buildDonutDescription(expenseMinor, slices)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 164.dp)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CategoryDonutCanvas(
                        slices = slices,
                        contentDescription = semantics,
                        modifier = Modifier.fillMaxSize()
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "本月支出",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatMoney(expenseMinor),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    slices.forEachIndexed { index, slice ->
                        CategoryLegendRow(
                            slice = slice,
                            color = ReportChartPalette[index % ReportChartPalette.size]
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryDonutCanvas(
    slices: List<CategoryShareSlice>,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.semantics {
            this.contentDescription = contentDescription
        }
    ) {
        val outlineWidth = 30.dp.toPx()
        val sliceWidth = 24.dp.toPx()
        drawCircle(
            color = ReportChartInk,
            style = Stroke(width = outlineWidth)
        )

        var startAngle = -90f
        slices.forEachIndexed { index, slice ->
            val sweepAngle = 360f * slice.percentageTenths / 1000f
            val gapAngle = if (slices.size == 1) {
                0f
            } else {
                min(2f, sweepAngle * 0.35f)
            }
            drawArc(
                color = ReportChartPalette[index % ReportChartPalette.size],
                startAngle = startAngle + gapAngle / 2f,
                sweepAngle = (sweepAngle - gapAngle).coerceAtLeast(0f),
                useCenter = false,
                style = Stroke(width = sliceWidth, cap = StrokeCap.Butt)
            )
            startAngle += sweepAngle
        }
    }
}

@Composable
private fun CategoryLegendRow(
    slice: CategoryShareSlice,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, CircleShape)
                .border(1.dp, ReportChartInk, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = slice.category,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = formatPercentage(slice.percentageTenths),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CategoryRankingPreview(
    totals: List<CategoryTotal>,
    onShowMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "分类排行",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(
                onClick = onShowMore,
                modifier = Modifier.testTag(ReportTestTags.SHOW_ALL_CATEGORIES)
            ) {
                Text("更多")
            }
        }
        if (totals.isEmpty()) {
            Text("本月暂无支出分类", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            totals.take(3).forEach { total ->
                CategoryRankingRow(total)
            }
        }
    }
}

@Composable
private fun FullCategoryRankingScreen(
    totals: List<CategoryTotal>,
    listState: LazyListState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "分类排行",
                modifier = Modifier.testTag(ReportTestTags.FULL_CATEGORY_RANKING_TITLE),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(
                onClick = onBack,
                modifier = Modifier.testTag(ReportTestTags.BACK_TO_REPORTS)
            ) {
                Text("返回")
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(ReportTestTags.CATEGORY_RANKING_LIST),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (totals.isEmpty()) {
                item {
                    Text("本月暂无支出分类", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(totals, key = { it.category }) { total ->
                    CategoryRankingRow(total)
                }
            }
        }
    }
}

@Composable
private fun CategoryRankingRow(
    total: CategoryTotal
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CategoryArtwork(
                categoryName = total.category,
                transactionKind = TransactionKind.EXPENSE,
                modifier = Modifier
                    .size(32.dp)
                    .clearAndSetSemantics {}
            )
            Spacer(Modifier.width(8.dp))
            Text("${total.category} ${formatMoney(total.amountMinor)}")
        }
    }
}

@Composable
private fun CashFlowPanel(
    anchorMonthKey: String,
    totals: List<MonthlyCashFlowTotal>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("7 个月收支", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                CashFlowHeader()
                totals.forEach { total ->
                    CashFlowRow(
                        total = total,
                        isAnchorMonth = total.monthKey == anchorMonthKey
                    )
                }
            }
        }
    }
}

@Composable
private fun CashFlowHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = "月份",
            modifier = Modifier.weight(1.05f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "支出",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelSmall,
            color = ReportExpenseAccent
        )
        Text(
            text = "收入",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelSmall,
            color = ReportIncomeAccent
        )
    }
}

@Composable
private fun CashFlowRow(
    total: MonthlyCashFlowTotal,
    isAnchorMonth: Boolean
) {
    val rowDescription = if (isAnchorMonth) {
        "基准月份 ${total.monthKey}，支出 ${formatMoney(total.expenseMinor)}，收入 ${formatMoney(total.incomeMinor)}"
    } else {
        "${total.monthKey}，支出 ${formatMoney(total.expenseMinor)}，收入 ${formatMoney(total.incomeMinor)}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isAnchorMonth) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                }
            )
            .semantics(mergeDescendants = true) {
                contentDescription = rowDescription
            }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = total.monthKey,
            modifier = Modifier.weight(1.05f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isAnchorMonth) FontWeight.SemiBold else FontWeight.Normal
        )
        Text(
            text = formatMoney(total.expenseMinor),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            color = ReportExpenseAccent,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = formatMoney(total.incomeMinor),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            color = ReportIncomeAccent,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun buildDonutDescription(
    expenseMinor: Long,
    slices: List<CategoryShareSlice>
): String = buildString {
    append("本月支出分类环形图，总支出 ")
    append(formatMoney(expenseMinor))
    slices.forEach { slice ->
        append("，")
        append(slice.category)
        append(" ")
        append(formatPercentage(slice.percentageTenths))
    }
}

private fun formatPercentage(percentageTenths: Int): String =
    "${percentageTenths / 10}.${percentageTenths % 10}%"

internal object ReportTestTags {
    const val CATEGORY_CHART = "report-category-chart"
    const val CATEGORY_RANKING_LIST = "report-category-ranking-list"
    const val CASH_FLOW = "report-cash-flow"
    const val SHOW_ALL_CATEGORIES = "report-show-all-categories"
    const val BACK_TO_REPORTS = "report-back-to-overview"
    const val FULL_CATEGORY_RANKING_TITLE = "report-full-category-ranking-title"
}
