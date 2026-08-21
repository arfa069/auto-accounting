package com.bks.feature.ledger

import com.bks.data.local.EntryOrigin
import com.bks.data.local.DefaultCategories
import com.bks.data.local.FlowDirection
import com.bks.data.local.LedgerEntryEntity
import com.bks.data.local.PaymentSource
import com.bks.data.local.TransactionKind
import java.math.BigInteger
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LedgerFlowType {
    EXPENSE,
    INCOME,
    NEUTRAL
}

data class LedgerUiEntry(
    val id: String,
    val title: String,
    val amountMinor: Long,
    val monthKey: String,
    val transactionTimeText: String,
    val category: String,
    val sourceLabel: String,
    val kindLabel: String,
    val flowType: LedgerFlowType,
    val note: String? = null,
    val paymentSource: PaymentSource? = null,
    val originalCaptureSource: PaymentSource? = null,
    val entryOrigin: EntryOrigin = EntryOrigin.LEGACY_CAPTURE,
    val originPendingEntryId: String? = null,
    val flowDirection: FlowDirection = FlowDirection.OUTFLOW,
    val transactionKind: TransactionKind = TransactionKind.OTHER,
    val transactionTimeEpochMillis: Long = 0,
    val categoryId: String? = null,
    val fundingAccountId: Long? = null,
    val evidenceSummary: String? = null,
    val parsedFieldsText: String? = null,
    val confirmedAtEpochMillis: Long = 0,
    val updatedAtEpochMillis: Long = 0,
    val deletedAtEpochMillis: Long? = null
)

data class MonthlySummary(
    val expenseMinor: Long,
    val incomeMinor: Long,
    val netMinor: Long
)

data class CategoryTotal(
    val category: String,
    val amountMinor: Long
)

data class MonthlyCashFlowTotal(
    val monthKey: String,
    val expenseMinor: Long,
    val incomeMinor: Long
)

data class CategoryShareSlice(
    val category: String,
    val amountMinor: Long,
    val percentageTenths: Int
)

data class LedgerReportUiModel(
    val anchorMonthKey: String? = null,
    val summary: MonthlySummary? = null,
    val categoryTotals: List<CategoryTotal> = emptyList(),
    val categorySlices: List<CategoryShareSlice> = emptyList(),
    val cashFlowTotals: List<MonthlyCashFlowTotal> = emptyList()
)

fun buildLedgerReportUiModel(entries: List<LedgerUiEntry>): LedgerReportUiModel {
    val anchorMonthKey = latestCashFlowMonthKey(entries) ?: return LedgerReportUiModel()
    val summary = monthlySummary(entries, anchorMonthKey)
    val categoryTotals = categoryExpenseTotals(entries, anchorMonthKey)
    return LedgerReportUiModel(
        anchorMonthKey = anchorMonthKey,
        summary = summary,
        categoryTotals = categoryTotals,
        categorySlices = categoryShareSlices(categoryTotals),
        cashFlowTotals = monthlyCashFlowRange(entries, anchorMonthKey)
    )
}

fun LedgerEntryEntity.toLedgerUiEntry(
    zoneId: ZoneId = ZoneId.systemDefault()
): LedgerUiEntry {
    val transactionTimeText = formatLedgerDateTime(transactionTimeEpochMillis, zoneId)
    val kind = transactionKind.toLabel()
    return LedgerUiEntry(
        id = id,
        title = merchantTitle,
        amountMinor = amountMinor,
        monthKey = transactionTimeText.take(7),
        transactionTimeText = transactionTimeText,
        category = categoryId?.let { DefaultCategories.nameForId(it) ?: it } ?: "未分类",
        sourceLabel = paymentSource?.toLabel() ?: "未指定",
        kindLabel = kind,
        flowType = when (flowDirection) {
            FlowDirection.INFLOW -> LedgerFlowType.INCOME
            FlowDirection.OUTFLOW -> LedgerFlowType.EXPENSE
            FlowDirection.NEUTRAL -> LedgerFlowType.NEUTRAL
        },
        note = note,
        paymentSource = paymentSource,
        originalCaptureSource = originalCaptureSource,
        entryOrigin = entryOrigin,
        originPendingEntryId = originPendingEntryId,
        flowDirection = flowDirection,
        transactionKind = transactionKind,
        transactionTimeEpochMillis = transactionTimeEpochMillis,
        categoryId = categoryId,
        fundingAccountId = fundingAccountId,
        evidenceSummary = evidenceSummary,
        parsedFieldsText = parsedFieldsText,
        confirmedAtEpochMillis = confirmedAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        deletedAtEpochMillis = deletedAtEpochMillis
    )
}

fun monthlySummary(
    entries: List<LedgerUiEntry>,
    monthKey: String
): MonthlySummary {
    val currentMonthEntries = entries.filter { it.isActive() && it.monthKey == monthKey }
    val expense = currentMonthEntries
        .filter { it.flowType == LedgerFlowType.EXPENSE }
        .sumOf { it.amountMinor }
    val income = currentMonthEntries
        .filter { it.flowType == LedgerFlowType.INCOME }
        .sumOf { it.amountMinor }
    return MonthlySummary(
        expenseMinor = expense,
        incomeMinor = income,
        netMinor = income - expense
    )
}

fun categoryExpenseTotals(
    entries: List<LedgerUiEntry>,
    monthKey: String
): List<CategoryTotal> = entries
    .filter {
        it.isActive() &&
            it.monthKey == monthKey &&
            it.flowType == LedgerFlowType.EXPENSE
    }
    .groupBy { it.category }
    .map { (category, categoryEntries) ->
        CategoryTotal(
            category = category,
            amountMinor = categoryEntries.sumOf { it.amountMinor }
        )
    }
    .sortedByDescending { it.amountMinor }

fun latestCashFlowMonthKey(entries: List<LedgerUiEntry>): String? = entries
    .asSequence()
    .filter { it.isActiveCashFlow() }
    .map { YearMonth.parse(it.monthKey) }
    .maxOrNull()
    ?.toString()

fun monthlyCashFlowRange(
    entries: List<LedgerUiEntry>,
    anchorMonthKey: String,
    radius: Int = 3
): List<MonthlyCashFlowTotal> {
    require(radius >= 0) { "radius must be non-negative" }
    val anchorMonth = YearMonth.parse(anchorMonthKey)
    val entriesByMonth = entries
        .asSequence()
        .filter { it.isActiveCashFlow() }
        .groupBy { YearMonth.parse(it.monthKey) }

    return (-radius..radius).map { offset ->
        val month = anchorMonth.plusMonths(offset.toLong())
        val monthEntries = entriesByMonth[month].orEmpty()
        MonthlyCashFlowTotal(
            monthKey = month.toString(),
            expenseMinor = monthEntries
                .filter { it.flowType == LedgerFlowType.EXPENSE }
                .sumOf { it.amountMinor },
            incomeMinor = monthEntries
                .filter { it.flowType == LedgerFlowType.INCOME }
                .sumOf { it.amountMinor }
        )
    }
}

fun categoryShareSlices(
    totals: List<CategoryTotal>,
    maxVisibleCategories: Int = 4
): List<CategoryShareSlice> {
    require(maxVisibleCategories > 0) { "maxVisibleCategories must be positive" }
    val rankedTotals = totals
        .filter { it.amountMinor > 0 }
        .sortedWith(
            compareByDescending<CategoryTotal> { it.amountMinor }
                .thenBy { it.category }
        )
    if (rankedTotals.isEmpty()) return emptyList()

    val displayedTotals = if (rankedTotals.size <= maxVisibleCategories) {
        rankedTotals
    } else {
        rankedTotals.take(maxVisibleCategories) +
            CategoryTotal(
                category = "其他",
                amountMinor = rankedTotals.drop(maxVisibleCategories).sumOf { it.amountMinor }
            )
    }
    val totalAmount = displayedTotals.fold(BigInteger.ZERO) { sum, total ->
        sum.add(BigInteger.valueOf(total.amountMinor))
    }
    val percentageTenths = MutableList(displayedTotals.size) { 0 }
    val remainders = displayedTotals.mapIndexed { index, total ->
        val quotientAndRemainder = BigInteger.valueOf(total.amountMinor)
            .multiply(BigInteger.valueOf(1000))
            .divideAndRemainder(totalAmount)
        percentageTenths[index] = quotientAndRemainder[0].toInt()
        index to quotientAndRemainder[1]
    }
    val undistributedTenths = 1000 - percentageTenths.sum()
    remainders
        .sortedWith(
            compareByDescending<Pair<Int, BigInteger>> { it.second }
                .thenBy { it.first }
        )
        .take(undistributedTenths)
        .forEach { (index) -> percentageTenths[index] += 1 }

    return displayedTotals.mapIndexed { index, total ->
        CategoryShareSlice(
            category = total.category,
            amountMinor = total.amountMinor,
            percentageTenths = percentageTenths[index]
        )
    }
}

fun latestMonthKey(entries: List<LedgerUiEntry>): String =
    entries.maxOfOrNull { it.monthKey } ?: "2026-07"

fun formatMoney(amountMinor: Long): String {
    val yuan = amountMinor / 100
    val cents = kotlin.math.abs(amountMinor % 100)
    return "¥$yuan.${cents.toString().padStart(2, '0')}"
}

fun formatSignedMoney(amountMinor: Long): String {
    val sign = when {
        amountMinor > 0 -> "+"
        amountMinor < 0 -> "-"
        else -> ""
    }
    return "$sign${formatMoney(kotlin.math.abs(amountMinor))}"
}

private fun LedgerUiEntry.isActive(): Boolean = deletedAtEpochMillis == null

private fun LedgerUiEntry.isActiveCashFlow(): Boolean =
    isActive() && flowType != LedgerFlowType.NEUTRAL

private fun PaymentSource.toLabel(): String = when (this) {
    PaymentSource.WECHAT -> "微信"
    PaymentSource.ALIPAY -> "支付宝"
    PaymentSource.OTHER -> "其他应用"
}

private fun TransactionKind.toLabel(): String = when (this) {
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

private fun formatLedgerDateTime(epochMillis: Long, zoneId: ZoneId): String =
    LEDGER_DATE_TIME_FORMATTER.withZone(zoneId).format(Instant.ofEpochMilli(epochMillis))

private val LEDGER_DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
