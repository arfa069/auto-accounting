package com.autoaccounting.backend.config

import com.autoaccounting.backend.Migration
import com.autoaccounting.backend.jdbcConnection
import com.autoaccounting.backend.runMigrations

class JdbcCloudConfigStore(
    private val jdbcUrl: String,
    private val username: String = "",
    private val password: String = ""
) : CloudConfigStore {
    init {
        runMigrations(jdbcUrl, username, password, cloudConfigMigrations)
    }

    override fun findConfig(phone: String): StoredCloudConfig? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT phone, ai_consent_granted, enhanced_context_granted,
                   feature_flags, updated_at_millis
            FROM cloud_config
            WHERE phone = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, phone)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredCloudConfig(
                        phone = rs.getString("phone"),
                        aiConsentGranted = rs.getBoolean("ai_consent_granted"),
                        enhancedContextGranted = rs.getBoolean("enhanced_context_granted"),
                        featureFlags = rs.getString("feature_flags").orEmpty().ifBlank { "{}" },
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
                WHERE phone = ?
                """.trimIndent()
            ).use { statement ->
                statement.setBoolean(1, config.aiConsentGranted)
                statement.setBoolean(2, config.enhancedContextGranted)
                statement.setString(3, config.featureFlags)
                statement.setLong(4, config.updatedAtMillis)
                statement.setString(5, config.phone)
                statement.executeUpdate()
            }
            if (updated == 0) {
                connection.prepareStatement(
                    """
                    INSERT INTO cloud_config (
                        phone, ai_consent_granted, enhanced_context_granted,
                        feature_flags, updated_at_millis
                    ) VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, config.phone)
                    statement.setBoolean(2, config.aiConsentGranted)
                    statement.setBoolean(3, config.enhancedContextGranted)
                    statement.setString(4, config.featureFlags)
                    statement.setLong(5, config.updatedAtMillis)
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun deleteConfig(phone: String) {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM cloud_config WHERE phone = ?").use { statement ->
                statement.setString(1, phone)
                statement.executeUpdate()
            }
        }
    }

    private fun connection() = jdbcConnection(jdbcUrl, username, password)
}

private val cloudConfigMigrations = listOf(
    Migration(
        version = 2,
        statements = listOf(
            """
            CREATE TABLE IF NOT EXISTS cloud_config (
                phone VARCHAR(32) PRIMARY KEY REFERENCES account_users(phone) ON DELETE CASCADE,
                ai_consent_granted BOOLEAN NOT NULL DEFAULT FALSE,
                enhanced_context_granted BOOLEAN NOT NULL DEFAULT FALSE,
                feature_flags TEXT NOT NULL DEFAULT '{}',
                updated_at_millis BIGINT NOT NULL
            )
            """.trimIndent()
        )
    )
)
