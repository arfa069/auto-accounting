@file:Suppress("MatchingDeclarationName", "NestedBlockDepth")

package com.autoaccounting.backend

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class Migration(
    val version: Int,
    val statements: List<String>
)

private const val H2_DATABASE_PRODUCT = "H2"
private const val POSTGRESQL_DATABASE_PRODUCT = "PostgreSQL"
private const val POSTGRESQL_MIGRATION_LOCK_ID = 0x4155544F41434354L
private val migrationProcessLock = ReentrantLock()

fun runMigrations(
    jdbcUrl: String,
    username: String = "",
    password: String = "",
    migrations: List<Migration>
) {
    migrationProcessLock.withLock {
        jdbcConnection(jdbcUrl, username, password).use { connection ->
            val databaseProduct = connection.metaData.databaseProductName
            acquireDatabaseMigrationLock(connection, databaseProduct)
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        applied_at_millis BIGINT NOT NULL
                    )
                    """.trimIndent()
                )
            }
            val applied = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version FROM schema_migrations").use { rs ->
                    buildSet {
                        while (rs.next()) add(rs.getInt("version"))
                    }
                }
            }
            migrations.filter { it.version !in applied }.forEach { migration ->
                val h2Snapshot = if (databaseProduct == H2_DATABASE_PRODUCT) {
                    captureH2Snapshot(connection)
                } else {
                    null
                }
                connection.autoCommit = false
                try {
                    connection.createStatement().use { statement ->
                        migration.statements.forEach(statement::execute)
                    }
                    connection.prepareStatement(
                        "INSERT INTO schema_migrations (version, applied_at_millis) VALUES (?, ?)"
                    ).use { statement ->
                        statement.setInt(1, migration.version)
                        statement.setLong(2, System.currentTimeMillis())
                        statement.executeUpdate()
                    }
                    connection.commit()
                } catch (error: SQLException) {
                    rollbackPreservingError(connection, error)
                    h2Snapshot?.let { restoreH2Snapshot(connection, it, error) }
                    throw error
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }
}

private fun acquireDatabaseMigrationLock(connection: Connection, databaseProduct: String) {
    if (databaseProduct != POSTGRESQL_DATABASE_PRODUCT) return
    connection.prepareStatement("SELECT pg_advisory_lock(?)").use { statement ->
        statement.setLong(1, POSTGRESQL_MIGRATION_LOCK_ID)
        statement.executeQuery().use { result ->
            if (!result.next()) throw SQLException("PostgreSQL migration lock returned no result.")
        }
    }
}

private fun captureH2Snapshot(connection: Connection): List<String> {
    return connection.createStatement().use { statement ->
        statement.executeQuery("SCRIPT").use { result ->
            buildList {
                while (result.next()) add(result.getString(1))
            }
        }
    }
}

private fun rollbackPreservingError(connection: Connection, migrationError: SQLException) {
    try {
        connection.rollback()
    } catch (rollbackError: SQLException) {
        migrationError.addSuppressed(rollbackError.sanitized("Migration rollback"))
    }
}

private fun restoreH2Snapshot(
    connection: Connection,
    snapshot: List<String>,
    migrationError: SQLException
) {
    try {
        connection.createStatement().use { statement ->
            statement.execute("DROP ALL OBJECTS")
            snapshot.filter(String::isNotBlank).forEach(statement::execute)
        }
        connection.commit()
    } catch (restoreError: SQLException) {
        migrationError.addSuppressed(restoreError.sanitized("H2 migration snapshot restoration"))
    }
}

private fun SQLException.sanitized(operation: String): SQLException {
    return SQLException(
        "$operation failed (SQLState=${sqlState ?: "unknown"}, errorCode=$errorCode)"
    )
}

fun jdbcConnection(
    jdbcUrl: String,
    username: String = "",
    password: String = ""
): Connection {
    return if (username.isBlank()) {
        DriverManager.getConnection(jdbcUrl)
    } else {
        DriverManager.getConnection(jdbcUrl, username, password)
    }
}

fun runBackendMigrations(
    jdbcUrl: String,
    username: String = "",
    password: String = ""
) {
    runMigrations(
        jdbcUrl,
        username,
        password,
        allBackendMigrations.filter { it.version <= ACCOUNT_PUBLIC_ID_BACKFILL_VERSION }
    )
    backfillAccountPublicIds(jdbcUrl, username, password)
    runMigrations(
        jdbcUrl,
        username,
        password,
        allBackendMigrations.filter { it.version > ACCOUNT_PUBLIC_ID_BACKFILL_VERSION }
    )
}

private fun backfillAccountPublicIds(
    jdbcUrl: String,
    username: String,
    password: String
) {
    jdbcConnection(jdbcUrl, username, password).use { connection ->
        val accountIds = connection.prepareStatement(
            "SELECT account_id FROM accounts WHERE public_id IS NULL"
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.getLong("account_id"))
                }
            }
        }
        if (accountIds.isEmpty()) return

        connection.autoCommit = false
        try {
            connection.prepareStatement(
                "UPDATE accounts SET public_id = ? WHERE account_id = ? AND public_id IS NULL"
            ).use { statement ->
                accountIds.forEach { accountId ->
                    statement.setString(1, UUID.randomUUID().toString())
                    statement.setLong(2, accountId)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.commit()
        } catch (error: SQLException) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }
}

val allBackendMigrations = listOf(
    Migration(
        version = 1,
        statements = listOf(
            """
            CREATE TABLE account_users (
                phone VARCHAR(32) PRIMARY KEY,
                failed_login_count INTEGER NOT NULL,
                locked_until_millis BIGINT NOT NULL,
                deletion_requested_at_millis BIGINT,
                created_at_millis BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE account_password_credentials (
                phone VARCHAR(32) PRIMARY KEY REFERENCES account_users(phone) ON DELETE CASCADE,
                password_salt TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                updated_at_millis BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE account_sms_codes (
                phone VARCHAR(32) PRIMARY KEY,
                code VARCHAR(16) NOT NULL,
                expires_at_millis BIGINT NOT NULL,
                failed_attempts INTEGER NOT NULL,
                invalidated BOOLEAN NOT NULL,
                device_id TEXT NOT NULL,
                ip_address TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE account_sms_issues (
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                scope_type VARCHAR(16) NOT NULL,
                scope_value TEXT NOT NULL,
                issued_at_millis BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE INDEX account_sms_issues_scope_idx
            ON account_sms_issues(scope_type, scope_value, issued_at_millis)
            """.trimIndent(),
            """
            CREATE TABLE account_sessions (
                token TEXT PRIMARY KEY,
                phone VARCHAR(32) NOT NULL REFERENCES account_users(phone) ON DELETE CASCADE,
                device_id TEXT NOT NULL,
                issued_at_millis BIGINT NOT NULL
            )
            """.trimIndent(),
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
    ),
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
    ),
    Migration(
        version = 3,
        statements = listOf(
            """
            CREATE TABLE IF NOT EXISTS ai_categorization_logs (
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
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS ai_categorization_logs_phone_idx
            ON ai_categorization_logs(account_phone)
            """.trimIndent()
        )
    ),
    Migration(
        version = 4,
        statements = listOf(
            "DELETE FROM account_sms_codes",
            "DELETE FROM account_sessions",
            "ALTER TABLE account_sms_codes RENAME COLUMN code TO code_hash",
            "ALTER TABLE account_sms_codes ALTER COLUMN code_hash TYPE TEXT",
            "ALTER TABLE account_sessions RENAME COLUMN token TO token_hash"
        )
    ),
    Migration(
        version = 5,
        statements = listOf(
            """
            CREATE TABLE accounts (
                account_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                legacy_phone VARCHAR(32),
                deletion_requested_at_millis BIGINT,
                created_at_millis BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            INSERT INTO accounts (legacy_phone, deletion_requested_at_millis, created_at_millis)
            SELECT u.phone, u.deletion_requested_at_millis, u.created_at_millis
            FROM account_users u
            ORDER BY u.created_at_millis, u.phone
            """.trimIndent(),
            """
            CREATE TABLE account_phone_credentials (
                account_id BIGINT PRIMARY KEY REFERENCES accounts(account_id) ON DELETE CASCADE,
                phone VARCHAR(32) NOT NULL UNIQUE,
                password_salt TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                failed_login_count INTEGER NOT NULL DEFAULT 0,
                locked_until_millis BIGINT NOT NULL DEFAULT 0,
                updated_at_millis BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            INSERT INTO account_phone_credentials (
                account_id, phone, password_salt, password_hash,
                failed_login_count, locked_until_millis, updated_at_millis
            )
            SELECT a.account_id, u.phone, c.password_salt, c.password_hash,
                   u.failed_login_count, u.locked_until_millis, c.updated_at_millis
            FROM account_users u
            JOIN accounts a ON a.legacy_phone = u.phone
            JOIN account_password_credentials c ON c.phone = u.phone
            """.trimIndent(),
            """
            CREATE TABLE account_wechat_identities (
                account_id BIGINT PRIMARY KEY REFERENCES accounts(account_id) ON DELETE CASCADE,
                app_id VARCHAR(64) NOT NULL,
                openid VARCHAR(64) NOT NULL,
                unionid VARCHAR(64),
                nickname TEXT,
                avatar_url TEXT,
                created_at_millis BIGINT NOT NULL,
                updated_at_millis BIGINT NOT NULL,
                CONSTRAINT uq_wechat_app_openid UNIQUE (app_id, openid),
                CONSTRAINT uq_wechat_unionid UNIQUE (unionid)
            )
            """.trimIndent(),
            """
            CREATE TABLE account_one_time_tickets (
                ticket_hash VARCHAR(64) PRIMARY KEY,
                ticket_type VARCHAR(32) NOT NULL,
                account_id BIGINT REFERENCES accounts(account_id) ON DELETE CASCADE,
                payload_json TEXT NOT NULL,
                expires_at_millis BIGINT NOT NULL,
                used_at_millis BIGINT
            )
            """.trimIndent(),
            """
            CREATE TABLE account_sessions_v5 (
                token_hash TEXT PRIMARY KEY,
                account_id BIGINT NOT NULL REFERENCES accounts(account_id) ON DELETE CASCADE,
                device_id TEXT NOT NULL,
                issued_at_millis BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            INSERT INTO account_sessions_v5 (token_hash, account_id, device_id, issued_at_millis)
            SELECT s.token_hash, a.account_id, s.device_id, s.issued_at_millis
            FROM account_sessions s
            JOIN accounts a ON a.legacy_phone = s.phone
            """.trimIndent(),
            """
            CREATE TABLE registered_devices_v5 (
                account_id BIGINT NOT NULL REFERENCES accounts(account_id) ON DELETE CASCADE,
                device_id TEXT NOT NULL,
                first_seen_at_millis BIGINT NOT NULL,
                last_seen_at_millis BIGINT NOT NULL,
                ip_address TEXT NOT NULL,
                PRIMARY KEY (account_id, device_id)
            )
            """.trimIndent(),
            """
            INSERT INTO registered_devices_v5 (account_id, device_id, first_seen_at_millis, last_seen_at_millis, ip_address)
            SELECT a.account_id, d.device_id, d.first_seen_at_millis, d.last_seen_at_millis, d.ip_address
            FROM registered_devices d
            JOIN accounts a ON a.legacy_phone = d.phone
            """.trimIndent(),
            """
            CREATE TABLE cloud_config_v5 (
                account_id BIGINT PRIMARY KEY REFERENCES accounts(account_id) ON DELETE CASCADE,
                ai_consent_granted BOOLEAN NOT NULL DEFAULT FALSE,
                enhanced_context_granted BOOLEAN NOT NULL DEFAULT FALSE,
                feature_flags TEXT NOT NULL DEFAULT '{}',
                updated_at_millis BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            INSERT INTO cloud_config_v5 (account_id, ai_consent_granted, enhanced_context_granted, feature_flags, updated_at_millis)
            SELECT a.account_id, c.ai_consent_granted, c.enhanced_context_granted, c.feature_flags, c.updated_at_millis
            FROM cloud_config c
            JOIN accounts a ON a.legacy_phone = c.phone
            """.trimIndent(),
            """
            CREATE TABLE ai_categorization_logs_v5 (
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                account_id BIGINT REFERENCES accounts(account_id) ON DELETE SET NULL,
                merchant_title TEXT NOT NULL,
                source_label TEXT NOT NULL,
                transaction_kind TEXT NOT NULL,
                amount_range_label TEXT NOT NULL,
                suggested_category TEXT NOT NULL,
                confidence_label TEXT NOT NULL,
                explanation TEXT NOT NULL,
                created_at_millis BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            INSERT INTO ai_categorization_logs_v5 (
                account_id, merchant_title, source_label, transaction_kind,
                amount_range_label, suggested_category, confidence_label, explanation, created_at_millis
            )
            SELECT a.account_id, l.merchant_title, l.source_label, l.transaction_kind,
                   l.amount_range_label, l.suggested_category, l.confidence_label, l.explanation, l.created_at_millis
            FROM ai_categorization_logs l
            LEFT JOIN accounts a ON a.legacy_phone = l.account_phone
            ORDER BY l.id
            """.trimIndent(),
            "DROP TABLE IF EXISTS account_sessions",
            "DROP TABLE IF EXISTS registered_devices",
            "DROP TABLE IF EXISTS cloud_config",
            "DROP TABLE IF EXISTS ai_categorization_logs",
            "DROP TABLE IF EXISTS account_password_credentials",
            "DROP TABLE IF EXISTS account_users",
            "ALTER TABLE account_sessions_v5 RENAME TO account_sessions",
            "ALTER TABLE registered_devices_v5 RENAME TO registered_devices",
            "ALTER TABLE cloud_config_v5 RENAME TO cloud_config",
            "ALTER TABLE ai_categorization_logs_v5 RENAME TO ai_categorization_logs",
            "CREATE INDEX ai_categorization_logs_account_id_idx ON ai_categorization_logs(account_id)",
            "ALTER TABLE accounts DROP COLUMN legacy_phone",
            "ALTER TABLE account_sms_codes ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'DEFAULT'",
            "ALTER TABLE account_sms_codes ADD COLUMN context_key TEXT"
        )
    ),
    Migration(
        version = 6,
        statements = listOf(
            "ALTER TABLE accounts ADD COLUMN primary_identifier_type VARCHAR(32)",
            """
            CREATE TABLE account_password_credentials (
                account_id BIGINT PRIMARY KEY REFERENCES accounts(account_id) ON DELETE CASCADE,
                password_salt TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                failed_login_count INTEGER NOT NULL DEFAULT 0,
                locked_until_millis BIGINT NOT NULL DEFAULT 0,
                updated_at_millis BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE account_identifiers (
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                account_id BIGINT NOT NULL REFERENCES accounts(account_id) ON DELETE CASCADE,
                identifier_type VARCHAR(32) NOT NULL,
                raw_value TEXT NOT NULL,
                normalized_value VARCHAR(256) NOT NULL,
                verified BOOLEAN NOT NULL DEFAULT TRUE,
                created_at_millis BIGINT NOT NULL,
                updated_at_millis BIGINT NOT NULL,
                CONSTRAINT uq_account_identifier_type UNIQUE (account_id, identifier_type),
                CONSTRAINT uq_identifier_type_normalized UNIQUE (identifier_type, normalized_value)
            )
            """.trimIndent(),
            """
            INSERT INTO account_password_credentials (
                account_id, password_salt, password_hash,
                failed_login_count, locked_until_millis, updated_at_millis
            )
            SELECT account_id, password_salt, password_hash,
                   failed_login_count, locked_until_millis, updated_at_millis
            FROM account_phone_credentials
            """.trimIndent(),
            """
            INSERT INTO account_identifiers (
                account_id, identifier_type, raw_value, normalized_value,
                verified, created_at_millis, updated_at_millis
            )
            SELECT account_id, 'PHONE', phone, phone,
                   TRUE, updated_at_millis, updated_at_millis
            FROM account_phone_credentials
            """.trimIndent(),
            """
            UPDATE accounts
            SET primary_identifier_type = 'PHONE'
            WHERE account_id IN (SELECT account_id FROM account_phone_credentials)
            """.trimIndent(),
            """
            CREATE TABLE verification_codes (
                identifier_type VARCHAR(32) NOT NULL,
                normalized_identifier VARCHAR(256) NOT NULL,
                purpose VARCHAR(32) NOT NULL,
                code_hash TEXT NOT NULL,
                expires_at_millis BIGINT NOT NULL,
                failed_attempts INTEGER NOT NULL,
                invalidated BOOLEAN NOT NULL,
                device_id TEXT NOT NULL,
                ip_address TEXT NOT NULL,
                context_key TEXT,
                PRIMARY KEY (identifier_type, normalized_identifier, purpose)
            )
            """.trimIndent(),
            """
            INSERT INTO verification_codes (
                identifier_type, normalized_identifier, purpose, code_hash,
                expires_at_millis, failed_attempts, invalidated, device_id, ip_address, context_key
            )
            SELECT 'PHONE', phone, purpose, code_hash,
                   expires_at_millis, failed_attempts, invalidated, device_id, ip_address, context_key
            FROM account_sms_codes
            """.trimIndent(),
            """
            CREATE TABLE verification_code_send_logs (
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                channel_type VARCHAR(32) NOT NULL,
                scope_type VARCHAR(32) NOT NULL,
                scope_value TEXT NOT NULL,
                issued_at_millis BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS account_sms_issues (
                scope_type VARCHAR(32) NOT NULL,
                scope_value TEXT NOT NULL,
                issued_at_millis BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            INSERT INTO verification_code_send_logs (
                channel_type, scope_type, scope_value, issued_at_millis
            )
            SELECT 'SMS', scope_type, scope_value, issued_at_millis
            FROM account_sms_issues
            """.trimIndent(),
            "CREATE INDEX verification_code_send_logs_scope_idx ON verification_code_send_logs(channel_type, scope_type, scope_value, issued_at_millis)",
            "DROP TABLE IF EXISTS account_phone_credentials",
            "DROP TABLE IF EXISTS account_sms_codes",
            "DROP TABLE IF EXISTS account_sms_issues"
        )
    ),
    Migration(
        version = 7,
        statements = listOf(
            """
            CREATE TABLE ledger_sync_profiles (
                account_id BIGINT PRIMARY KEY REFERENCES accounts(account_id) ON DELETE CASCADE,
                profile_key VARCHAR(64) NOT NULL UNIQUE,
                created_at_millis BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE ledger_sync_records (
                account_id BIGINT NOT NULL REFERENCES accounts(account_id) ON DELETE CASCADE,
                entity_type VARCHAR(32) NOT NULL,
                entity_id VARCHAR(128) NOT NULL,
                version BIGINT NOT NULL,
                revision BIGINT NOT NULL,
                deleted BOOLEAN NOT NULL,
                payload TEXT,
                business_key TEXT,
                updated_at_millis BIGINT NOT NULL,
                PRIMARY KEY (account_id, entity_type, entity_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE ledger_sync_changes (
                revision BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                account_id BIGINT NOT NULL REFERENCES accounts(account_id) ON DELETE CASCADE,
                entity_type VARCHAR(32) NOT NULL,
                entity_id VARCHAR(128) NOT NULL,
                version BIGINT NOT NULL,
                deleted BOOLEAN NOT NULL,
                payload TEXT,
                changed_at_millis BIGINT NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX ledger_sync_changes_account_revision_idx ON ledger_sync_changes(account_id, revision)",
            """
            CREATE TABLE ledger_sync_conflicts (
                conflict_id VARCHAR(64) PRIMARY KEY,
                account_id BIGINT NOT NULL REFERENCES accounts(account_id) ON DELETE CASCADE,
                entity_type VARCHAR(32) NOT NULL,
                entity_id VARCHAR(128) NOT NULL,
                canonical_version BIGINT NOT NULL,
                canonical_deleted BOOLEAN NOT NULL,
                canonical_payload TEXT,
                candidate_deleted BOOLEAN NOT NULL,
                candidate_payload TEXT,
                created_at_millis BIGINT NOT NULL,
                resolved BOOLEAN NOT NULL DEFAULT FALSE
            )
            """.trimIndent(),
            "CREATE INDEX ledger_sync_conflicts_account_resolved_idx ON ledger_sync_conflicts(account_id, resolved, created_at_millis)",
            """
            CREATE TABLE ledger_sync_mutations (
                account_id BIGINT NOT NULL REFERENCES accounts(account_id) ON DELETE CASCADE,
                mutation_id VARCHAR(64) NOT NULL,
                accepted BOOLEAN NOT NULL,
                version BIGINT,
                revision BIGINT,
                conflict_id VARCHAR(64),
                canonical_entity_id VARCHAR(128),
                processed_at_millis BIGINT NOT NULL,
                PRIMARY KEY (account_id, mutation_id)
            )
            """.trimIndent()
        )
    ),
    Migration(
        version = 8,
        statements = listOf(
            """
            CREATE TABLE account_profiles (
                account_id BIGINT PRIMARY KEY REFERENCES accounts(account_id) ON DELETE CASCADE,
                nickname TEXT,
                avatar_url TEXT,
                updated_at_millis BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            INSERT INTO account_profiles (account_id, nickname, avatar_url, updated_at_millis)
            SELECT account_id, nickname, avatar_url, updated_at_millis
            FROM account_wechat_identities
            WHERE nickname IS NOT NULL OR avatar_url IS NOT NULL
            """.trimIndent()
        )
    ),
    Migration(
        version = 9,
        statements = listOf(
            "ALTER TABLE accounts ADD COLUMN public_id VARCHAR(36)",
            "CREATE UNIQUE INDEX accounts_public_id_idx ON accounts(public_id)"
        )
    ),
    Migration(
        version = 10,
        statements = listOf(
            "ALTER TABLE accounts ALTER COLUMN public_id SET NOT NULL"
        )
    ),
    Migration(
        version = 11,
        statements = listOf(
            "ALTER TABLE cloud_config ADD COLUMN default_funding_account_sync_id VARCHAR(128)"
        )
    ),
    Migration(
        version = 12,
        statements = listOf(
            "ALTER TABLE account_sessions ADD COLUMN expires_at_millis BIGINT",
            "UPDATE account_sessions SET expires_at_millis = issued_at_millis + 2592000000 WHERE expires_at_millis IS NULL",
            "ALTER TABLE account_sessions ALTER COLUMN expires_at_millis SET NOT NULL"
        )
    ),
    Migration(
        version = 13,
        statements = listOf(
            "ALTER TABLE accounts ADD COLUMN deletion_claimed_at_millis BIGINT"
        )
    ),
    Migration(
        version = 14,
        statements = listOf(
            "CREATE INDEX ledger_sync_records_business_key_idx " +
                "ON ledger_sync_records(account_id, entity_type, business_key, entity_id)"
        )
    )
)

private const val ACCOUNT_PUBLIC_ID_BACKFILL_VERSION = 9
