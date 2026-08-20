package com.bks.backend.ai

import com.bks.api.AiCategorizationRequestContract
import com.bks.backend.account.JdbcAccountStore

internal const val MAX_MERCHANT_TITLE_LENGTH = 200
internal const val MAX_SOURCE_LABEL_LENGTH = 80
internal const val MAX_TRANSACTION_KIND_LENGTH = 40
internal const val MAX_AMOUNT_RANGE_LABEL_LENGTH = 32
internal const val MAX_CATEGORY_CANDIDATES = 50
internal const val MAX_CATEGORY_LENGTH = 80
internal const val MAX_NOTE_LENGTH = 500
internal const val MAX_RAW_EVIDENCE_LENGTH = 2_000
internal const val MAX_EXPLANATION_LENGTH = 240

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
        } catch (error: RuntimeException) {
            throw error.toAiCategorizationException()
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
                ?: error("BKS_DATABASE_URL is required for backend AI persistence.")
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
