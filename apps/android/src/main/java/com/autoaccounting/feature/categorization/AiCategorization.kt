package com.autoaccounting.feature.categorization

import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.review.ReviewQueueEntry

data class AiCategorizationSettings(
    val aiConsentGranted: Boolean = false,
    val enhancedContextGranted: Boolean = false
)

data class AiCategorizationPayload(
    val merchantTitle: String,
    val sourceLabel: String,
    val transactionKind: String,
    val amountRangeLabel: String,
    val categoryCandidates: List<String> = emptyList(),
    val note: String? = null,
    val rawEvidenceText: String? = null
)

data class AiCategorizationResponse(
    val category: String,
    val confidenceLabel: String,
    val explanation: String
)

enum class AiCategorizationSkipReason {
    REQUIRES_SIGNED_IN_ACCOUNT,
    REQUIRES_AI_CONSENT
}

data class AiCategorizationResult(
    val suggestion: AiCategorizationResponse? = null,
    val skipReason: AiCategorizationSkipReason? = null
)

data class AiCategorizationGatewayRequest(
    val token: String,
    val payload: AiCategorizationPayload
)

interface AiCategorizationGateway {
    fun suggestCategory(
        token: String,
        payload: AiCategorizationPayload
    ): AiCategorizationResponse
}

class AiCategorizationClient(
    private val gateway: AiCategorizationGateway
) {
    fun suggestCategory(
        entry: ReviewQueueEntry,
        session: AccountSession?,
        settings: AiCategorizationSettings,
        categoryCandidates: List<String> = emptyList()
    ): AiCategorizationResult {
        val signedIn = session as? AccountSession.SignedIn
            ?: return AiCategorizationResult(
                skipReason = AiCategorizationSkipReason.REQUIRES_SIGNED_IN_ACCOUNT
            )
        if (!settings.aiConsentGranted) {
            return AiCategorizationResult(
                skipReason = AiCategorizationSkipReason.REQUIRES_AI_CONSENT
            )
        }

        return AiCategorizationResult(
            suggestion = gateway.suggestCategory(
                token = signedIn.token,
                payload = entry.toAiPayload(
                    enhancedContextGranted = settings.enhancedContextGranted,
                    categoryCandidates = categoryCandidates
                )
            )
        )
    }
}

private fun ReviewQueueEntry.toAiPayload(
    enhancedContextGranted: Boolean,
    categoryCandidates: List<String>
): AiCategorizationPayload = AiCategorizationPayload(
    merchantTitle = title,
    sourceLabel = sourceLabel,
    transactionKind = kindLabel,
    amountRangeLabel = amountRangeLabel(amountMinor),
    categoryCandidates = categoryCandidates,
    note = note.takeIf { enhancedContextGranted },
    rawEvidenceText = rawEvidenceText.takeIf { enhancedContextGranted }
)

private fun amountRangeLabel(amountMinor: Long): String {
    val yuan = amountMinor / 100
    return when {
        yuan < 50 -> "0-50"
        yuan < 200 -> "50-200"
        yuan < 500 -> "200-500"
        yuan < 1000 -> "500-1000"
        else -> "1000+"
    }
}
