package com.bks.feature.ledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val ReportExpenseAccent = Color(0xFFC23F36)
private val ReportIncomeAccent = Color(0xFF087F70)

@Composable
internal fun CashFlowPanel(
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
