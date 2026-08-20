package com.bks.backend.ai

import com.bks.api.AiCategorizationRequestContract

private val ALLOWED_AMOUNT_RANGE_LABELS = setOf("0-50", "50-200", "200-500", "500-1000", "1000+")

internal fun AiCategorizationRequestContract.toValidatedPayload(
    enhancedContextAuthorized: Boolean
): AiCategorizationPayload {
    validateContextAuthorization(enhancedContextAuthorized)

    val candidates = categoryCandidates
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    validateCategoryCandidates(candidates)

    val validatedAmountRange = amountRangeLabel.validatedAmountRange()

    return AiCategorizationPayload(
        merchantTitle = merchantTitle.requiredTrimmed(MAX_MERCHANT_TITLE_LENGTH),
        sourceLabel = sourceLabel.requiredTrimmed(MAX_SOURCE_LABEL_LENGTH),
        transactionKind = transactionKind.requiredTrimmed(MAX_TRANSACTION_KIND_LENGTH),
        amountRangeLabel = validatedAmountRange,
        categoryCandidates = candidates,
        note = note.optionalTrimmed(MAX_NOTE_LENGTH).takeIf { enhancedContext },
        rawEvidenceText = rawEvidenceText.optionalTrimmed(MAX_RAW_EVIDENCE_LENGTH)
            .takeIf { enhancedContext }
    )
}

private fun AiCategorizationRequestContract.validateContextAuthorization(
    enhancedContextAuthorized: Boolean
) {
    if (enhancedContext && !enhancedContextAuthorized) {
        throw AiCategorizationException(AiCategorizationError.ENHANCED_CONTEXT_NOT_AUTHORIZED)
    }
    if (!enhancedContext && hasOptionalContext()) {
        throw AiCategorizationException(AiCategorizationError.INVALID_REQUEST)
    }
}

private fun AiCategorizationRequestContract.hasOptionalContext(): Boolean =
    !note.isNullOrBlank() || !rawEvidenceText.isNullOrBlank()

private fun validateCategoryCandidates(candidates: List<String>) {
    if (candidates.isEmpty()) {
        throw AiCategorizationException(AiCategorizationError.CATEGORY_CANDIDATES_REQUIRED)
    }
    if (candidates.size > MAX_CATEGORY_CANDIDATES || candidates.any { it.length > MAX_CATEGORY_LENGTH }) {
        throw AiCategorizationException(AiCategorizationError.INVALID_REQUEST)
    }
}

private fun String.requiredTrimmed(maxLength: Int): String {
    val value = trim()
    if (value.isBlank() || value.length > maxLength) {
        throw AiCategorizationException(AiCategorizationError.INVALID_REQUEST)
    }
    return value
}

private fun String.validatedAmountRange(): String {
    val value = requiredTrimmed(MAX_AMOUNT_RANGE_LABEL_LENGTH)
    if (value !in ALLOWED_AMOUNT_RANGE_LABELS) {
        throw AiCategorizationException(AiCategorizationError.INVALID_REQUEST)
    }
    return value
}

private fun String?.optionalTrimmed(maxLength: Int): String? {
    val value = this?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (value.length > maxLength) {
        throw AiCategorizationException(AiCategorizationError.INVALID_REQUEST)
    }
    return value
}
