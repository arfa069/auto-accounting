package com.bks.backend.account

import com.bks.api.LedgerSyncEntityTypeContract
import com.bks.api.LedgerSyncJsonContracts
import com.bks.api.LedgerSyncPayloadContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
@Suppress("NestedBlockDepth")
internal class JdbcAccountWechatStore(
    context: JdbcAccountStoreContext
) : JdbcAccountStoreComponent(context), AccountWechatStore {
    override fun findWechatIdentityByOpenid(appId: String, openid: String): StoredWechatIdentity? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT account_id, app_id, openid, unionid, nickname, avatar_url, created_at_millis, updated_at_millis
            FROM account_wechat_identities
            WHERE app_id = ? AND openid = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, appId)
            statement.setString(2, openid)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredWechatIdentity() else null
            }
        }
    }

    override fun findWechatIdentityByUnionid(unionid: String): StoredWechatIdentity? {
        if (unionid.isBlank()) return null
        return connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT account_id, app_id, openid, unionid, nickname, avatar_url, created_at_millis, updated_at_millis
                FROM account_wechat_identities
                WHERE unionid = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, unionid)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.toStoredWechatIdentity() else null
                }
            }
        }
    }

    override fun findWechatIdentityByAccountId(accountId: Long): StoredWechatIdentity? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT account_id, app_id, openid, unionid, nickname, avatar_url, created_at_millis, updated_at_millis
            FROM account_wechat_identities
            WHERE account_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredWechatIdentity() else null
            }
        }
    }

    override fun claimWechatIdentity(identity: StoredWechatIdentity): WechatIdentityClaimResult {
        try {
            connection().use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO account_wechat_identities (
                        account_id, app_id, openid, unionid, nickname, avatar_url, created_at_millis, updated_at_millis
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, identity.accountId)
                    statement.setString(2, identity.appId)
                    statement.setString(3, identity.openid)
                    statement.setString(4, identity.unionid)
                    statement.setString(5, identity.nickname)
                    statement.setString(6, identity.avatarUrl)
                    statement.setLong(7, identity.createdAtMillis)
                    statement.setLong(8, identity.updatedAtMillis)
                    statement.executeUpdate()
                }
            }
            return WechatIdentityClaimResult.Claimed
        } catch (error: SQLException) {
            if (error.sqlState != UNIQUE_VIOLATION_SQL_STATE) throw error
            val existingIdentity = findWechatIdentityByAccountId(identity.accountId)
                ?: findWechatIdentityByOpenid(identity.appId, identity.openid)
                ?: identity.unionid?.let(::findWechatIdentityByUnionid)
                ?: throw error
            return WechatIdentityClaimResult.Conflict(existingIdentity)
        }
    }

    override fun upsertWechatIdentity(identity: StoredWechatIdentity) {
        connection().use { connection ->
            val updated = connection.prepareStatement(
                """
                UPDATE account_wechat_identities
                SET app_id = ?, openid = ?, unionid = ?, nickname = ?, avatar_url = ?, updated_at_millis = ?
                WHERE account_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, identity.appId)
                statement.setString(2, identity.openid)
                statement.setString(3, identity.unionid)
                statement.setString(4, identity.nickname)
                statement.setString(5, identity.avatarUrl)
                statement.setLong(6, identity.updatedAtMillis)
                statement.setLong(7, identity.accountId)
                statement.executeUpdate()
            }
            if (updated == 0) {
                connection.prepareStatement(
                    """
                    INSERT INTO account_wechat_identities (
                        account_id, app_id, openid, unionid, nickname, avatar_url, created_at_millis, updated_at_millis
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, identity.accountId)
                    statement.setString(2, identity.appId)
                    statement.setString(3, identity.openid)
                    statement.setString(4, identity.unionid)
                    statement.setString(5, identity.nickname)
                    statement.setString(6, identity.avatarUrl)
                    statement.setLong(7, identity.createdAtMillis)
                    statement.setLong(8, identity.updatedAtMillis)
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun deleteWechatIdentity(accountId: Long) {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM account_wechat_identities WHERE account_id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeUpdate()
            }
        }
    }

    override fun createOneTimeTicket(ticket: StoredOneTimeTicket) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO account_one_time_tickets (
                    ticket_hash, ticket_type, account_id, payload_json, expires_at_millis, used_at_millis
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, ticket.ticketHash)
                statement.setString(2, ticket.ticketType)
                statement.setNullableLong(3, ticket.accountId)
                statement.setString(4, ticket.payloadJson)
                statement.setLong(5, ticket.expiresAtMillis)
                statement.setNullableLong(6, ticket.usedAtMillis)
                statement.executeUpdate()
            }
        }
    }

    override fun findOneTimeTicket(ticketHash: String): StoredOneTimeTicket? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT ticket_hash, ticket_type, account_id, payload_json, expires_at_millis, used_at_millis
            FROM account_one_time_tickets
            WHERE ticket_hash = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ticketHash)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toStoredOneTimeTicket() else null
            }
        }
    }

    override fun markOneTimeTicketUsed(ticketHash: String, usedAtMillis: Long): Boolean = connection().use { connection ->
        markOneTimeTicketUsed(connection, ticketHash, usedAtMillis) > 0
    }

}

