package com.autoaccounting.feature.settings

import com.autoaccounting.data.local.CategorizationRuleEntity
import com.autoaccounting.data.local.CaptureReason
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.data.local.IgnoreReason
import com.autoaccounting.data.local.IgnoredEntryEntity
import com.autoaccounting.data.local.LocalSettingsEntity
import com.autoaccounting.data.local.DEFAULT_LEDGER_BOOK_ID
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.TransactionKind
import java.io.DataInputStream
import java.io.DataOutputStream

internal fun DataOutputStream.writeIgnoredEntry(entry: IgnoredEntryEntity) {
    writeString(entry.id)
    writeString(entry.originalPendingEntryId)
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
    writeLong(entry.ignoredAtEpochMillis)
    writeLong(entry.expiresAtEpochMillis)
    writeString(entry.reason.name)
    writeNullableString(entry.suggestedCategoryLabel)
}

internal fun DataInputStream.readIgnoredEntry(): IgnoredEntryEntity = IgnoredEntryEntity(
    id = readString(),
    originalPendingEntryId = readString(),
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
    ignoredAtEpochMillis = readLong(),
    expiresAtEpochMillis = readLong(),
    reason = IgnoreReason.valueOf(readString()),
    suggestedCategoryLabel = readNullableString()
)

internal fun DataOutputStream.writeCategorizationRule(rule: CategorizationRuleEntity) {
    writeString(rule.id)
    writeString(rule.merchantContains)
    writeString(rule.titleContains)
    writeString(rule.sourceLabel)
    writeString(rule.transactionKind)
    writeString(rule.category)
    writeInt(rule.priority)
    writeBoolean(rule.enabled)
    writeLong(rule.updatedAtEpochMillis)
}

internal fun DataInputStream.readCategorizationRule(): CategorizationRuleEntity =
    CategorizationRuleEntity(
        id = readString(),
        merchantContains = readString(),
        titleContains = readString(),
        sourceLabel = readString(),
        transactionKind = readString(),
        category = readString(),
        priority = readInt(),
        enabled = readBoolean(),
        updatedAtEpochMillis = readLong()
    )

internal fun DataOutputStream.writeSettings(settings: LocalSettingsEntity) {
    writeString(settings.id)
    writeBoolean(settings.aiConsentGranted)
    writeBoolean(settings.enhancedContextGranted)
    writeBoolean(settings.continuousBillSyncCompleted)
    writeBoolean(settings.continuousMonitoringEnabled)
    writeString(settings.activeLedgerId)
}

internal fun DataInputStream.readSettingsV4(): LocalSettingsEntity = LocalSettingsEntity(
    id = readString(),
    aiConsentGranted = readBoolean(),
    enhancedContextGranted = readBoolean(),
    continuousBillSyncCompleted = readBoolean(),
    continuousMonitoringEnabled = readBoolean(),
    activeLedgerId = readString()
)

internal fun DataInputStream.readSettingsV3(): LocalSettingsEntity = LocalSettingsEntity(
    id = readString(),
    aiConsentGranted = readBoolean(),
    enhancedContextGranted = readBoolean(),
    continuousBillSyncCompleted = readBoolean(),
    continuousMonitoringEnabled = readBoolean(),
    activeLedgerId = DEFAULT_LEDGER_BOOK_ID
)
