package com.autoaccounting.feature.ledger

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoaccounting.data.local.TransactionKind
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

@Composable
internal fun CategoryShareDonut(
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
internal fun CategoryRankingPreview(
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
internal fun FullCategoryRankingScreen(
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
