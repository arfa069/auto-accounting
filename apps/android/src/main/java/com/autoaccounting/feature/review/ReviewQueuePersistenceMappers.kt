package com.autoaccounting.feature.review

import com.autoaccounting.data.local.CaptureReason
import com.autoaccounting.data.local.IgnoreReason
import com.autoaccounting.data.local.IgnoredEntryEntity
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.PendingEntryEntity
import com.autoaccounting.data.local.TransactionKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun PendingEntryEntity.toReviewEntry(zoneId: ZoneId): ReviewQueueEntry = ReviewQueueEntry(
    id = id,
    title = merchantTitle,
    amountMinor = amountMinor,
    transactionTimeText = formatReviewDateTime(transactionTimeEpochMillis, zoneId),
    category = suggestedCategoryId?.toCategoryLabel().orEmpty(),
    fundingAccountLabel = fundingAccountLabel ?: fundingAccountId?.let { "账户 $it" }.orEmpty(),
    sourceLabel = source.toLabel(),
    kindLabel = transactionKind.toLabel(),
    captureReasonLabel = captureReason.toLabel(),
    confidence = confidence,
    capturedAtEpochMillis = capturedAtEpochMillis,
    captureTimeText = formatReviewDateTime(capturedAtEpochMillis, zoneId),
    note = note,
    rawEvidenceText = evidenceSummary.orEmpty(),
    parsedFields = parsedFieldsText.decodeParsedFields()
)

internal fun IgnoredEntryEntity.toReviewIgnoredEntry(zoneId: ZoneId): ReviewQueueIgnoredEntry =
    ReviewQueueIgnoredEntry(
        id = id,
        originalPendingId = originalPendingEntryId,
        entry = toPendingEntryEntity().toReviewEntry(zoneId),
        ignoredAtEpochMillis = ignoredAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis
    )

internal fun ReviewQueueEntry.toEntity(zoneId: ZoneId): PendingEntryEntity = PendingEntryEntity(
    id = id,
    source = sourceLabel.toPaymentSource(),
    captureReason = captureReasonLabel.toCaptureReason(),
    confidence = confidence,
    transactionKind = kindLabel.toTransactionKind(),
    amountMinor = amountMinor,
    currency = "CNY",
    merchantTitle = title,
    transactionTimeEpochMillis = parseReviewDateTime(transactionTimeText, zoneId)
        ?: capturedAtEpochMillis,
    capturedAtEpochMillis = capturedAtEpochMillis,
    suggestedCategoryId = category.toCategoryIdOrNull(),
    fundingAccountId = null,
    fundingAccountLabel = fundingAccountLabel.ifBlank { null },
    note = note,
    evidenceSummary = rawEvidenceText.ifBlank { null },
    parsedFieldsText = parsedFields.encodeParsedFields()
)

internal fun ReviewQueueIgnoredEntry.toEntity(zoneId: ZoneId): IgnoredEntryEntity {
    val pending = entry.toEntity(zoneId)
    return IgnoredEntryEntity(
        id = id,
        originalPendingEntryId = originalPendingId,
        source = pending.source,
        captureReason = pending.captureReason,
        confidence = pending.confidence,
        transactionKind = pending.transactionKind,
        amountMinor = pending.amountMinor,
        currency = pending.currency,
        merchantTitle = pending.merchantTitle,
        transactionTimeEpochMillis = pending.transactionTimeEpochMillis,
        capturedAtEpochMillis = pending.capturedAtEpochMillis,
        suggestedCategoryId = pending.suggestedCategoryId,
        fundingAccountId = pending.fundingAccountId,
        fundingAccountLabel = pending.fundingAccountLabel,
        note = pending.note,
        evidenceSummary = pending.evidenceSummary,
        parsedFieldsText = pending.parsedFieldsText,
        ignoredAtEpochMillis = ignoredAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
        reason = IgnoreReason.USER_IGNORED
    )
}

internal fun String.toCategoryIdOrNull(): String? = when (trim()) {
    "餐饮" -> "food"
    "交通" -> "transport"
    "购物" -> "shopping"
    "居住" -> "housing"
    "医疗健康" -> "healthcare"
    "工资" -> "salary"
    "退款" -> "refund"
    "未分类" -> "uncategorized"
    else -> null
}

private fun IgnoredEntryEntity.toPendingEntryEntity(): PendingEntryEntity = PendingEntryEntity(
    id = originalPendingEntryId,
    source = source,
    captureReason = captureReason,
    confidence = confidence,
    transactionKind = transactionKind,
    amountMinor = amountMinor,
    currency = currency,
    merchantTitle = merchantTitle,
    transactionTimeEpochMillis = transactionTimeEpochMillis,
    capturedAtEpochMillis = capturedAtEpochMillis,
    suggestedCategoryId = suggestedCategoryId,
    fundingAccountId = fundingAccountId,
    fundingAccountLabel = fundingAccountLabel,
    note = note,
    evidenceSummary = evidenceSummary,
    parsedFieldsText = parsedFieldsText
)

private fun PaymentSource.toLabel(): String = when (this) {
    PaymentSource.WECHAT -> "微信"
    PaymentSource.ALIPAY -> "支付宝"
}

private fun String.toPaymentSource(): PaymentSource = when (trim()) {
    "支付宝" -> PaymentSource.ALIPAY
    else -> PaymentSource.WECHAT
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

private fun String.toTransactionKind(): TransactionKind = when (trim()) {
    "收入" -> TransactionKind.INCOME
    "退款" -> TransactionKind.REFUND
    "转账" -> TransactionKind.TRANSFER
    "红包" -> TransactionKind.RED_PACKET
    "还款" -> TransactionKind.REPAYMENT
    "理财" -> TransactionKind.INVESTMENT
    "手续费" -> TransactionKind.FEE
    "其他" -> TransactionKind.OTHER
    else -> TransactionKind.EXPENSE
}

private fun CaptureReason.toLabel(): String = when (this) {
    CaptureReason.NOTIFICATION -> "通知捕获"
    CaptureReason.BILL_SYNC -> "账单同步"
    CaptureReason.DUPLICATE_MERGE -> "重复合并"
    CaptureReason.MANUAL_SAMPLE -> "手动样例"
}

private fun String.toCaptureReason(): CaptureReason = when (trim()) {
    "账单同步" -> CaptureReason.BILL_SYNC
    "重复合并" -> CaptureReason.DUPLICATE_MERGE
    "手动样例" -> CaptureReason.MANUAL_SAMPLE
    else -> CaptureReason.NOTIFICATION
}

private fun String.toCategoryLabel(): String = when (this) {
    "food" -> "餐饮"
    "transport" -> "交通"
    "shopping" -> "购物"
    "housing" -> "居住"
    "healthcare" -> "医疗健康"
    "salary" -> "工资"
    "refund" -> "退款"
    "uncategorized" -> "未分类"
    else -> this
}

private fun formatReviewDateTime(epochMillis: Long, zoneId: ZoneId): String =
    REVIEW_DATE_TIME_FORMATTER.withZone(zoneId).format(Instant.ofEpochMilli(epochMillis))

private fun parseReviewDateTime(text: String, zoneId: ZoneId): Long? = runCatching {
    java.time.LocalDateTime.parse(text.trim(), REVIEW_DATE_TIME_FORMATTER)
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()
}.getOrNull()

private fun List<String>.encodeParsedFields(): String? =
    takeIf { it.isNotEmpty() }?.joinToString(PARSED_FIELD_SEPARATOR)

private fun String?.decodeParsedFields(): List<String> =
    this?.takeIf { it.isNotBlank() }?.split(PARSED_FIELD_SEPARATOR).orEmpty()

private val REVIEW_DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private const val PARSED_FIELD_SEPARATOR = "\n"
