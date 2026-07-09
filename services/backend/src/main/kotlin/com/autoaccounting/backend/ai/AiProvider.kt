package com.autoaccounting.backend.ai

/**
 * Provider seam for AI categorization suggestions.
 * Environment-configured: reads AUTO_ACCOUNTING_AI_PROVIDER and related keys.
 * Falls back to [MissingAiProvider] when unconfigured.
 */
interface AiProvider {
    fun suggest(payload: AiCategorizationPayload): AiCategorizationSuggestion
}

/**
 * Rule-based fallback that mimics the original hardcoded logic.
 * Used as the default when no external AI provider is configured.
 */
object RuleBasedAiProvider : AiProvider {
    override fun suggest(payload: AiCategorizationPayload): AiCategorizationSuggestion {
        val category = suggestCategory(payload)
        return AiCategorizationSuggestion(
            category = category,
            confidenceLabel = "中",
            explanation = "基于商户标题、交易类型和来源生成分类建议"
        )
    }

    private fun suggestCategory(payload: AiCategorizationPayload): String {
        val title = payload.merchantTitle.lowercase()
        return when {
            payload.categoryCandidates.any { it == "餐饮" } &&
                (title.contains("餐") || title.contains("咖啡") || title.contains("饭")) -> "餐饮"
            title.contains("地铁") || title.contains("公交") -> "交通"
            payload.categoryCandidates.isNotEmpty() -> payload.categoryCandidates.first()
            else -> "未分类"
        }
    }
}

/**
 * Safe fallback returned when the AI provider environment variable is missing or unrecognized.
 */
object MissingAiProvider : AiProvider {
    override fun suggest(payload: AiCategorizationPayload): AiCategorizationSuggestion {
        return AiCategorizationSuggestion(
            category = "未分类",
            confidenceLabel = "低",
            explanation = "AI服务未配置"
        )
    }
}

fun aiProviderFromEnvironment(env: Map<String, String> = System.getenv()): AiProvider {
    val provider = env["AUTO_ACCOUNTING_AI_PROVIDER"].orEmpty().lowercase()
    return when {
        provider.isBlank() -> RuleBasedAiProvider
        provider == "rule" -> RuleBasedAiProvider
        // Future: provider == "openai" -> OpenAiProvider.fromEnvironment(env)
        else -> MissingAiProvider
    }
}
