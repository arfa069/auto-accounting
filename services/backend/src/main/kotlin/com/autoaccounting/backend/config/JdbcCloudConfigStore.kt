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

    override fun findConfig(phone: String): StoredCloudConfig? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT p.phone, c.ai_consent_granted, c.enhanced_context_granted,
                   c.feature_flags, c.updated_at_millis
            FROM cloud_config c
            JOIN account_phone_credentials p ON p.account_id = c.account_id
            WHERE p.phone = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, phone)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredCloudConfig(
                        phone = rs.getString("phone"),
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
                WHERE account_id = (SELECT account_id FROM account_phone_credentials WHERE phone = ?)
                """.trimIndent()
            ).use { statement ->
                statement.setBoolean(1, config.aiConsentGranted)
                statement.setBoolean(2, config.enhancedContextGranted)
                statement.setString(3, ApiJsonContracts.encodeFeatureFlags(config.featureFlags))
                statement.setLong(4, config.updatedAtMillis)
                statement.setString(5, config.phone)
                statement.executeUpdate()
            }
            if (updated == 0) {
                connection.prepareStatement(
                    """
                    INSERT INTO cloud_config (
                        account_id, ai_consent_granted, enhanced_context_granted,
                        feature_flags, updated_at_millis
                    ) VALUES (
                        (SELECT account_id FROM account_phone_credentials WHERE phone = ?),
                        ?, ?, ?, ?
                    )
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, config.phone)
                    statement.setBoolean(2, config.aiConsentGranted)
                    statement.setBoolean(3, config.enhancedContextGranted)
                    statement.setString(4, ApiJsonContracts.encodeFeatureFlags(config.featureFlags))
                    statement.setLong(5, config.updatedAtMillis)
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun deleteConfig(phone: String) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                DELETE FROM cloud_config
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

