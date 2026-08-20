package com.bks.feature.ledger

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bks.ui.components.EmptyStatePanel
import com.bks.ui.components.HomeReturnButton
import com.bks.ui.components.SlidePageTransition

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

internal object ReportTestTags {
    const val CATEGORY_CHART = "report-category-chart"
    const val CATEGORY_RANKING_LIST = "report-category-ranking-list"
    const val CASH_FLOW = "report-cash-flow"
    const val SHOW_ALL_CATEGORIES = "report-show-all-categories"
    const val BACK_TO_REPORTS = "report-back-to-overview"
    const val FULL_CATEGORY_RANKING_TITLE = "report-full-category-ranking-title"
}
