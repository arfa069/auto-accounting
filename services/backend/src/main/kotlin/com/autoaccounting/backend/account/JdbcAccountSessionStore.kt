package com.autoaccounting.backend.account

import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncJsonContracts
import com.autoaccounting.api.LedgerSyncPayloadContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
@Suppress("NestedBlockDepth")
internal class JdbcAccountSessionStore(
    context: JdbcAccountStoreContext
) : JdbcAccountStoreComponent(context), AccountSessionStore {
    override fun createSession(session: StoredSession) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO account_sessions (token_hash, account_id, device_id, issued_at_millis)
                VALUES (?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, session.tokenHash)
                statement.setLong(2, session.accountId)
                statement.setString(3, session.deviceId)
                statement.setLong(4, session.issuedAtMillis)
                statement.executeUpdate()
            }
        }
    }

    override fun findSession(tokenHash: String): StoredSession? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT token_hash, account_id, device_id, issued_at_millis
            FROM account_sessions
            WHERE token_hash = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, tokenHash)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredSession(
                        tokenHash = rs.getString("token_hash"),
                        accountId = rs.getLong("account_id"),
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

    override fun deleteSessionsForAccount(accountId: Long) {
        connection().use { connection ->
            connection.prepareStatement(
                "DELETE FROM account_sessions WHERE account_id = ?"
            ).use { statement ->
                statement.setLong(1, accountId)
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
                WHERE account_id = ? AND device_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, device.lastSeenAtMillis)
                statement.setString(2, device.ipAddress)
                statement.setLong(3, device.accountId)
                statement.setString(4, device.deviceId)
                statement.executeUpdate()
            }
            if (updated == 0) {
                connection.prepareStatement(
                    """
                    INSERT INTO registered_devices (
                        account_id, device_id, first_seen_at_millis,
                        last_seen_at_millis, ip_address
                    ) VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, device.accountId)
                    statement.setString(2, device.deviceId)
                    statement.setLong(3, device.firstSeenAtMillis)
                    statement.setLong(4, device.lastSeenAtMillis)
                    statement.setString(5, device.ipAddress)
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun registeredDevices(accountId: Long): List<StoredRegisteredDevice> = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT account_id, device_id, first_seen_at_millis, last_seen_at_millis, ip_address
            FROM registered_devices
            WHERE account_id = ?
            ORDER BY device_id
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StoredRegisteredDevice(
                                accountId = rs.getLong("account_id"),
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

}

