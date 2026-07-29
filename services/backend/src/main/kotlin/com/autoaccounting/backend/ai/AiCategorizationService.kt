@file:Suppress("LongParameterList")

package com.autoaccounting.backend.ai

import com.autoaccounting.api.AiCategorizationRequestContract
import com.autoaccounting.backend.account.JdbcAccountStore
import kotlinx.coroutines.CancellationException

internal const val MAX_MERCHANT_TITLE_LENGTH = 200
internal const val MAX_SOURCE_LABEL_LENGTH = 80
internal const val MAX_TRANSACTION_KIND_LENGTH = 40
internal const val MAX_AMOUNT_RANGE_LABEL_LENGTH = 32
internal const val MAX_CATEGORY_CANDIDATES = 50
internal const val MAX_CATEGORY_LENGTH = 80
internal const val MAX_NOTE_LENGTH = 500
internal const val MAX_RAW_EVIDENCE_LENGTH = 2_000
internal const val MAX_EXPLANATION_LENGTH = 240
private val ALLOWED_AMOUNT_RANGE_LABELS = setOf("0-50", "50-200", "200-500", "500-1000", "1000+")

data class AiCategorizationPayload(
    val merchantTitle: String,
    val sourceLabel: String,
    val transactionKind: String,
    val amountRangeLabel: String,
    val categoryCandidates: List<String>,
    val note: String? = null,
    val rawEvidenceText: String? = null
)

data class AiCategorizationSuggestion(
    val category: String,
    val confidenceLabel: String,
    val explanation: String
)

enum class AiCategorizationError(
    val message: String
) {
    INVALID_REQUEST("分类请求信息不完整或格式不正确"),
    CATEGORY_CANDIDATES_REQUIRED("暂无可用分类，请先创建或启用分类"),
    AI_CONSENT_REQUIRED("请先明确开启云端 AI 分类"),
    ENHANCED_CONTEXT_NOT_AUTHORIZED("增强上下文尚未获得授权"),
    PROVIDER_UNAVAILABLE("云端 AI 服务尚未配置"),
    PROVIDER_CONFIGURATION_INVALID("云端 AI 服务配置无效"),
    PROVIDER_TIMEOUT("云端 AI 响应超时，请稍后重试"),
    PROVIDER_RATE_LIMITED("云端 AI 请求过于频繁，请稍后重试"),
    PROVIDER_ERROR("云端 AI 服务暂时不可用，请稍后重试"),
    PROVIDER_INVALID_RESPONSE("云端 AI 返回了无效响应，请稍后重试")
}

class AiCategorizationException(
    val error: AiCategorizationError
) : RuntimeException(error.name)

class AiCategorizationService(
    private val provider: AiProvider = UnavailableAiProvider(),
    private val logStore: AiCategorizationLogStore = InMemoryAiCategorizationLogStore()
) {
    val logs: List<StoredAiCategorizationLog>
        get() = logStore.allLogs()

    suspend fun suggest(
        accountId: Long? = null,
        request: AiCategorizationRequestContract,
        enhancedContextAuthorized: Boolean
    ): AiCategorizationSuggestion {
        val payload = request.toValidatedPayload(enhancedContextAuthorized)
        val suggestion = try {
            provider.suggest(payload)
        } catch (error: AiProviderException) {
            throw AiCategorizationException(error.toCategorizationError())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            throw AiCategorizationException(AiCategorizationError.PROVIDER_ERROR)
        }
        val validatedSuggestion = suggestion.validateAgainst(payload.categoryCandidates)
        logStore.insertLog(
            StoredAiCategorizationLog(
                accountId = accountId,
                merchantTitle = payload.merchantTitle,
                sourceLabel = payload.sourceLabel,
                transactionKind = payload.transactionKind,
                amountRangeLabel = payload.amountRangeLabel,
                suggestedCategory = validatedSuggestion.category,
                confidenceLabel = validatedSuggestion.confidenceLabel,
                explanation = payload.safeLogExplanation(validatedSuggestion.explanation),
                createdAtMillis = System.currentTimeMillis()
            )
        )
        return validatedSuggestion
    }

    fun deleteLogsForAccount(accountId: Long) {
        logStore.deleteLogsForAccount(accountId)
    }

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AiCategorizationService {
            val jdbcConfig = JdbcAccountStore.configFromEnvironment(env)
                ?: error("AUTO_ACCOUNTING_DATABASE_URL is required for backend AI persistence.")
            return AiCategorizationService(
                provider = aiProviderFromEnvironment(env),
                logStore = JdbcAiCategorizationLogStore(
                    jdbcUrl = jdbcConfig.jdbcUrl,
                    username = jdbcConfig.username,
                    password = jdbcConfig.password
                )
            )
        }
    }
}

private fun AiCategorizationRequestContract.toValidatedPayload(
    enhancedContextAuthorized: Boolean
): AiCategorizationPayload {
    if (enhancedContext && !enhancedContextAuthorized) {
        throw AiCategorizationException(AiCategorizationError.ENHANCED_CONTEXT_NOT_AUTHORIZED)
    }
    if (!enhancedContext && (!note.isNullOrBlank() || !rawEvidenceText.isNullOrBlank())) {
        throw AiCategorizationException(AiCategorizationError.INVALID_REQUEST)
    }

    val candidates = categoryCandidates
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    if (candidates.isEmpty()) {
        throw AiCategorizationException(AiCategorizationError.CATEGORY_CANDIDATES_REQUIRED)
    }
    if (candidates.size > MAX_CATEGORY_CANDIDATES || candidates.any { it.length > MAX_CATEGORY_LENGTH }) {
        throw AiCategorizationException(AiCategorizationError.INVALID_REQUEST)
    }

    val validatedAmountRange = amountRangeLabel.requiredTrimmed(MAX_AMOUNT_RANGE_LABEL_LENGTH)
    if (validatedAmountRange !in ALLOWED_AMOUNT_RANGE_LABELS) {
        throw AiCategorizationException(AiCategorizationError.INVALID_REQUEST)
    }

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

private fun String.requiredTrimmed(maxLength: Int): String {
    val value = trim()
    if (value.isBlank() || value.length > maxLength) {
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

private fun AiCategorizationPayload.safeLogExplanation(explanation: String): String {
    return if (note != null || rawEvidenceText != null) {
        "增强上下文请求：解释未持久化"
    } else {
        explanation
    }
}

private fun AiCategorizationSuggestion.validateAgainst(
    categoryCandidates: List<String>
): AiCategorizationSuggestion {
    val category = category.trim()
    val confidence = confidenceLabel.trim()
    val safeExplanation = explanation.trim()
    if (
        category !in categoryCandidates ||
        confidence !in setOf("低", "中", "高") ||
        safeExplanation.isBlank() ||
        safeExplanation.length > MAX_EXPLANATION_LENGTH
    ) {
        throw AiCategorizationException(AiCategorizationError.PROVIDER_INVALID_RESPONSE)
    }
    return copy(
        category = category,
        confidenceLabel = confidence,
        explanation = safeExplanation
    )
}

private fun AiProviderException.toCategorizationError(): AiCategorizationError = when (this) {
    AiProviderException.Unavailable -> AiCategorizationError.PROVIDER_UNAVAILABLE
    AiProviderException.ConfigurationInvalid -> AiCategorizationError.PROVIDER_CONFIGURATION_INVALID
    AiProviderException.TimedOut -> AiCategorizationError.PROVIDER_TIMEOUT
    AiProviderException.RateLimited -> AiCategorizationError.PROVIDER_RATE_LIMITED
    AiProviderException.UpstreamFailure -> AiCategorizationError.PROVIDER_ERROR
    AiProviderException.InvalidResponse -> AiCategorizationError.PROVIDER_INVALID_RESPONSE
}
