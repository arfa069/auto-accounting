package com.autoaccounting.backend

import java.sql.Connection
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

private const val POSTGRES_V6_TEST_URL_ENV = "AUTO_ACCOUNTING_POSTGRES_TEST_URL"
private const val POSTGRES_V6_TEST_USER_ENV = "AUTO_ACCOUNTING_POSTGRES_TEST_USER"
private const val POSTGRES_V6_TEST_PASSWORD_ENV = "AUTO_ACCOUNTING_POSTGRES_TEST_PASSWORD"
private val v6TestSchemaPattern = Regex("codex_v6_data_test_[0-9a-f]{32}")

class PostgresV6DataMigrationIntegrationTest {
    @Test
    fun populatedVersion6SchemaMigratesToLatestWithoutDataLoss() {
        val config = postgresTestConfig()
        val schemaName = "codex_v6_data_test_${UUID.randomUUID().toString().replace("-", "")}"
        val schemaUrl = schemaUrl(config.jdbcUrl, schemaName)
        var schemaCreated = false

        try {
            createSchema(config, schemaName)
            schemaCreated = true
            runMigrations(
                jdbcUrl = schemaUrl,
                username = config.username,
                password = config.password,
                migrations = allBackendMigrations.filter { it.version <= 6 }
            )
            val accountId = seedVersion6Account(schemaUrl, config)

            runBackendMigrations(schemaUrl, config.username, config.password)
            val firstMigrationTime = verifyVersion7SyncSchema(schemaUrl, config, accountId)

            runBackendMigrations(schemaUrl, config.username, config.password)
            assertEquals(firstMigrationTime, verifyVersion7SyncSchema(schemaUrl, config, accountId))
        } finally {
            if (schemaCreated) dropSchema(config, schemaName)
        }
    }

    @Test
    fun populatedVersion5SchemaMigratesToVersion6WithoutDataLoss() {
        val config = postgresTestConfig()
        val schemaName = "codex_v6_data_test_${UUID.randomUUID().toString().replace("-", "")}"
        val schemaUrl = schemaUrl(config.jdbcUrl, schemaName)
        var schemaCreated = false

        try {
            createSchema(config, schemaName)
            schemaCreated = true
            runMigrations(
                jdbcUrl = schemaUrl,
                username = config.username,
                password = config.password,
                migrations = allBackendMigrations.filter { it.version <= 5 }
            )
            seedVersion5Data(schemaUrl, config)

            runMigrations(
                jdbcUrl = schemaUrl,
                username = config.username,
                password = config.password,
                migrations = allBackendMigrations.filter { it.version <= 6 }
            )
            val firstMigrationTime = verifyVersion6Data(schemaUrl, config)

            runMigrations(
                jdbcUrl = schemaUrl,
                username = config.username,
                password = config.password,
                migrations = allBackendMigrations.filter { it.version <= 6 }
            )
            assertEquals(firstMigrationTime, verifyVersion6Data(schemaUrl, config))
        } finally {
            if (schemaCreated) dropSchema(config, schemaName)
        }
    }

    private fun seedVersion6Account(schemaUrl: String, config: V6PostgresTestConfig): Long =
        jdbcConnection(schemaUrl, config.username, config.password).use { connection ->
            val accountId = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    INSERT INTO accounts (primary_identifier_type, created_at_millis)
                    VALUES ('PHONE', 123456789)
                    RETURNING account_id
                    """.trimIndent()
                ).use { result ->
                    assertTrue(result.next())
                    result.getLong("account_id")
                }
            }
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO account_identifiers (
                        account_id, identifier_type, raw_value, normalized_value,
                        verified, created_at_millis, updated_at_millis
                    ) VALUES (
                        $accountId, 'PHONE', '13800138000', '13800138000',
                        TRUE, 123456789, 123456789
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    INSERT INTO account_password_credentials (
                        account_id, password_salt, password_hash,
                        failed_login_count, locked_until_millis, updated_at_millis
                    ) VALUES ($accountId, 'salt-v6', 'hash-v6', 0, 0, 123456789)
                    """.trimIndent()
                )
            }
            accountId
        }

    private fun verifyVersion7SyncSchema(
        schemaUrl: String,
        config: V6PostgresTestConfig,
        accountId: Long
    ): Long = jdbcConnection(schemaUrl, config.username, config.password).use { connection ->
        assertEquals(
            "1,2,3,4,5,6,7,8",
            queryString(
                connection,
                "SELECT string_agg(version::text, ',' ORDER BY version) FROM schema_migrations"
            )
        )
        assertEquals(
            "13800138000|salt-v6|hash-v6",
            queryString(
                connection,
                """
                SELECT identifiers.normalized_value || '|' || credentials.password_salt || '|' ||
                       credentials.password_hash
                FROM account_identifiers identifiers
                JOIN account_password_credentials credentials USING (account_id)
                WHERE identifiers.account_id = $accountId
                """.trimIndent()
            )
        )
        listOf(
            "ledger_sync_profiles",
            "ledger_sync_records",
            "ledger_sync_changes",
            "ledger_sync_conflicts",
            "ledger_sync_mutations",
            "account_profiles"
        ).forEach { table -> assertTrue(tableExists(connection, table)) }
        assertEquals(
            1L,
            queryLong(
                connection,
                """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'ledger_sync_records'
                  AND column_name = 'business_key'
                """.trimIndent()
            )
        )
        assertEquals(
            1L,
            queryLong(
                connection,
                """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'ledger_sync_mutations'
                  AND column_name = 'canonical_entity_id'
                """.trimIndent()
            )
        )
        queryLong(connection, "SELECT applied_at_millis FROM schema_migrations WHERE version = 7")
    }

    private fun seedVersion5Data(schemaUrl: String, config: V6PostgresTestConfig) {
        jdbcConnection(schemaUrl, config.username, config.password).use { connection ->
            val accountId = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    INSERT INTO accounts (deletion_requested_at_millis, created_at_millis)
                    VALUES (500000, 123456789)
                    RETURNING account_id
                    """.trimIndent()
                ).use { result ->
                    assertTrue(result.next())
                    result.getLong("account_id")
                }
            }
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO account_phone_credentials (
                        account_id, phone, password_salt, password_hash,
                        failed_login_count, locked_until_millis, updated_at_millis
                    ) VALUES ($accountId, '13800138000', 'salt-v5', 'hash-v5', 2, 600000, 700000)
                    """.trimIndent()
                )
                statement.execute(
                    """
                    INSERT INTO account_wechat_identities (
                        account_id, app_id, openid, unionid, nickname, avatar_url,
                        created_at_millis, updated_at_millis
                    ) VALUES (
                        $accountId, 'test-app', 'test-openid', 'test-unionid', 'Test User',
                        'https://example.invalid/avatar.png', 800000, 900000
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    INSERT INTO account_one_time_tickets (
                        ticket_hash, ticket_type, account_id, payload_json, expires_at_millis, used_at_millis
                    ) VALUES ('ticket-v5', 'WECHAT_LINK', $accountId, '{"source":"v5"}', 1000000, NULL)
                    """.trimIndent()
                )
                statement.execute(
                    "INSERT INTO account_sessions VALUES ('token-v5', $accountId, 'device-v5', 1100000)"
                )
                statement.execute(
                    """
                    INSERT INTO registered_devices VALUES (
                        $accountId, 'device-v5', 1200000, 1300000, '127.0.0.1'
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    INSERT INTO cloud_config VALUES (
                        $accountId, TRUE, TRUE, '{"v5":true}', 1400000
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    INSERT INTO ai_categorization_logs (
                        account_id, merchant_title, source_label, transaction_kind,
                        amount_range_label, suggested_category, confidence_label,
                        explanation, created_at_millis
                    ) VALUES (
                        $accountId, 'V5 Merchant', 'APP', 'EXPENSE', '10-50',
                        'Food', 'HIGH', 'Preserve me', 1500000
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    INSERT INTO account_sms_codes (
                        phone, code_hash, expires_at_millis, failed_attempts, invalidated,
                        device_id, ip_address, purpose, context_key
                    ) VALUES (
                        '13800138000', 'code-v5', 1600000, 1, FALSE,
                        'device-v5', '127.0.0.1', 'RECOVERY', 'context-v5'
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    INSERT INTO account_sms_issues (scope_type, scope_value, issued_at_millis)
                    VALUES ('PHONE', '13800138000', 1700000)
                    """.trimIndent()
                )
            }
        }
    }

    private fun verifyVersion6Data(schemaUrl: String, config: V6PostgresTestConfig): Long {
        return jdbcConnection(schemaUrl, config.username, config.password).use { connection ->
            assertEquals("1,2,3,4,5,6", queryString(connection, "SELECT string_agg(version::text, ',' ORDER BY version) FROM schema_migrations"))
            assertEquals(1L, queryLong(connection, "SELECT COUNT(*) FROM schema_migrations WHERE version = 6"))

            val accountId = queryLong(connection, "SELECT account_id FROM accounts")
            assertEquals("PHONE", queryString(connection, "SELECT primary_identifier_type FROM accounts"))
            assertEquals(500000L, queryLong(connection, "SELECT deletion_requested_at_millis FROM accounts"))
            assertEquals("salt-v5|hash-v5|2|600000", queryString(connection, "SELECT password_salt || '|' || password_hash || '|' || failed_login_count || '|' || locked_until_millis FROM account_password_credentials"))
            assertEquals("$accountId|PHONE|13800138000|13800138000|true", queryString(connection, "SELECT account_id || '|' || identifier_type || '|' || raw_value || '|' || normalized_value || '|' || verified FROM account_identifiers"))
            assertEquals("test-app|test-openid|test-unionid|Test User", queryString(connection, "SELECT app_id || '|' || openid || '|' || unionid || '|' || nickname FROM account_wechat_identities"))
            assertEquals("ticket-v5|WECHAT_LINK|{\"source\":\"v5\"}", queryString(connection, "SELECT ticket_hash || '|' || ticket_type || '|' || payload_json FROM account_one_time_tickets"))
            assertEquals("token-v5|device-v5", queryString(connection, "SELECT token_hash || '|' || device_id FROM account_sessions"))
            assertEquals("device-v5|127.0.0.1", queryString(connection, "SELECT device_id || '|' || ip_address FROM registered_devices"))
            assertEquals("true|true|{\"v5\":true}", queryString(connection, "SELECT ai_consent_granted || '|' || enhanced_context_granted || '|' || feature_flags FROM cloud_config"))
            assertEquals("V5 Merchant|Preserve me", queryString(connection, "SELECT merchant_title || '|' || explanation FROM ai_categorization_logs"))
            assertEquals("PHONE|13800138000|RECOVERY|code-v5|context-v5", queryString(connection, "SELECT identifier_type || '|' || normalized_identifier || '|' || purpose || '|' || code_hash || '|' || context_key FROM verification_codes"))
            assertEquals("SMS|PHONE|13800138000|1700000", queryString(connection, "SELECT channel_type || '|' || scope_type || '|' || scope_value || '|' || issued_at_millis FROM verification_code_send_logs"))

            assertFalse(tableExists(connection, "account_phone_credentials"))
            assertFalse(tableExists(connection, "account_sms_codes"))
            assertFalse(tableExists(connection, "account_sms_issues"))
            queryLong(connection, "SELECT applied_at_millis FROM schema_migrations WHERE version = 6")
        }
    }

    private fun postgresTestConfig(): V6PostgresTestConfig {
        val environment = BackendEnvironment.load()
        val jdbcUrl = environment[POSTGRES_V6_TEST_URL_ENV]
        assumeTrue("$POSTGRES_V6_TEST_URL_ENV is not configured.", !jdbcUrl.isNullOrBlank())
        return V6PostgresTestConfig(
            jdbcUrl = requireNotNull(jdbcUrl),
            username = environment[POSTGRES_V6_TEST_USER_ENV].orEmpty(),
            password = environment[POSTGRES_V6_TEST_PASSWORD_ENV].orEmpty()
        )
    }

    private fun createSchema(config: V6PostgresTestConfig, schemaName: String) {
        require(v6TestSchemaPattern.matches(schemaName))
        jdbcConnection(config.jdbcUrl, config.username, config.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schemaName") }
        }
    }

    private fun dropSchema(config: V6PostgresTestConfig, schemaName: String) {
        require(v6TestSchemaPattern.matches(schemaName))
        jdbcConnection(config.jdbcUrl, config.username, config.password).use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA $schemaName CASCADE") }
        }
    }

    private fun schemaUrl(jdbcUrl: String, schemaName: String): String {
        require(jdbcUrl.startsWith("jdbc:postgresql:"))
        require(!Regex("[?&]currentSchema=", RegexOption.IGNORE_CASE).containsMatchIn(jdbcUrl))
        val separator = if ('?' in jdbcUrl) '&' else '?'
        return "$jdbcUrl${separator}currentSchema=$schemaName"
    }

    private fun queryLong(connection: Connection, sql: String): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                assertTrue(result.next())
                result.getLong(1)
            }
        }

    private fun queryString(connection: Connection, sql: String): String =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                assertTrue(result.next())
                result.getString(1)
            }
        }

    private fun tableExists(connection: Connection, tableName: String): Boolean =
        connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
            statement.setString(1, tableName)
            statement.executeQuery().use { result ->
                assertTrue(result.next())
                result.getBoolean(1)
            }
        }
}

private data class V6PostgresTestConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String
)
