package com.autoaccounting.feature.categorization

import com.autoaccounting.feature.review.ReviewQueueEntry

data class CategorizationRule(
    val id: String,
    val merchantContains: String = "",
    val titleContains: String = "",
    val sourceLabel: String = "",
    val transactionKind: String = "",
    val category: String,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val updatedAtEpochMillis: Long = 0
)

data class CategorizationTransaction(
    val merchantTitle: String,
    val sourceLabel: String,
    val transactionKind: String
)

data class CategorizationSuggestion(
    val ruleId: String,
    val category: String
)

fun suggestCategory(
    rules: List<CategorizationRule>,
    transaction: CategorizationTransaction
): CategorizationSuggestion? = rules
    .asSequence()
    .filter { it.enabled && it.category.isNotBlank() }
    .filter { it.matches(transaction) }
    .sortedWith(
        compareByDescending<CategorizationRule> { it.priority }
            .thenByDescending { it.updatedAtEpochMillis }
    )
    .firstOrNull()
    ?.let { rule ->
        CategorizationSuggestion(
            ruleId = rule.id,
            category = rule.category
        )
    }

fun ReviewQueueEntry.applyCategorizationSuggestion(
    rules: List<CategorizationRule>
): ReviewQueueEntry {
    val suggestion = suggestCategory(
        rules = rules,
        transaction = CategorizationTransaction(
            merchantTitle = title,
            sourceLabel = sourceLabel,
            transactionKind = kindLabel
        )
    ) ?: return this

    return copy(category = suggestion.category)
}

private fun CategorizationRule.matches(transaction: CategorizationTransaction): Boolean {
    return merchantContains.matchesBlankOrContains(transaction.merchantTitle) &&
        titleContains.matchesBlankOrContains(transaction.merchantTitle) &&
        sourceLabel.matchesBlankOrEquals(transaction.sourceLabel) &&
        transactionKind.matchesBlankOrEquals(transaction.transactionKind)
}

private fun String.matchesBlankOrContains(candidate: String): Boolean {
    return isBlank() || candidate.contains(this, ignoreCase = true)
}

private fun String.matchesBlankOrEquals(candidate: String): Boolean {
    return isBlank() || equals(candidate, ignoreCase = true)
}
