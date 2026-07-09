package com.autoaccounting.backend.ai

data class StoredAiCategorizationLog(
    val id: Long = 0,
    val accountPhone: String?,
    val merchantTitle: String,
    val sourceLabel: String,
    val transactionKind: String,
    val amountRangeLabel: String,
    val suggestedCategory: String,
    val confidenceLabel: String,
    val explanation: String,
    val createdAtMillis: Long
)

interface AiCategorizationLogStore {
    fun insertLog(log: StoredAiCategorizationLog)
    fun logsForAccount(phone: String): List<StoredAiCategorizationLog>
    fun allLogs(): List<StoredAiCategorizationLog>
    fun deleteLogsForAccount(phone: String)
}

class InMemoryAiCategorizationLogStore : AiCategorizationLogStore {
    private val logs = mutableListOf<StoredAiCategorizationLog>()

    override fun insertLog(log: StoredAiCategorizationLog) {
        logs += log
    }

    override fun logsForAccount(phone: String): List<StoredAiCategorizationLog> {
        return logs.filter { it.accountPhone == phone }
    }

    override fun allLogs(): List<StoredAiCategorizationLog> = logs.toList()

    override fun deleteLogsForAccount(phone: String) {
        logs.removeAll { it.accountPhone == phone }
    }
}
