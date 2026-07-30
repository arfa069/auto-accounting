package com.autoaccounting.backend.ai

/** Provider boundary for optional cloud AI categorization suggestions. */
interface AiProvider {
    suspend fun suggest(payload: AiCategorizationPayload): AiCategorizationSuggestion
}

/** Explicit deterministic test provider; never selected from runtime environment. */
object RuleBasedAiProvider : AiProvider {
    override suspend fun suggest(payload: AiCategorizationPayload): AiCategorizationSuggestion {
        val title = payload.merchantTitle.lowercase()
        val category = when {
            payload.categoryCandidates.any { it == "餐饮" } &&
                (title.contains("餐") || title.contains("咖啡") || title.contains("饭")) -> "餐饮"
            payload.categoryCandidates.any { it == "交通" } &&
                (title.contains("地铁") || title.contains("公交")) -> "交通"
            else -> payload.categoryCandidates.first()
        }
        return AiCategorizationSuggestion(
            category = category,
            confidenceLabel = "中",
            explanation = "基于商户标题、交易类型和来源生成分类建议"
        )
    }
}

sealed class AiProviderException : RuntimeException() {
    data object Unavailable : AiProviderException()
    data object ConfigurationInvalid : AiProviderException()
    data object TimedOut : AiProviderException()
    data object RateLimited : AiProviderException()
    data object UpstreamFailure : AiProviderException()
    data object InvalidResponse : AiProviderException()
}

/** Missing or invalid production configuration must fail closed, never mimic a successful AI call. */
class UnavailableAiProvider(
    private val failure: AiProviderException = AiProviderException.Unavailable
) : AiProvider {
    override suspend fun suggest(payload: AiCategorizationPayload): AiCategorizationSuggestion {
        throw failure
    }
}

fun aiProviderFromEnvironment(env: Map<String, String> = System.getenv()): AiProvider {
    return when (env["AUTO_ACCOUNTING_AI_PROTOCOL"].orEmpty().trim().lowercase()) {
        "openai-responses" -> OpenAiProvider.fromEnvironment(env)
        "openai-chat-completions" -> DeepSeekProvider.fromEnvironment(env)
        "anthropic-messages" -> AnthropicProvider.fromEnvironment(env)
        "" -> UnavailableAiProvider()
        else -> UnavailableAiProvider(AiProviderException.ConfigurationInvalid)
    }
}
