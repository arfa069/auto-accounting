package com.autoaccounting.backend.ai

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

data class AiCategorizationLog(
    val accountPhone: String?,
    val payload: AiCategorizationPayload,
    val suggestion: AiCategorizationSuggestion
)

class AiCategorizationService {
    private val mutableLogs = mutableListOf<AiCategorizationLog>()

    val logs: List<AiCategorizationLog>
        get() = mutableLogs.toList()

    fun suggest(
        accountPhone: String? = null,
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
        val suggestion = AiCategorizationSuggestion(
            category = suggestCategory(payload),
            confidenceLabel = "中",
            explanation = "基于商户标题、交易类型和来源生成分类建议"
        )
        mutableLogs += AiCategorizationLog(accountPhone, payload, suggestion)
        return suggestion
    }

    fun deleteLogsForAccount(phone: String) {
        mutableLogs.removeAll { it.accountPhone == phone }
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
}
