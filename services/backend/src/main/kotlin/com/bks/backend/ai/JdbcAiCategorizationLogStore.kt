package com.bks.backend.ai

import com.bks.backend.jdbcConnection
import com.bks.backend.runBackendMigrations

class JdbcAiCategorizationLogStore(
    private val jdbcUrl: String,
    username: String = "",
    password: String = ""
) : AiCategorizationLogStore {
    private val storeUsername = username
    private val storePassword = password

    init {
        runBackendMigrations(jdbcUrl, username, password)
    }

    override fun insertLog(log: StoredAiCategorizationLog) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO ai_categorization_logs (
                    account_id, merchant_title, source_label,
                    transaction_kind, amount_range_label,
                    suggested_category, confidence_label, explanation,
                    created_at_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                if (log.accountId != null) {
                    statement.setLong(1, log.accountId)
                } else {
                    statement.setNull(1, java.sql.Types.BIGINT)
                }
                statement.setString(2, log.merchantTitle)
                statement.setString(3, log.sourceLabel)
                statement.setString(4, log.transactionKind)
                statement.setString(5, log.amountRangeLabel)
                statement.setString(6, log.suggestedCategory)
                statement.setString(7, log.confidenceLabel)
                statement.setString(8, log.explanation)
                statement.setLong(9, log.createdAtMillis)
                statement.executeUpdate()
            }
        }
    }

    override fun logsForAccount(accountId: Long): List<StoredAiCategorizationLog> = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT id, account_id, merchant_title, source_label,
                   transaction_kind, amount_range_label,
                   suggested_category, confidence_label, explanation,
                   created_at_millis
            FROM ai_categorization_logs
            WHERE account_id = ?
            ORDER BY id
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toStoredLog())
                }
            }
        }
    }

    override fun allLogs(): List<StoredAiCategorizationLog> = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT id, account_id, merchant_title, source_label,
                   transaction_kind, amount_range_label,
                   suggested_category, confidence_label, explanation,
                   created_at_millis
            FROM ai_categorization_logs
            ORDER BY id
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toStoredLog())
                }
            }
        }
    }

    override fun deleteLogsForAccount(accountId: Long) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                DELETE FROM ai_categorization_logs
                WHERE account_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.executeUpdate()
            }
        }
    }

    private fun connection() = jdbcConnection(jdbcUrl, storeUsername, storePassword)
}

private fun java.sql.ResultSet.toStoredLog(): StoredAiCategorizationLog {
    val accountIdVal = getLong("account_id")
    val accountId = if (wasNull()) null else accountIdVal
    return StoredAiCategorizationLog(
        id = getLong("id"),
        accountId = accountId,
        merchantTitle = getString("merchant_title"),
        sourceLabel = getString("source_label"),
        transactionKind = getString("transaction_kind"),
        amountRangeLabel = getString("amount_range_label"),
        suggestedCategory = getString("suggested_category"),
        confidenceLabel = getString("confidence_label"),
        explanation = getString("explanation"),
        createdAtMillis = getLong("created_at_millis")
    )
}
