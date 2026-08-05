package com.autoaccounting.feature.settings

import com.autoaccounting.data.local.CaptureReason
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.data.local.EntryOrigin
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.FundingAccountSourceScope
import com.autoaccounting.data.local.LedgerEntryEntity
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.PendingEntryEntity
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.data.local.defaultFlowDirection
import java.io.DataInputStream
import java.io.DataOutputStream

internal fun DataOutputStream.writePendingEntry(entry: PendingEntryEntity) {
    writeString(entry.id)
    writeString(entry.source.name)
    writeString(entry.captureReason.name)
    writeString(entry.confidence.name)
    writeString(entry.transactionKind.name)
    writeLong(entry.amountMinor)
    writeString(entry.currency)
    writeString(entry.merchantTitle)
    writeLong(entry.transactionTimeEpochMillis)
    writeLong(entry.capturedAtEpochMillis)
    writeNullableString(entry.suggestedCategoryId)
    writeNullableLong(entry.fundingAccountId)
    writeNullableString(entry.fundingAccountLabel)
    writeNullableString(entry.note)
    writeNullableString(entry.evidenceSummary)
    writeNullableString(entry.parsedFieldsText)
    writeNullableString(entry.suggestedCategoryLabel)
}

internal fun DataInputStream.readPendingEntry(): PendingEntryEntity = PendingEntryEntity(
    id = readString(),
    source = PaymentSource.valueOf(readString()),
    captureReason = CaptureReason.valueOf(readString()),
    confidence = ConfidenceState.valueOf(readString()),
    transactionKind = TransactionKind.valueOf(readString()),
    amountMinor = readLong(),
    currency = readString(),
    merchantTitle = readString(),
    transactionTimeEpochMillis = readLong(),
    capturedAtEpochMillis = readLong(),
    suggestedCategoryId = readNullableString(),
    fundingAccountId = readNullableLong(),
    fundingAccountLabel = readNullableString(),
    note = readNullableString(),
    evidenceSummary = readNullableString(),
    parsedFieldsText = readNullableString(),
    suggestedCategoryLabel = readNullableString()
)

internal fun DataOutputStream.writeLedgerEntryV4(entry: LedgerEntryEntity) {
    writeString(entry.id)
    writeString(entry.ledgerBookId)
    writeNullableString(entry.paymentSource?.name)
    writeNullableString(entry.originalCaptureSource?.name)
    writeString(entry.entryOrigin.name)
    writeNullableString(entry.originPendingEntryId)
    writeString(entry.flowDirection.name)
    writeString(entry.transactionKind.name)
    writeLong(entry.amountMinor)
    writeString(entry.currency)
    writeString(entry.merchantTitle)
    writeLong(entry.transactionTimeEpochMillis)
    writeNullableString(entry.categoryId)
    writeNullableLong(entry.fundingAccountId)
    writeNullableString(entry.note)
    writeNullableString(entry.evidenceSummary)
    writeNullableString(entry.parsedFieldsText)
    writeLong(entry.confirmedAtEpochMillis)
    writeLong(entry.updatedAtEpochMillis)
    writeNullableLong(entry.deletedAtEpochMillis)
}

internal fun DataInputStream.readLedgerEntryV4(): LedgerEntryEntity = LedgerEntryEntity(
    id = readString(),
    ledgerBookId = readString(),
    paymentSource = readNullableString()?.let(PaymentSource::valueOf),
    originalCaptureSource = readNullableString()?.let(PaymentSource::valueOf),
    entryOrigin = EntryOrigin.valueOf(readString()),
    originPendingEntryId = readNullableString(),
    flowDirection = FlowDirection.valueOf(readString()),
    transactionKind = TransactionKind.valueOf(readString()),
    amountMinor = readLong(),
    currency = readString(),
    merchantTitle = readString(),
    transactionTimeEpochMillis = readLong(),
    categoryId = readNullableString(),
    fundingAccountId = readNullableLong(),
    note = readNullableString(),
    evidenceSummary = readNullableString(),
    parsedFieldsText = readNullableString(),
    confirmedAtEpochMillis = readLong(),
    updatedAtEpochMillis = readLong(),
    deletedAtEpochMillis = readNullableLong()
)

internal fun DataInputStream.readLedgerEntryV3(): LedgerEntryEntity = LedgerEntryEntity(
    id = readString(),
    paymentSource = readNullableString()?.let(PaymentSource::valueOf),
    originalCaptureSource = readNullableString()?.let(PaymentSource::valueOf),
    entryOrigin = EntryOrigin.valueOf(readString()),
    originPendingEntryId = readNullableString(),
    flowDirection = FlowDirection.valueOf(readString()),
    transactionKind = TransactionKind.valueOf(readString()),
    amountMinor = readLong(),
    currency = readString(),
    merchantTitle = readString(),
    transactionTimeEpochMillis = readLong(),
    categoryId = readNullableString(),
    fundingAccountId = readNullableLong(),
    note = readNullableString(),
    evidenceSummary = readNullableString(),
    parsedFieldsText = readNullableString(),
    confirmedAtEpochMillis = readLong(),
    updatedAtEpochMillis = readLong(),
    deletedAtEpochMillis = readNullableLong()
)

internal fun DataInputStream.readLedgerEntryV2(): LedgerEntryEntity {
    val id = readString()
    val source = PaymentSource.valueOf(readString())
    val originPendingEntryId = readNullableString()
    val transactionKind = TransactionKind.valueOf(readString())
    val amountMinor = readLong()
    val currency = readString()
    val merchantTitle = readString()
    val transactionTimeEpochMillis = readLong()
    val categoryId = readNullableString()
    val fundingAccountId = readNullableLong()
    val note = readNullableString()
    val confirmedAtEpochMillis = readLong()
    return LedgerEntryEntity(
        id = id,
        paymentSource = source,
        originalCaptureSource = source,
        entryOrigin = EntryOrigin.LEGACY_CAPTURE,
        originPendingEntryId = originPendingEntryId,
        flowDirection = transactionKind.defaultFlowDirection(),
        transactionKind = transactionKind,
        amountMinor = amountMinor,
        currency = currency,
        merchantTitle = merchantTitle,
        transactionTimeEpochMillis = transactionTimeEpochMillis,
        categoryId = categoryId,
        fundingAccountId = fundingAccountId,
        note = note,
        evidenceSummary = null,
        parsedFieldsText = null,
        confirmedAtEpochMillis = confirmedAtEpochMillis,
        updatedAtEpochMillis = confirmedAtEpochMillis,
        deletedAtEpochMillis = null
    )
}

internal fun PaymentSource.toScope(): FundingAccountSourceScope = when (this) {
    PaymentSource.WECHAT -> FundingAccountSourceScope.WECHAT
    PaymentSource.ALIPAY -> FundingAccountSourceScope.ALIPAY
}
