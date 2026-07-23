@file:Suppress("NestedBlockDepth")

package com.autoaccounting.backend.config

import com.autoaccounting.api.ApiJsonContracts
import com.autoaccounting.backend.jdbcConnection
import com.autoaccounting.backend.runBackendMigrations

class JdbcCloudConfigStore(
    private val jdbcUrl: String,
    username: String = "",
    password: String = ""
) : CloudConfigStore {
    private val storeUsername = username
    private val storePassword = password

    init {
        runBackendMigrations(jdbcUrl, username, password)
    }

    override fun findConfig(accountId: Long): StoredCloudConfig? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT account_id, ai_consent_granted, enhanced_context_granted,
                   feature_flags, updated_at_millis
            FROM cloud_config
            WHERE account_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredCloudConfig(
                        accountId = rs.getLong("account_id"),
                        aiConsentGranted = rs.getBoolean("ai_consent_granted"),
                        enhancedContextGranted = rs.getBoolean("enhanced_context_granted"),
                        featureFlags = ApiJsonContracts.parseFeatureFlags(
                            rs.getString("feature_flags").orEmpty().ifBlank { "{}" }
                        ),
                        updatedAtMillis = rs.getLong("updated_at_millis")
                    )
                } else {
                    null
                }
            }
        }
    }

    override fun upsertConfig(config: StoredCloudConfig) {
        connection().use { connection ->
            val updated = connection.prepareStatement(
                """
                UPDATE cloud_config
                SET ai_consent_granted = ?, enhanced_context_granted = ?,
                    feature_flags = ?, updated_at_millis = ?
                WHERE account_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setBoolean(1, config.aiConsentGranted)
                statement.setBoolean(2, config.enhancedContextGranted)
                statement.setString(3, ApiJsonContracts.encodeFeatureFlags(config.featureFlags))
                statement.setLong(4, config.updatedAtMillis)
                statement.setLong(5, config.accountId)
                statement.executeUpdate()
            }
            if (updated == 0) {
                connection.prepareStatement(
                    """
                    INSERT INTO cloud_config (
                        account_id, ai_consent_granted, enhanced_context_granted,
                        feature_flags, updated_at_millis
                    ) VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, config.accountId)
                    statement.setBoolean(2, config.aiConsentGranted)
                    statement.setBoolean(3, config.enhancedContextGranted)
                    statement.setString(4, ApiJsonContracts.encodeFeatureFlags(config.featureFlags))
                    statement.setLong(5, config.updatedAtMillis)
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun deleteConfig(accountId: Long) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                DELETE FROM cloud_config
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
