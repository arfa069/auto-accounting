package com.autoaccounting.feature.settings

import com.autoaccounting.data.local.LOCAL_SETTINGS_ID

internal fun PersistedLocalDataSnapshot.validated(): PersistedLocalDataSnapshot = apply {
    val categoryIds = validateCategories()
    val fundingAccountIds = validateFundingAccounts()
    val ledgerBookIds = validateLedgerBooks()
    validatePendingEntries(categoryIds, fundingAccountIds)
    validateLedgerEntries(categoryIds, fundingAccountIds, ledgerBookIds)
    validateIgnoredEntries(categoryIds, fundingAccountIds)
    validateCategorizationRules()
    validateSettings(ledgerBookIds)
}

private fun PersistedLocalDataSnapshot.validateCategories(): Set<String> {
    require(categories.map { it.id }.allDistinct()) { "Backup contains duplicate categories" }
    require(categories.map { it.name }.allDistinct()) {
        "Backup contains duplicate category names"
    }
    require(categories.all { it.id.isNotBlank() && it.name.isNotBlank() }) {
        "Backup contains an invalid category"
    }
    return categories.mapTo(mutableSetOf()) { it.id }
}

private fun PersistedLocalDataSnapshot.validateFundingAccounts(): Set<Long> {
    require(fundingAccounts.map { it.id }.allDistinct()) { "Backup contains duplicate funding accounts" }
    require(fundingAccounts.all { it.id >= 0 && it.label.isNotBlank() }) {
        "Backup contains an invalid funding account"
    }
    require(
        fundingAccounts
            .map { it.sourceScope to it.label }
            .allDistinct()
    ) { "Backup contains duplicate funding account names for a payment source" }
    return fundingAccounts.mapTo(mutableSetOf()) { it.id }
}

private fun PersistedLocalDataSnapshot.validateLedgerBooks(): Set<String> {
    require(ledgerBooks.isNotEmpty()) { "Backup must contain at least one ledger book" }
    require(ledgerBooks.map { it.id }.allDistinct()) {
        "Backup contains duplicate ledger books"
    }
    require(ledgerBooks.map { it.name }.allDistinct()) {
        "Backup contains duplicate ledger book names"
    }
    require(ledgerBooks.all {
        it.id.isNotBlank() &&
            it.name.isNotBlank() &&
            it.name == it.name.trim() &&
            it.createdAtEpochMillis >= 0
    }) { "Backup contains an invalid ledger book" }
    return ledgerBooks.mapTo(mutableSetOf()) { it.id }
}

private fun PersistedLocalDataSnapshot.validatePendingEntries(
    categoryIds: Set<String>,
    fundingAccountIds: Set<Long>
) {
    require(pendingEntries.map { it.id }.allDistinct()) { "Backup contains duplicate pending entries" }
    require(pendingEntries.all { entry ->
        entry.id.isNotBlank() &&
            entry.amountMinor > 0 &&
            entry.currency == SUPPORTED_BACKUP_CURRENCY &&
            entry.transactionTimeEpochMillis >= 0 &&
            entry.capturedAtEpochMillis >= 0 &&
            (entry.suggestedCategoryId == null || entry.suggestedCategoryId in categoryIds) &&
            (entry.fundingAccountId == null || entry.fundingAccountId in fundingAccountIds)
    }) { "Backup contains an invalid pending entry" }
}

private fun PersistedLocalDataSnapshot.validateLedgerEntries(
    categoryIds: Set<String>,
    fundingAccountIds: Set<Long>,
    ledgerBookIds: Set<String>
) {
    require(ledgerEntries.map { it.id }.allDistinct()) { "Backup contains duplicate ledger entries" }
    require(ledgerEntries.all { entry ->
        entry.id.isNotBlank() &&
            entry.amountMinor > 0 &&
            entry.currency == SUPPORTED_BACKUP_CURRENCY &&
            entry.transactionTimeEpochMillis >= 0 &&
            entry.confirmedAtEpochMillis >= 0 &&
            entry.updatedAtEpochMillis >= entry.confirmedAtEpochMillis &&
            (entry.deletedAtEpochMillis == null || entry.deletedAtEpochMillis >= entry.confirmedAtEpochMillis) &&
            entry.ledgerBookId in ledgerBookIds &&
            (entry.categoryId == null || entry.categoryId in categoryIds) &&
            (entry.fundingAccountId == null || entry.fundingAccountId in fundingAccountIds)
    }) { "Backup contains an invalid ledger entry" }
}

private fun PersistedLocalDataSnapshot.validateIgnoredEntries(
    categoryIds: Set<String>,
    fundingAccountIds: Set<Long>
) {
    require(ignoredEntries.map { it.id }.allDistinct()) { "Backup contains duplicate ignored entries" }
    require(ignoredEntries.all {
        it.id.isNotBlank() &&
            it.ignoredAtEpochMillis >= 0 &&
            (it.suggestedCategoryId == null || it.suggestedCategoryId in categoryIds) &&
            (it.fundingAccountId == null || it.fundingAccountId in fundingAccountIds)
    }) {
        "Backup contains an invalid ignored entry"
    }
}

private fun PersistedLocalDataSnapshot.validateCategorizationRules() {
    require(categorizationRules.map { it.id }.allDistinct()) {
        "Backup contains duplicate categorization rules"
    }
    require(categorizationRules.all {
        it.id.isNotBlank() && it.category.isNotBlank() && it.updatedAtEpochMillis >= 0
    }) { "Backup contains an invalid categorization rule" }
}

private fun PersistedLocalDataSnapshot.validateSettings(ledgerBookIds: Set<String>) {
    require(
        settings != null &&
            settings.id == LOCAL_SETTINGS_ID &&
            settings.activeLedgerId in ledgerBookIds &&
            (settings.aiConsentGranted || !settings.enhancedContextGranted)
    ) { "Backup contains invalid local settings" }
}

private fun <T> List<T>.allDistinct(): Boolean = size == toSet().size

internal const val SUPPORTED_BACKUP_CURRENCY = "CNY"
