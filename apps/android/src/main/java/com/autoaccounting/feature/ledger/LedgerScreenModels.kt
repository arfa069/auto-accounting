package com.autoaccounting.feature.ledger

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.runtime.saveable.listSaver
import com.autoaccounting.data.local.EntryOrigin
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.LedgerEntryInput
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.TransactionKind
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

internal enum class LedgerView {
    LIST,
    EDIT,
    DELETED,
    LEDGER_BOOKS,
    FUNDING_ACCOUNTS
}

internal data class LedgerPage(
    val view: LedgerView,
    val selectedEntryId: String?
)

internal data class LedgerEntryFormState(
    val flowDirection: FlowDirection,
    val transactionKind: TransactionKind,
    val amountText: String,
    val transactionTimeEpochMillis: Long,
    val merchantTitle: String,
    val categoryId: String,
    val fundingAccountId: Long?,
    val creatingFundingAccount: Boolean,
    val newFundingAccountLabel: String,
    val note: String,
    val paymentSource: PaymentSource?
) {
    fun toInput(nowEpochMillis: Long): LedgerEntryInput {
        val normalizedAmount = amountText.trim()
        require(AMOUNT_PATTERN.matches(normalizedAmount)) {
            "金额必须大于 0，且最多保留两位小数"
        }
        val amountMinor = try {
            BigDecimal(normalizedAmount).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("金额超出支持范围")
        }
        require(amountMinor > 0) { "金额必须大于 0" }
        require(transactionTimeEpochMillis <= nowEpochMillis) { "交易时间不能晚于当前时间" }
        if (creatingFundingAccount) {
            require(newFundingAccountLabel.isNotBlank()) { "请输入资金账户名称" }
        }
        return LedgerEntryInput(
            flowDirection = flowDirection,
            transactionKind = transactionKind,
            amountMinor = amountMinor,
            transactionTimeEpochMillis = transactionTimeEpochMillis,
            merchantTitle = merchantTitle,
            categoryId = categoryId,
            fundingAccountId = if (creatingFundingAccount) null else fundingAccountId,
            newFundingAccountLabel = if (creatingFundingAccount) newFundingAccountLabel else null,
            note = note,
            paymentSource = paymentSource
        )
    }

    companion object {
        val Saver = listSaver<LedgerEntryFormState, Any>(
            save = { state ->
                listOf(
                    state.flowDirection.name,
                    state.transactionKind.name,
                    state.amountText,
                    state.transactionTimeEpochMillis,
                    state.merchantTitle,
                    state.categoryId,
                    state.fundingAccountId ?: NO_FUNDING_ACCOUNT_ID,
                    state.creatingFundingAccount,
                    state.newFundingAccountLabel,
                    state.note,
                    state.paymentSource?.name.orEmpty()
                )
            },
            restore = { values ->
                LedgerEntryFormState(
                    flowDirection = FlowDirection.valueOf(values[0] as String),
                    transactionKind = TransactionKind.valueOf(values[1] as String),
                    amountText = values[2] as String,
                    transactionTimeEpochMillis = values[3] as Long,
                    merchantTitle = values[4] as String,
                    categoryId = values[5] as String,
                    fundingAccountId = (values[6] as Long)
                        .takeUnless { it == NO_FUNDING_ACCOUNT_ID },
                    creatingFundingAccount = values[7] as Boolean,
                    newFundingAccountLabel = values[8] as String,
                    note = values[9] as String,
                    paymentSource = (values[10] as String)
                        .takeIf(String::isNotEmpty)
                        ?.let(PaymentSource::valueOf)
                )
            }
        )

        fun newEntry(nowEpochMillis: Long = System.currentTimeMillis()): LedgerEntryFormState =
            LedgerEntryFormState(
                flowDirection = FlowDirection.OUTFLOW,
                transactionKind = TransactionKind.EXPENSE,
                amountText = "",
                transactionTimeEpochMillis = nowEpochMillis,
                merchantTitle = "",
                categoryId = LocalLedgerRepository.DEFAULT_CATEGORY_ID,
                fundingAccountId = null,
                creatingFundingAccount = false,
                newFundingAccountLabel = "",
                note = "",
                paymentSource = null
            )

        fun from(entry: LedgerUiEntry): LedgerEntryFormState = LedgerEntryFormState(
            flowDirection = entry.flowDirection,
            transactionKind = entry.transactionKind,
            amountText = BigDecimal(entry.amountMinor).movePointLeft(2).toPlainString(),
            transactionTimeEpochMillis = entry.transactionTimeEpochMillis,
            merchantTitle = entry.title,
            categoryId = entry.categoryId ?: LocalLedgerRepository.DEFAULT_CATEGORY_ID,
            fundingAccountId = entry.fundingAccountId,
            creatingFundingAccount = false,
            newFundingAccountLabel = "",
            note = entry.note.orEmpty(),
            paymentSource = entry.paymentSource
        )

        private const val NO_FUNDING_ACCOUNT_ID = Long.MIN_VALUE
    }
}

internal fun showDateTimePicker(
    context: android.content.Context,
    currentEpochMillis: Long,
    onSelected: (Long) -> Unit
) {
    val zoneId = ZoneId.systemDefault()
    val current = Instant.ofEpochMilli(currentEpochMillis).atZone(zoneId)
    val dateDialog = DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    onSelected(
                        LocalDateTime.of(year, month + 1, day, hour, minute)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli()
                    )
                },
                current.hour,
                current.minute,
                true
            ).show()
        },
        current.year,
        current.monthValue - 1,
        current.dayOfMonth
    )
    dateDialog.datePicker.maxDate = System.currentTimeMillis()
    dateDialog.show()
}

internal fun LedgerUiEntry.remainingRetentionDays(nowEpochMillis: Long): Long {
    val deletedAt = deletedAtEpochMillis ?: return 0
    val remaining = deletedAt + LocalLedgerRepository.DELETED_RETENTION_MILLIS - nowEpochMillis
    return max(0, (remaining + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY)
}

internal fun formatLedgerEpoch(epochMillis: Long): String =
    if (epochMillis <= 0) "—" else LEDGER_DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis))

internal fun PaymentSource?.labelOrNone(): String = when (this) {
    PaymentSource.WECHAT -> "微信"
    PaymentSource.ALIPAY -> "支付宝"
    null -> "未指定"
}

internal fun FlowDirection.label(): String = when (this) {
    FlowDirection.INFLOW -> "流入"
    FlowDirection.OUTFLOW -> "流出"
    FlowDirection.NEUTRAL -> "不计收支"
}

internal fun TransactionKind.label(): String = when (this) {
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

internal fun EntryOrigin.label(): String = when (this) {
    EntryOrigin.MANUAL -> "手动录入"
    EntryOrigin.NOTIFICATION -> "通知捕获"
    EntryOrigin.ACCESSIBILITY_AUTO -> "自动记账"
    EntryOrigin.BILL_SYNC -> "补录账单"
    EntryOrigin.DUPLICATE_MERGE -> "重复合并"
    EntryOrigin.LEGACY_CAPTURE -> "旧版采集（方式未知）"
}

internal fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: "操作失败，请重试"

private val AMOUNT_PATTERN = Regex("^\\d+(\\.\\d{1,2})?$")
private val LEDGER_DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
