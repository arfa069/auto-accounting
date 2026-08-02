package com.autoaccounting.backend.ai

import kotlinx.coroutines.CancellationException

internal fun AiCategorizationPayload.safeLogExplanation(explanation: String): String =
    if (note != null || rawEvidenceText != null) {
        "增强上下文请求：解释未持久化"
    } else {
        explanation
    }

internal fun AiCategorizationSuggestion.validateAgainst(
    categoryCandidates: List<String>
): AiCategorizationSuggestion {
    val normalizedCategory = category.trim()
    val normalizedConfidence = confidenceLabel.trim()
    val normalizedExplanation = explanation.trim()
    if (normalizedCategory !in categoryCandidates) {
        throw AiCategorizationException(AiCategorizationError.PROVIDER_INVALID_RESPONSE)
    }
    if (normalizedConfidence !in setOf("低", "中", "高")) {
        throw AiCategorizationException(AiCategorizationError.PROVIDER_INVALID_RESPONSE)
    }
    normalizedExplanation.validateExplanation()
    return copy(
        category = normalizedCategory,
        confidenceLabel = normalizedConfidence,
        explanation = normalizedExplanation
    )
}

private fun String.validateExplanation() {
    if (isBlank() || length > MAX_EXPLANATION_LENGTH) {
        throw AiCategorizationException(AiCategorizationError.PROVIDER_INVALID_RESPONSE)
    }
}

internal fun RuntimeException.toAiCategorizationException(): AiCategorizationException {
    if (this is CancellationException) throw this
    val categoryError = (this as? AiProviderException)?.toCategorizationError()
        ?: AiCategorizationError.PROVIDER_ERROR
    return AiCategorizationException(categoryError)
}

private fun AiProviderException.toCategorizationError(): AiCategorizationError = when (this) {
    AiProviderException.Unavailable -> AiCategorizationError.PROVIDER_UNAVAILABLE
    AiProviderException.ConfigurationInvalid -> AiCategorizationError.PROVIDER_CONFIGURATION_INVALID
    AiProviderException.TimedOut -> AiCategorizationError.PROVIDER_TIMEOUT
    AiProviderException.RateLimited -> AiCategorizationError.PROVIDER_RATE_LIMITED
    AiProviderException.UpstreamFailure -> AiCategorizationError.PROVIDER_ERROR
    AiProviderException.InvalidResponse -> AiCategorizationError.PROVIDER_INVALID_RESPONSE
}
