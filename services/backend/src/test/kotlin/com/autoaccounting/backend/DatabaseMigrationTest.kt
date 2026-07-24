@file:Suppress("LongMethod", "NestedBlockDepth")

package com.autoaccounting.backend

import com.autoaccounting.backend.ai.JdbcAiCategorizationLogStore
import com.autoaccounting.backend.ai.StoredAiCategorizationLog
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class DatabaseMigrationTest {

    @Test
    fun migratesFromVersion4ToVersion5PreservingAllRecordsAndHashes() {
        val databaseUrl = h2DatabaseUrl()
        setupV4Database(databaseUrl)

        runBackendMigrations(databaseUrl)

        verifyV6Database(databaseUrl)
    }

    @Test
    fun rerunMigrationsIsIdempotent() {
        val databaseUrl = h2DatabaseUrl()
        runBackendMigrations(databaseUrl)
        val firstMigrationTime = getMigrationTime(databaseUrl, 6)

        runBackendMigrations(databaseUrl)
        val secondMigrationTime = getMigrationTime(databaseUrl, 6)

        assertEquals(firstMigrationTime, secondMigrationTime)
    }

    @Test
    fun migratedAiLogDoesNotCollideWithNextGeneratedIdentity() {
        val databaseUrl = h2DatabaseUrl()
        setupV4Database(databaseUrl)
        runBackendMigrations(databaseUrl)
        val accountId = DriverManager.getConnection(databaseUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT account_id FROM account_identifiers WHERE identifier_type = 'PHONE'").use { result ->
                    result.next()
                    result.getLong("account_id")
                }
            }
        }

        val store = JdbcAiCategorizationLogStore(databaseUrl)
        store.insertLog(
            StoredAiCategorizationLog(
                accountId = accountId,
                merchantTitle = "After Migration",
                sourceLabel = "APP",
                transactionKind = "EXPENSE",
                amountRangeLabel = "0-50",
                suggestedCategory = "Food",
                confidenceLabel = "HIGH",
                explanation = "New log",
                createdAtMillis = 500_000
            )
        )

        val logs = store.allLogs()
        assertEquals(listOf(1L, 2L), logs.map { it.id })
        assertEquals(listOf("Test Shop", "After Migration"), logs.map { it.merchantTitle })
    }

    @Test
    fun failedVersion5RestoresVersion4SchemaAndData() {
        val databaseUrl = h2DatabaseUrl()
        setupV4Database(databaseUrl)
        val version5 = allBackendMigrations.single { it.version == 5 }
        val failingVersion5 = version5.copy(
            statements = version5.statements + "THIS IS NOT VALID SQL"
        )

        assertThrows(SQLException::class.java) {
            runMigrations(databaseUrl, migrations = listOf(failingVersion5))
        }

        DriverManager.getConnection(databaseUrl).use { connection ->
            val tables = tableNames(connection)
            assertTrue("account_users" in tables)
            assertTrue("account_password_credentials" in tables)
            assertTrue("accounts" !in tables)
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT token_hash FROM account_sessions").use { result ->
                    assertTrue(result.next())
                    assertEquals("token-hash-xyz", result.getString("token_hash"))
                }
                statement.executeQuery("SELECT COUNT(*) FROM schema_migrations WHERE version = 5").use { result ->
                    result.next()
                    assertEquals(0, result.getInt(1))
                }
            }
        }

        runBackendMigrations(databaseUrl)
        verifyV6Database(databaseUrl)
    }

    @Test
    fun failedVersion6RestoresVersion5SchemaAndAllAccountRelations() {
        val databaseUrl = h2DatabaseUrl()
        setupV4Database(databaseUrl)
        val version5 = allBackendMigrations.single { it.version == 5 }
        runMigrations(databaseUrl, migrations = listOf(version5))
        val version6 = allBackendMigrations.single { it.version == 6 }
        val failingVersion6 = version6.copy(
            statements = version6.statements + "THIS IS NOT VALID SQL"
        )

        assertThrows(SQLException::class.java) {
            runMigrations(databaseUrl, migrations = listOf(failingVersion6))
        }

        DriverManager.getConnection(databaseUrl).use { connection ->
            val tables = tableNames(connection)
            assertTrue("accounts" in tables)
            assertTrue("account_phone_credentials" in tables)
            assertTrue("account_identifiers" !in tables)
            assertTrue("verification_codes" !in tables)
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT phone, password_salt, password_hash FROM account_phone_credentials"
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals("13800138000", result.getString("phone"))
                    assertEquals("salt123", result.getString("password_salt"))
                    assertEquals("hash456", result.getString("password_hash"))
                }
                statement.executeQuery("SELECT token_hash, device_id FROM account_sessions").use { result ->
                    assertTrue(result.next())
                    assertEquals("token-hash-xyz", result.getString("token_hash"))
                    assertEquals("device-a", result.getString("device_id"))
                }
                statement.executeQuery("SELECT COUNT(*) FROM schema_migrations WHERE version = 6").use { result ->
                    result.next()
                    assertEquals(0, result.getInt(1))
                }
            }
        }

        runMigrations(databaseUrl, migrations = listOf(version6))
        verifyV6Database(databaseUrl)
    }

    @Test
    fun concurrentCallsApplyMigrationExactlyOnce() {
        val databaseUrl = h2DatabaseUrl()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val migration = Migration(
            version = 42,
            statements = listOf(
                "CREATE ALIAS IF NOT EXISTS SLEEP FOR 'java.lang.Thread.sleep'",
                "CALL SLEEP(200)",
                "CREATE TABLE concurrent_migration_probe (id INTEGER PRIMARY KEY)"
            )
        )

        try {
            val futures = List(2) {
                executor.submit<Unit> {
                    start.await()
                    runMigrations(databaseUrl, migrations = listOf(migration))
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }

        DriverManager.getConnection(databaseUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM schema_migrations WHERE version = 42").use { result ->
                    result.next()
                    assertEquals(1, result.getInt(1))
                }
            }
        }
    }

    private fun setupV4Database(databaseUrl: String) {
        DriverManager.getConnection(databaseUrl).use { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at_millis BIGINT NOT NULL)"
                )
                stmt.execute("INSERT INTO schema_migrations VALUES (1, 1000), (2, 1000), (3, 1000), (4, 1000)")

                stmt.execute(
                    """
                    CREATE TABLE account_users (
                        phone VARCHAR(32) PRIMARY KEY,
                        failed_login_count INTEGER NOT NULL,
                        locked_until_millis BIGINT NOT NULL,
                        deletion_requested_at_millis BIGINT,
                        created_at_millis BIGINT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE account_password_credentials (
                        phone VARCHAR(32) PRIMARY KEY REFERENCES account_users(phone) ON DELETE CASCADE,
                        password_salt TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        updated_at_millis BIGINT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE account_sms_codes (
                        phone VARCHAR(32) PRIMARY KEY,
                        code_hash TEXT NOT NULL,
                        expires_at_millis BIGINT NOT NULL,
                        failed_attempts INTEGER NOT NULL,
                        invalidated BOOLEAN NOT NULL,
                        device_id TEXT NOT NULL,
                        ip_address TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE account_sessions (
                        token_hash TEXT PRIMARY KEY,
                        phone VARCHAR(32) NOT NULL REFERENCES account_users(phone) ON DELETE CASCADE,
                        device_id TEXT NOT NULL,
                        issued_at_millis BIGINT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE registered_devices (
                        phone VARCHAR(32) NOT NULL REFERENCES account_users(phone) ON DELETE CASCADE,
                        device_id TEXT NOT NULL,
                        first_seen_at_millis BIGINT NOT NULL,
                        last_seen_at_millis BIGINT NOT NULL,
                        ip_address TEXT NOT NULL,
                        PRIMARY KEY (phone, device_id)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE cloud_config (
                        phone VARCHAR(32) PRIMARY KEY REFERENCES account_users(phone) ON DELETE CASCADE,
                        ai_consent_granted BOOLEAN NOT NULL DEFAULT FALSE,
                        enhanced_context_granted BOOLEAN NOT NULL DEFAULT FALSE,
                        feature_flags TEXT NOT NULL DEFAULT '{}',
                        updated_at_millis BIGINT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE ai_categorization_logs (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        account_phone VARCHAR(32),
                        merchant_title TEXT NOT NULL,
                        source_label TEXT NOT NULL,
                        transaction_kind TEXT NOT NULL,
                        amount_range_label TEXT NOT NULL,
                        suggested_category TEXT NOT NULL,
                        confidence_label TEXT NOT NULL,
                        explanation TEXT NOT NULL,
                        created_at_millis BIGINT NOT NULL
                    )
                    """.trimIndent()
                )

                stmt.execute("INSERT INTO account_users VALUES ('13800138000', 1, 100000, 500000, 123456789)")
                stmt.execute("INSERT INTO account_password_credentials VALUES ('13800138000', 'salt123', 'hash456', 123456789)")
                stmt.execute("INSERT INTO account_sessions VALUES ('token-hash-xyz', '13800138000', 'device-a', 200000)")
                stmt.execute("INSERT INTO registered_devices VALUES ('13800138000', 'device-a', 100000, 200000, '127.0.0.1')")
                stmt.execute("INSERT INTO cloud_config VALUES ('13800138000', TRUE, FALSE, '{\"flag1\":true}', 300000)")
                stmt.execute("INSERT INTO ai_categorization_logs VALUES (1, '13800138000', 'Test Shop', 'SMS', 'EXPENSE', '10-50', 'Food', 'HIGH', 'Test', 400000)")
                stmt.execute("INSERT INTO account_sms_codes VALUES ('13800138000', 'code-hash-abc', 500000, 0, FALSE, 'device-a', '127.0.0.1')")
            }
        }
    }

    private fun verifyV6Database(databaseUrl: String) {
        DriverManager.getConnection(databaseUrl).use { connection ->
            val accountId = verifyAccountsAndCredentials(connection)
            verifySessionsAndDevices(connection, accountId)
            verifyConfigAndLogs(connection, accountId)
            verifyTablesExistAndDropped(connection)
        }
    }

    private fun verifyAccountsAndCredentials(connection: java.sql.Connection): Long {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM schema_migrations WHERE version = 6").use { rs ->
                rs.next()
                assertEquals(1, rs.getInt(1))
            }
        }

        var accountId: Long = -1
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT account_id, primary_identifier_type, deletion_requested_at_millis, created_at_millis FROM accounts").use { rs ->
                assertTrue(rs.next())
                accountId = rs.getLong("account_id")
                assertTrue(accountId > 0)
                assertEquals("PHONE", rs.getString("primary_identifier_type"))
                assertEquals(500000L, rs.getLong("deletion_requested_at_millis"))
                assertEquals(123456789L, rs.getLong("created_at_millis"))
            }
        }

        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT account_id, password_salt, password_hash, failed_login_count, locked_until_millis FROM account_password_credentials").use { rs ->
                assertTrue(rs.next())
                assertEquals(accountId, rs.getLong("account_id"))
                assertEquals("salt123", rs.getString("password_salt"))
                assertEquals("hash456", rs.getString("password_hash"))
                assertEquals(1, rs.getInt("failed_login_count"))
                assertEquals(100000L, rs.getLong("locked_until_millis"))
            }
        }

        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT account_id, identifier_type, raw_value, normalized_value, verified FROM account_identifiers").use { rs ->
                assertTrue(rs.next())
                assertEquals(accountId, rs.getLong("account_id"))
                assertEquals("PHONE", rs.getString("identifier_type"))
                assertEquals("13800138000", rs.getString("raw_value"))
                assertEquals("13800138000", rs.getString("normalized_value"))
                assertTrue(rs.getBoolean("verified"))
            }
        }
        return accountId
    }

    private fun verifySessionsAndDevices(connection: java.sql.Connection, accountId: Long) {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT token_hash, account_id, device_id FROM account_sessions").use { rs ->
                assertTrue(rs.next())
                assertEquals("token-hash-xyz", rs.getString("token_hash"))
                assertEquals(accountId, rs.getLong("account_id"))
                assertEquals("device-a", rs.getString("device_id"))
            }
        }

        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT account_id, device_id FROM registered_devices").use { rs ->
                assertTrue(rs.next())
                assertEquals(accountId, rs.getLong("account_id"))
                assertEquals("device-a", rs.getString("device_id"))
            }
        }
    }

    private fun verifyConfigAndLogs(connection: java.sql.Connection, accountId: Long) {
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT account_id, ai_consent_granted, feature_flags FROM cloud_config").use { rs ->
                assertTrue(rs.next())
                assertEquals(accountId, rs.getLong("account_id"))
                assertTrue(rs.getBoolean("ai_consent_granted"))
                assertEquals("{\"flag1\":true}", rs.getString("feature_flags"))
            }
        }

        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT account_id, merchant_title FROM ai_categorization_logs").use { rs ->
                assertTrue(rs.next())
                assertEquals(accountId, rs.getLong("account_id"))
                assertEquals("Test Shop", rs.getString("merchant_title"))
            }
        }

        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT identifier_type, normalized_identifier, purpose, code_hash FROM verification_codes").use { rs ->
                assertTrue(rs.next())
                assertEquals("PHONE", rs.getString("identifier_type"))
                assertEquals("13800138000", rs.getString("normalized_identifier"))
                assertEquals("DEFAULT", rs.getString("purpose"))
                assertEquals("code-hash-abc", rs.getString("code_hash"))
            }
        }
    }

    private fun verifyTablesExistAndDropped(connection: java.sql.Connection) {
        val tables = tableNames(connection)
        assertTrue("accounts" in tables)
        assertTrue("account_password_credentials" in tables)
        assertTrue("account_identifiers" in tables)
        assertTrue("verification_codes" in tables)
        assertTrue("account_wechat_identities" in tables)
        assertTrue("account_one_time_tickets" in tables)
        assertTrue("account_users" !in tables)
        assertTrue("account_phone_credentials" !in tables)
        assertTrue("account_sms_codes" !in tables)
        assertTrue("account_sms_issues" !in tables)
    }

    private fun tableNames(connection: java.sql.Connection): Set<String> {
        return connection.metaData.getTables(null, null, null, arrayOf("TABLE")).use { result ->
            buildSet {
                while (result.next()) add(result.getString("TABLE_NAME").lowercase())
            }
        }
    }

    private fun getMigrationTime(databaseUrl: String, version: Int): Long? {
        val conn = DriverManager.getConnection(databaseUrl)
        return conn.use { connection ->
            val stmt = connection.prepareStatement("SELECT applied_at_millis FROM schema_migrations WHERE version = ?")
            stmt.use { statement ->
                statement.setInt(1, version)
                val rs = statement.executeQuery()
                rs.use { resultSet ->
                    if (resultSet.next()) resultSet.getLong(1) else null
                }
            }
        }
    }

    private fun h2DatabaseUrl(): String {
        return "jdbc:h2:mem:${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    }
}
