package com.bks.backend.account

/**
 * Core account record keyed by internal account_id.
 * Holds primary identifier type, deletion state and creation timestamp.
 */
data class StoredAccount(
    val accountId: Long,
    val publicId: String = java.util.UUID.randomUUID().toString(),
    val primaryIdentifierType: String? = null,
    val deletionRequestedAtMillis: Long? = null,
    val deletionClaimedAtMillis: Long? = null,
    val createdAtMillis: Long
)

/**
 * Account-level password credential (shared across all identifiers).
 */
data class StoredPasswordCredential(
    val accountId: Long,
    val passwordSalt: String,
    val passwordHash: String,
    val failedLoginCount: Int = 0,
    val lockedUntilMillis: Long = 0,
    val updatedAtMillis: Long
)

/**
 * Account identifier record.
 */
data class StoredAccountIdentifier(
    val id: Long = 0,
    val accountId: Long,
    val identifierType: String,
    val rawValue: String,
    val normalizedValue: String,
    val verified: Boolean = true,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

/**
 * Verification code record for SMS and Email.
 */
data class StoredVerificationCode(
    val identifierType: String,
    val normalizedIdentifier: String,
    val purpose: String,
    val codeHash: String,
    val expiresAtMillis: Long,
    val failedAttempts: Int = 0,
    val invalidated: Boolean = false,
    val deviceId: String = "",
    val ipAddress: String = "",
    val contextKey: String? = null
)

data class StoredSession(
    val tokenHash: String,
    val accountId: Long,
    val deviceId: String = "",
    val issuedAtMillis: Long,
    val expiresAtMillis: Long = issuedAtMillis + ACCOUNT_SESSION_TTL_MILLIS
)

data class StoredRegisteredDevice(
    val accountId: Long,
    val deviceId: String,
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long,
    val ipAddress: String = ""
)

data class StoredWechatIdentity(
    val accountId: Long,
    val appId: String,
    val openid: String,
    val unionid: String? = null,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class StoredAccountProfile(
    val accountId: Long,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val updatedAtMillis: Long
)

sealed interface WechatIdentityClaimResult {
    data object Claimed : WechatIdentityClaimResult
    data class Conflict(val existingIdentity: StoredWechatIdentity) : WechatIdentityClaimResult
}

data class StoredOneTimeTicket(
    val ticketHash: String,
    val ticketType: String,
    val accountId: Long? = null,
    val payloadJson: String,
    val expiresAtMillis: Long,
    val usedAtMillis: Long? = null
)
