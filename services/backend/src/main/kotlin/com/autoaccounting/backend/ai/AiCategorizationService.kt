@file:Suppress("LongParameterList")

package com.autoaccounting.backend.ai

import com.autoaccounting.backend.account.JdbcAccountStore

data class AiCategorizationPayload(
    val merchantTitle: String,
    val sourceLabel: String,
    val transactionKind: String,
    val amountRangeLabel: String,
    val categoryCandidates: List<String> = emptyList(),
    val note: String? = null,
    val rawEvidenceText: String? = null
)

data class AiCategorizationSuggestion(
    val category: String,
    val confidenceLabel: String,
    val explanation: String
)

class AiCategorizationService(
    private val provider: AiProvider = RuleBasedAiProvider,
    private val logStore: AiCategorizationLogStore = InMemoryAiCategorizationLogStore()
) {
    /** All logs (read-only snapshot). */
    val logs: List<StoredAiCategorizationLog>
        get() = logStore.allLogs()

    fun suggest(
        accountId: Long? = null,
        merchantTitle: String,
        sourceLabel: String,
        transactionKind: String,
        amountMinor: Long,
        categoryCandidates: List<String>,
        note: String?,
        rawEvidenceText: String?,
        enhancedContext: Boolean
    ): AiCategorizationSuggestion {
        val payload = AiCategorizationPayload(
            merchantTitle = merchantTitle.trim(),
            sourceLabel = sourceLabel.trim(),
            transactionKind = transactionKind.trim(),
            amountRangeLabel = amountRangeLabel(amountMinor),
            categoryCandidates = categoryCandidates,
            note = note?.trim().takeIf { enhancedContext && !it.isNullOrBlank() },
            rawEvidenceText = rawEvidenceText?.trim().takeIf { enhancedContext && !it.isNullOrBlank() }
        )
        val suggestion = provider.suggest(payload)
        logStore.insertLog(
            StoredAiCategorizationLog(
                accountId = accountId,
                merchantTitle = payload.merchantTitle,
                sourceLabel = payload.sourceLabel,
                transactionKind = payload.transactionKind,
                amountRangeLabel = payload.amountRangeLabel,
                suggestedCategory = suggestion.category,
                confidenceLabel = suggestion.confidenceLabel,
                explanation = suggestion.explanation,
                createdAtMillis = System.currentTimeMillis()
            )
        )
        return suggestion
    }

    fun deleteLogsForAccount(accountId: Long) {
        logStore.deleteLogsForAccount(accountId)
    }

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
