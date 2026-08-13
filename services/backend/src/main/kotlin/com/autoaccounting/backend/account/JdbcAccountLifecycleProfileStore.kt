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
internal class JdbcAccountLifecycleProfileStore(
    context: JdbcAccountStoreContext
) : JdbcAccountStoreComponent(context), AccountLifecycleStore, AccountProfileStore {
    override fun findAccount(accountId: Long): StoredAccount? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT account_id, public_id, primary_identifier_type, deletion_requested_at_millis, deletion_claimed_at_millis, created_at_millis
            FROM accounts
            WHERE account_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredAccount(
                        accountId = rs.getLong("account_id"),
                        publicId = rs.getString("public_id"),
                        primaryIdentifierType = rs.getString("primary_identifier_type"),
                        deletionRequestedAtMillis = rs.getNullableLong("deletion_requested_at_millis"),
                        deletionClaimedAtMillis = rs.getNullableLong("deletion_claimed_at_millis"),
                        createdAtMillis = rs.getLong("created_at_millis")
                    )
                } else {
                    null
                }
            }
        }
    }

    override fun updateAccountDeletionRequestedAt(accountId: Long, requestedAtMillis: Long?) {
        connection().use { connection ->
            connection.prepareStatement(
                "UPDATE accounts SET deletion_requested_at_millis = ? WHERE account_id = ?"
            ).use { statement ->
                if (requestedAtMillis == null) statement.setNull(1, java.sql.Types.BIGINT)
                else statement.setLong(1, requestedAtMillis)
                statement.setLong(2, accountId)
                statement.executeUpdate()
            }
        }
    }

    override fun cancelAccountDeletion(accountId: Long): Boolean = connection().use { connection ->
        connection.prepareStatement(
            """
            UPDATE accounts
            SET deletion_requested_at_millis = NULL
            WHERE account_id = ? AND deletion_requested_at_millis IS NOT NULL
                AND deletion_claimed_at_millis IS NULL
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeUpdate() == 1
        }
    }

    override fun claimAccountDeletion(
        accountId: Long,
        cutoffMillis: Long,
        claimedAtMillis: Long
    ): Boolean = connection().use { connection ->
        connection.prepareStatement(
            """
            UPDATE accounts
            SET deletion_claimed_at_millis = ?
            WHERE account_id = ? AND deletion_requested_at_millis <= ?
                AND deletion_claimed_at_millis IS NULL
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, claimedAtMillis)
            statement.setLong(2, accountId)
            statement.setLong(3, cutoffMillis)
            statement.executeUpdate() == 1
        }
    }

    override fun accountsPendingDeletion(): List<StoredAccount> = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT account_id, public_id, primary_identifier_type, deletion_requested_at_millis, deletion_claimed_at_millis, created_at_millis
            FROM accounts
            WHERE deletion_requested_at_millis IS NOT NULL
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StoredAccount(
                                accountId = rs.getLong("account_id"),
                                publicId = rs.getString("public_id"),
                                primaryIdentifierType = rs.getString("primary_identifier_type"),
                                deletionRequestedAtMillis = rs.getNullableLong("deletion_requested_at_millis"),
                                createdAtMillis = rs.getLong("created_at_millis")
                            )
                        )
                    }
                }
            }
        }
    }

    override fun deleteAccount(accountId: Long) {
        connection().use { connection ->
            connection.prepareStatement(
                "DELETE FROM accounts WHERE account_id = ?"
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.executeUpdate()
            }
        }
    }

    // Unified Identifier & Credential Store Implementations

    override fun findProfileByAccountId(accountId: Long): StoredAccountProfile? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT account_id, nickname, avatar_url, updated_at_millis
            FROM account_profiles
            WHERE account_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredAccountProfile(
                        accountId = rs.getLong("account_id"),
                        nickname = rs.getString("nickname"),
                        avatarUrl = rs.getString("avatar_url"),
                        updatedAtMillis = rs.getLong("updated_at_millis")
                    )
                } else {
                    null
                }
            }
        }
    }

    override fun upsertProfile(profile: StoredAccountProfile) {
        connection().use { connection ->
            val updated = connection.prepareStatement(
                """
                UPDATE account_profiles
                SET nickname = ?, avatar_url = ?, updated_at_millis = ?
                WHERE account_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setNullableString(1, profile.nickname)
                statement.setNullableString(2, profile.avatarUrl)
                statement.setLong(3, profile.updatedAtMillis)
                statement.setLong(4, profile.accountId)
                statement.executeUpdate()
            }
            if (updated == 0) {
                connection.prepareStatement(
                    """
                    INSERT INTO account_profiles (account_id, nickname, avatar_url, updated_at_millis)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, profile.accountId)
                    statement.setNullableString(2, profile.nickname)
                    statement.setNullableString(3, profile.avatarUrl)
                    statement.setLong(4, profile.updatedAtMillis)
                    statement.executeUpdate()
                }
            }
        }
    }

}

