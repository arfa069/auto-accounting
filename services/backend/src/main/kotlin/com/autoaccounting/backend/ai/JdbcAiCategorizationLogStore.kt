package com.autoaccounting.backend.ai

import com.autoaccounting.backend.jdbcConnection
import com.autoaccounting.backend.runBackendMigrations

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
                ) VALUES (
                    (SELECT account_id FROM account_phone_credentials WHERE phone = ?),
                    ?, ?, ?, ?, ?, ?, ?, ?
                )
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, log.accountPhone)
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

    override fun logsForAccount(phone: String): List<StoredAiCategorizationLog> = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT l.id, p.phone AS account_phone, l.merchant_title, l.source_label,
                   l.transaction_kind, l.amount_range_label,
                   l.suggested_category, l.confidence_label, l.explanation,
                   l.created_at_millis
            FROM ai_categorization_logs l
            JOIN account_phone_credentials p ON p.account_id = l.account_id
            WHERE p.phone = ?
            ORDER BY l.id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, phone)
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
            SELECT l.id, p.phone AS account_phone, l.merchant_title, l.source_label,
                   l.transaction_kind, l.amount_range_label,
                   l.suggested_category, l.confidence_label, l.explanation,
                   l.created_at_millis
            FROM ai_categorization_logs l
            LEFT JOIN account_phone_credentials p ON p.account_id = l.account_id
            ORDER BY l.id
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toStoredLog())
                }
            }
        }
    }

    override fun deleteLogsForAccount(phone: String) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                DELETE FROM ai_categorization_logs
                WHERE account_id = (SELECT account_id FROM account_phone_credentials WHERE phone = ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, phone)
                statement.executeUpdate()
            }
        }
    }

    private fun connection() = jdbcConnection(jdbcUrl, storeUsername, storePassword)
}

private fun java.sql.ResultSet.toStoredLog(): StoredAiCategorizationLog {
    return StoredAiCategorizationLog(
        id = getLong("id"),
        accountPhone = getString("account_phone"),
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

