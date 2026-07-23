package com.autoaccounting.backend.ai

data class StoredAiCategorizationLog(
    val id: Long = 0,
    val accountId: Long? = null,
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
    fun logsForAccount(accountId: Long): List<StoredAiCategorizationLog>
    fun allLogs(): List<StoredAiCategorizationLog>
    fun deleteLogsForAccount(accountId: Long)
}

class InMemoryAiCategorizationLogStore : AiCategorizationLogStore {
    private val logs = mutableListOf<StoredAiCategorizationLog>()

    override fun insertLog(log: StoredAiCategorizationLog) {
        logs += log
    }

    override fun logsForAccount(accountId: Long): List<StoredAiCategorizationLog> {
        return logs.filter { it.accountId == accountId }
    }

    override fun allLogs(): List<StoredAiCategorizationLog> = logs.toList()

    override fun deleteLogsForAccount(accountId: Long) {
        logs.removeAll { it.accountId == accountId }
    }
}
