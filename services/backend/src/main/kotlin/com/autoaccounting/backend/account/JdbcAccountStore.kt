package com.autoaccounting.backend.account

import com.autoaccounting.backend.Migration
import com.autoaccounting.backend.jdbcConnection
import com.autoaccounting.backend.runMigrations
import java.sql.ResultSet

class JdbcAccountStore(
    private val jdbcUrl: String,
    private val username: String = "",
    private val password: String = ""
) : AccountStore {
    init {
        runMigrations(jdbcUrl, username, password, accountMigrations)
    }

    override fun findUser(phone: String): StoredUser? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT u.phone, c.password_salt, c.password_hash, u.failed_login_count,
                   u.locked_until_millis, u.deletion_requested_at_millis, u.created_at_millis
            FROM account_users u
            JOIN account_password_credentials c ON c.phone = u.phone
            WHERE u.phone = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, phone)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredUser() else null
            }
        }
    }

    override fun createUser(user: StoredUser): Boolean = connection().use { connection ->
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                """
                INSERT INTO account_users (
                    phone, failed_login_count, locked_until_millis,
                    deletion_requested_at_millis, created_at_millis
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, user.phone)
                statement.setInt(2, user.failedLoginCount)
                statement.setLong(3, user.lockedUntilMillis)
                statement.setNullableLong(4, user.deletionRequestedAtMillis)
                statement.setLong(5, user.createdAtMillis)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO account_password_credentials (
                    phone, password_salt, password_hash, updated_at_millis
                ) VALUES (?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, user.phone)
                statement.setString(2, user.passwordSalt)
                statement.setString(3, user.passwordHash)
                statement.setLong(4, user.createdAtMillis)
                statement.executeUpdate()
            }
            connection.commit()
            true
        } catch (error: java.sql.SQLException) {
            connection.rollback()
            if (error.sqlState == UNIQUE_VIOLATION_SQL_STATE) false else throw error
        } finally {
            connection.autoCommit = true
        }
    }

    override fun updateUser(user: StoredUser) {
        connection().use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    UPDATE account_users
                    SET failed_login_count = ?,
                        locked_until_millis = ?,
                        deletion_requested_at_millis = ?
                    WHERE phone = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, user.failedLoginCount)
                    statement.setLong(2, user.lockedUntilMillis)
                    statement.setNullableLong(3, user.deletionRequestedAtMillis)
                    statement.setString(4, user.phone)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    UPDATE account_password_credentials
                    SET password_salt = ?,
                        password_hash = ?,
                        updated_at_millis = ?
                    WHERE phone = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, user.passwordSalt)
                    statement.setString(2, user.passwordHash)
                    statement.setLong(3, System.currentTimeMillis())
                    statement.setString(4, user.phone)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (error: java.sql.SQLException) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override fun usersPendingDeletion(): List<StoredUser> = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT u.phone, c.password_salt, c.password_hash, u.failed_login_count,
                   u.locked_until_millis, u.deletion_requested_at_millis, u.created_at_millis
            FROM account_users u
            JOIN account_password_credentials c ON c.phone = u.phone
            WHERE u.deletion_requested_at_millis IS NOT NULL
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toStoredUser())
                }
            }
        }
    }

    override fun deleteUser(phone: String) {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM account_users WHERE phone = ?").use { statement ->
                statement.setString(1, phone)
                statement.executeUpdate()
            }
        }
    }

    override fun upsertSmsCode(record: StoredSmsCode) {
        connection().use { connection ->
            val updated = connection.prepareStatement(
                """
                UPDATE account_sms_codes
                SET code_hash = ?, expires_at_millis = ?, failed_attempts = ?,
                    invalidated = ?, device_id = ?, ip_address = ?
                WHERE phone = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, record.codeHash)
                statement.setLong(2, record.expiresAtMillis)
                statement.setInt(3, record.failedAttempts)
                statement.setBoolean(4, record.invalidated)
                statement.setString(5, record.deviceId)
                statement.setString(6, record.ipAddress)
                statement.setString(7, record.phone)
                statement.executeUpdate()
            }
            if (updated == 0) {
                connection.prepareStatement(
                    """
                    INSERT INTO account_sms_codes (
                        phone, code_hash, expires_at_millis, failed_attempts,
                        invalidated, device_id, ip_address
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, record.phone)
                    statement.setString(2, record.codeHash)
                    statement.setLong(3, record.expiresAtMillis)
                    statement.setInt(4, record.failedAttempts)
                    statement.setBoolean(5, record.invalidated)
                    statement.setString(6, record.deviceId)
                    statement.setString(7, record.ipAddress)
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun findSmsCode(phone: String): StoredSmsCode? = connection().use { connection ->
        connection.prepareStatement(
            """
                SELECT phone, code_hash, expires_at_millis, failed_attempts,
                   invalidated, device_id, ip_address
            FROM account_sms_codes
            WHERE phone = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, phone)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredSmsCode(
                        phone = rs.getString("phone"),
                        codeHash = rs.getString("code_hash"),
                        expiresAtMillis = rs.getLong("expires_at_millis"),
                        failedAttempts = rs.getInt("failed_attempts"),
                        invalidated = rs.getBoolean("invalidated"),
                        deviceId = rs.getString("device_id").orEmpty(),
                        ipAddress = rs.getString("ip_address").orEmpty()
                    )
                } else {
                    null
                }
            }
        }
    }

    override fun updateSmsCode(record: StoredSmsCode) {
        upsertSmsCode(record)
    }

    override fun deleteSmsCode(phone: String) {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM account_sms_codes WHERE phone = ?").use { statement ->
                statement.setString(1, phone)
                statement.executeUpdate()
            }
        }
    }

    override fun recordSmsIssue(scopeType: String, scopeValue: String, issuedAtMillis: Long) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO account_sms_issues (scope_type, scope_value, issued_at_millis)
                VALUES (?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, scopeType)
                statement.setString(2, scopeValue)
                statement.setLong(3, issuedAtMillis)
                statement.executeUpdate()
            }
        }
    }

    override fun countSmsIssues(scopeType: String, scopeValue: String, sinceMillis: Long): Int {
        return connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*) AS issue_count
                FROM account_sms_issues
                WHERE scope_type = ? AND scope_value = ? AND issued_at_millis >= ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, scopeType)
                statement.setString(2, scopeValue)
                statement.setLong(3, sinceMillis)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt("issue_count")
                }
            }
        }
    }

    override fun latestSmsIssueMillis(scopeType: String, scopeValue: String): Long? {
        return connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT MAX(issued_at_millis) AS latest_issue
                FROM account_sms_issues
                WHERE scope_type = ? AND scope_value = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, scopeType)
                statement.setString(2, scopeValue)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getNullableLong("latest_issue")
                }
            }
        }
    }

    override fun createSession(session: StoredSession) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO account_sessions (token_hash, phone, device_id, issued_at_millis)
                VALUES (?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, session.tokenHash)
                statement.setString(2, session.phone)
                statement.setString(3, session.deviceId)
                statement.setLong(4, session.issuedAtMillis)
                statement.executeUpdate()
            }
        }
    }

    override fun findSession(tokenHash: String): StoredSession? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT token_hash, phone, device_id, issued_at_millis
            FROM account_sessions
            WHERE token_hash = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, tokenHash)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredSession(
                        tokenHash = rs.getString("token_hash"),
                        phone = rs.getString("phone"),
                        deviceId = rs.getString("device_id").orEmpty(),
                        issuedAtMillis = rs.getLong("issued_at_millis")
                    )
                } else {
                    null
                }
            }
        }
    }

    override fun deleteSession(tokenHash: String) {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM account_sessions WHERE token_hash = ?").use { statement ->
                statement.setString(1, tokenHash)
                statement.executeUpdate()
            }
        }
    }

    override fun deleteSessionsForPhone(phone: String) {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM account_sessions WHERE phone = ?").use { statement ->
                statement.setString(1, phone)
                statement.executeUpdate()
            }
        }
    }

    override fun upsertRegisteredDevice(device: StoredRegisteredDevice) {
        connection().use { connection ->
            val updated = connection.prepareStatement(
                """
                UPDATE registered_devices
                SET last_seen_at_millis = ?, ip_address = ?
                WHERE phone = ? AND device_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, device.lastSeenAtMillis)
                statement.setString(2, device.ipAddress)
                statement.setString(3, device.phone)
                statement.setString(4, device.deviceId)
                statement.executeUpdate()
            }
            if (updated == 0) {
                connection.prepareStatement(
                    """
                    INSERT INTO registered_devices (
                        phone, device_id, first_seen_at_millis,
                        last_seen_at_millis, ip_address
                    ) VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, device.phone)
                    statement.setString(2, device.deviceId)
                    statement.setLong(3, device.firstSeenAtMillis)
                    statement.setLong(4, device.lastSeenAtMillis)
                    statement.setString(5, device.ipAddress)
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun registeredDevices(phone: String): List<StoredRegisteredDevice> = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT phone, device_id, first_seen_at_millis, last_seen_at_millis, ip_address
            FROM registered_devices
            WHERE phone = ?
            ORDER BY device_id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, phone)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StoredRegisteredDevice(
                                phone = rs.getString("phone"),
                                deviceId = rs.getString("device_id"),
                                firstSeenAtMillis = rs.getLong("first_seen_at_millis"),
                                lastSeenAtMillis = rs.getLong("last_seen_at_millis"),
                                ipAddress = rs.getString("ip_address").orEmpty()
                            )
                        )
                    }
                }
            }
        }
    }

    private fun connection() = jdbcConnection(jdbcUrl, username, password)

    data class Config(
        val jdbcUrl: String,
        val username: String = "",
        val password: String = ""
    )

    companion object {
        fun configFromEnvironment(env: Map<String, String> = System.getenv()): Config? {
            val url = env["AUTO_ACCOUNTING_DATABASE_URL"].orEmpty()
            if (url.isBlank()) return null
            return Config(
                jdbcUrl = url,
                username = env["AUTO_ACCOUNTING_DATABASE_USER"].orEmpty(),
                password = env["AUTO_ACCOUNTING_DATABASE_PASSWORD"].orEmpty()
            )
        }
    }
}

private val accountMigrations = listOf(
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
        version = 4,
        statements = listOf(
            "DELETE FROM account_sms_codes",
            "DELETE FROM account_sessions",
            "ALTER TABLE account_sms_codes RENAME COLUMN code TO code_hash",
            "ALTER TABLE account_sms_codes ALTER COLUMN code_hash TYPE TEXT",
            "ALTER TABLE account_sessions RENAME COLUMN token TO token_hash"
        )
    )
)

private const val UNIQUE_VIOLATION_SQL_STATE = "23505"

private fun ResultSet.toStoredUser(): StoredUser {
    return StoredUser(
        phone = getString("phone"),
        passwordSalt = getString("password_salt"),
        passwordHash = getString("password_hash"),
        failedLoginCount = getInt("failed_login_count"),
        lockedUntilMillis = getLong("locked_until_millis"),
        deletionRequestedAtMillis = getNullableLong("deletion_requested_at_millis"),
        createdAtMillis = getLong("created_at_millis")
    )
}

private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) {
        setNull(index, java.sql.Types.BIGINT)
    } else {
        setLong(index, value)
    }
}

private fun ResultSet.getNullableLong(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}
