package com.bks.feature.sync

import com.bks.api.LedgerSyncEntityTypeContract
import com.bks.api.LedgerSyncPayloadContract
import com.bks.data.local.DefaultCategorizationRules
import com.bks.data.local.DefaultCategories
import com.bks.data.local.EntryOrigin
import com.bks.data.local.FlowDirection
import com.bks.data.local.FundingAccountEntity
import com.bks.data.local.FundingAccountSourceScope
import com.bks.data.local.LedgerBookEntity
import com.bks.data.local.LedgerEntryEntity
import com.bks.data.local.CategorizationRuleEntity
import com.bks.data.local.CategoryEntity
import com.bks.data.local.PaymentSource
import com.bks.data.local.TransactionKind

internal fun LedgerSyncPayloadContract.isPristineGeneratedDefault(): Boolean = when (this) {
    is LedgerSyncPayloadContract.Category -> matchesDefaultCategory()
    is LedgerSyncPayloadContract.CategorizationRule -> matchesDefaultRule()
    else -> false
}

private fun LedgerSyncPayloadContract.Category.matchesDefaultCategory(): Boolean =
    DefaultCategories.systemDefaults(createdAtMillis).any { default ->
        default.id == id &&
            default.name == name &&
            default.kind?.name == kind &&
            default.sortOrder == sortOrder &&
            default.isSystem == isSystem
    }

private fun LedgerSyncPayloadContract.CategorizationRule.matchesDefaultRule(): Boolean =
    DefaultCategorizationRules.rules.any { default ->
        default.id == id &&
            default.merchantContains == merchantContains &&
            default.titleContains == titleContains &&
            default.sourceLabel == sourceLabel &&
            default.transactionKind == transactionKind &&
            default.category == category &&
            default.priority == priority &&
            default.enabled == enabled &&
            default.updatedAtEpochMillis == updatedAtMillis
    }

internal fun LedgerSyncPayloadContract.entityType(): LedgerSyncEntityTypeContract = when (this) {
    is LedgerSyncPayloadContract.Category -> LedgerSyncEntityTypeContract.CATEGORY
    is LedgerSyncPayloadContract.FundingAccount -> LedgerSyncEntityTypeContract.FUNDING_ACCOUNT
    is LedgerSyncPayloadContract.LedgerBook -> LedgerSyncEntityTypeContract.LEDGER_BOOK
    is LedgerSyncPayloadContract.LedgerEntry -> LedgerSyncEntityTypeContract.LEDGER_ENTRY
    is LedgerSyncPayloadContract.CategorizationRule -> LedgerSyncEntityTypeContract.CATEGORIZATION_RULE
}

internal fun LedgerSyncPayloadContract.entityId(): String = when (this) {
    is LedgerSyncPayloadContract.Category -> id
    is LedgerSyncPayloadContract.FundingAccount -> syncId
    is LedgerSyncPayloadContract.LedgerBook -> id
    is LedgerSyncPayloadContract.LedgerEntry -> id
    is LedgerSyncPayloadContract.CategorizationRule -> id
}

internal fun LedgerSyncPayloadContract.Category.toEntity(nameOverride: String = name) = CategoryEntity(
    id, nameOverride, kind?.let(TransactionKind::valueOf), sortOrder, isSystem, createdAtMillis
)

internal fun LedgerSyncPayloadContract.FundingAccount.toEntity(labelOverride: String = label) = FundingAccountEntity(
    syncId = syncId,
    sourceScope = FundingAccountSourceScope.valueOf(sourceScope),
    paymentSource = paymentSource?.let(PaymentSource::valueOf),
    label = labelOverride,
    createdAtEpochMillis = createdAtMillis
)

internal fun LedgerSyncPayloadContract.LedgerBook.toEntity() = LedgerBookEntity(id, name, createdAtMillis)

internal fun LedgerSyncPayloadContract.LedgerEntry.toEntity(
    existing: LedgerEntryEntity?,
    fundingAccountId: Long?
) = LedgerEntryEntity(
    id = id,
    ledgerBookId = ledgerBookId,
    paymentSource = paymentSource?.let(PaymentSource::valueOf),
    originalCaptureSource = originalCaptureSource?.let(PaymentSource::valueOf),
    entryOrigin = EntryOrigin.valueOf(entryOrigin),
    originPendingEntryId = existing?.originPendingEntryId,
    flowDirection = FlowDirection.valueOf(flowDirection),
    transactionKind = TransactionKind.valueOf(transactionKind),
    amountMinor = amountMinor,
    currency = currency,
    merchantTitle = merchantTitle,
    transactionTimeEpochMillis = transactionTimeMillis,
    categoryId = categoryId,
    fundingAccountId = fundingAccountId,
    note = note,
    evidenceSummary = existing?.evidenceSummary,
    parsedFieldsText = existing?.parsedFieldsText,
    confirmedAtEpochMillis = confirmedAtMillis,
    updatedAtEpochMillis = updatedAtMillis,
    deletedAtEpochMillis = deletedAtMillis
)

internal fun LedgerSyncPayloadContract.CategorizationRule.toEntity() = CategorizationRuleEntity(
    id, merchantContains, titleContains, sourceLabel, transactionKind, category,
    priority, enabled, updatedAtMillis
)
