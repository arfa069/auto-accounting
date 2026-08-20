package com.bks.backend.account

import java.sql.ResultSet

internal const val UNIQUE_VIOLATION_SQL_STATE = "23505"

internal fun ResultSet.toStoredAccount(): StoredAccount {
    return StoredAccount(
        accountId = getLong("account_id"),
        publicId = getString("public_id"),
        primaryIdentifierType = getString("primary_identifier_type"),
        deletionRequestedAtMillis = getNullableLong("deletion_requested_at_millis"),
        deletionClaimedAtMillis = getNullableLong("deletion_claimed_at_millis"),
        createdAtMillis = getLong("created_at_millis")
    )
}

internal fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) {
        setNull(index, java.sql.Types.BIGINT)
    } else {
        setLong(index, value)
    }
}

internal fun java.sql.PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) {
        setNull(index, java.sql.Types.VARCHAR)
    } else {
        setString(index, value)
    }
}

internal fun ResultSet.getNullableLong(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

internal fun ResultSet.toStoredWechatIdentity(): StoredWechatIdentity {
    return StoredWechatIdentity(
        accountId = getLong("account_id"),
        appId = getString("app_id"),
        openid = getString("openid"),
        unionid = getString("unionid"),
        nickname = getString("nickname"),
        avatarUrl = getString("avatar_url"),
        createdAtMillis = getLong("created_at_millis"),
        updatedAtMillis = getLong("updated_at_millis")
    )
}

internal fun ResultSet.toStoredOneTimeTicket(): StoredOneTimeTicket {
    return StoredOneTimeTicket(
        ticketHash = getString("ticket_hash"),
        ticketType = getString("ticket_type"),
        accountId = getNullableLong("account_id"),
        payloadJson = getString("payload_json"),
        expiresAtMillis = getLong("expires_at_millis"),
        usedAtMillis = getNullableLong("used_at_millis")
    )
}
