@file:Suppress("TooManyFunctions")

package com.autoaccounting.backend.account

/**
 * Core account record keyed by internal account_id.
 * Holds deletion state and creation timestamp.
 */
data class StoredAccount(
    val accountId: Long,
    val deletionRequestedAtMillis: Long? = null,
    val createdAtMillis: Long
)

/**
 * Phone credential linked to an account.
 */
data class StoredPhoneCredential(
    val accountId: Long,
    val phone: String,
    val passwordSalt: String,
    val passwordHash: String,
    val failedLoginCount: Int = 0,
    val lockedUntilMillis: Long = 0
)

/**
 * Convenience composite for service-layer operations that need
 * both account-level and phone-credential-level fields.
 */
data class StoredUser(
    val accountId: Long,
    val phone: String,
    val passwordSalt: String,
    val passwordHash: String,
    val failedLoginCount: Int = 0,
    val lockedUntilMillis: Long = 0,
    val deletionRequestedAtMillis: Long? = null,
    val createdAtMillis: Long
)

data class StoredSmsCode(
    val phone: String,
    val codeHash: String,
    val expiresAtMillis: Long,
    val failedAttempts: Int = 0,
    val invalidated: Boolean = false,
    val deviceId: String = "",
    val ipAddress: String = ""
)

data class StoredSession(
    val tokenHash: String,
    val accountId: Long,
    val deviceId: String = "",
    val issuedAtMillis: Long
)

data class StoredRegisteredDevice(
    val accountId: Long,
    val deviceId: String,
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long,
    val ipAddress: String = ""
)

interface AccountStore {
    fun findUser(phone: String): StoredUser?
    fun findUserByAccountId(accountId: Long): StoredUser?
    fun findAccount(accountId: Long): StoredAccount?
    fun createUser(user: StoredUser): Boolean
    fun updateUser(user: StoredUser)
    fun accountsPendingDeletion(): List<StoredAccount>
    fun deleteAccount(accountId: Long)

    fun upsertSmsCode(record: StoredSmsCode)
    fun findSmsCode(phone: String): StoredSmsCode?
    fun updateSmsCode(record: StoredSmsCode)
    fun deleteSmsCode(phone: String)
    fun recordSmsIssue(scopeType: String, scopeValue: String, issuedAtMillis: Long)
    fun countSmsIssues(scopeType: String, scopeValue: String, sinceMillis: Long): Int
    fun latestSmsIssueMillis(scopeType: String, scopeValue: String): Long?

    fun createSession(session: StoredSession)
    fun findSession(tokenHash: String): StoredSession?
    fun deleteSession(tokenHash: String)
    fun deleteSessionsForAccount(accountId: Long)

    fun upsertRegisteredDevice(device: StoredRegisteredDevice)
    fun registeredDevices(accountId: Long): List<StoredRegisteredDevice>
}

class InMemoryAccountStore : AccountStore {
    private var nextAccountId = 1L
    private val accounts = mutableMapOf<Long, StoredAccount>()
    private val phoneCredentials = mutableMapOf<Long, StoredPhoneCredential>()
    private val phoneIndex = mutableMapOf<String, Long>() // phone -> accountId
    private val smsCodes = mutableMapOf<String, StoredSmsCode>()
    private val smsIssues = mutableListOf<SmsIssue>()
    private val sessions = mutableMapOf<String, StoredSession>()
    private val devices = mutableMapOf<Pair<Long, String>, StoredRegisteredDevice>()

    override fun findUser(phone: String): StoredUser? {
        val accountId = phoneIndex[phone] ?: return null
        return findUserByAccountId(accountId)
    }

    override fun findUserByAccountId(accountId: Long): StoredUser? {
        val account = accounts[accountId] ?: return null
        val credential = phoneCredentials[accountId]
        return StoredUser(
            accountId = account.accountId,
            phone = credential?.phone.orEmpty(),
            passwordSalt = credential?.passwordSalt.orEmpty(),
            passwordHash = credential?.passwordHash.orEmpty(),
            failedLoginCount = credential?.failedLoginCount ?: 0,
            lockedUntilMillis = credential?.lockedUntilMillis ?: 0,
            deletionRequestedAtMillis = account.deletionRequestedAtMillis,
            createdAtMillis = account.createdAtMillis
        )
    }

    override fun findAccount(accountId: Long): StoredAccount? = accounts[accountId]

    override fun createUser(user: StoredUser): Boolean {
        if (phoneIndex.containsKey(user.phone)) return false
        val accountId = nextAccountId++
        accounts[accountId] = StoredAccount(
            accountId = accountId,
            deletionRequestedAtMillis = user.deletionRequestedAtMillis,
            createdAtMillis = user.createdAtMillis
        )
        phoneCredentials[accountId] = StoredPhoneCredential(
            accountId = accountId,
            phone = user.phone,
            passwordSalt = user.passwordSalt,
            passwordHash = user.passwordHash,
            failedLoginCount = user.failedLoginCount,
            lockedUntilMillis = user.lockedUntilMillis
        )
        phoneIndex[user.phone] = accountId
        return true
    }

    override fun updateUser(user: StoredUser) {
        val accountId = user.accountId.takeIf { it > 0 } ?: phoneIndex[user.phone] ?: return
        accounts[accountId] = StoredAccount(
            accountId = accountId,
            deletionRequestedAtMillis = user.deletionRequestedAtMillis,
            createdAtMillis = user.createdAtMillis
        )
        if (user.phone.isNotBlank()) {
            phoneCredentials[accountId] = StoredPhoneCredential(
                accountId = accountId,
                phone = user.phone,
                passwordSalt = user.passwordSalt,
                passwordHash = user.passwordHash,
                failedLoginCount = user.failedLoginCount,
                lockedUntilMillis = user.lockedUntilMillis
            )
            phoneIndex[user.phone] = accountId
        }
    }

    override fun accountsPendingDeletion(): List<StoredAccount> {
        return accounts.values.filter { it.deletionRequestedAtMillis != null }
    }

    override fun deleteAccount(accountId: Long) {
        val credential = phoneCredentials.remove(accountId)
        if (credential != null) phoneIndex.remove(credential.phone)
        accounts.remove(accountId)
        sessions.values.removeAll { it.accountId == accountId }
        devices.keys.removeAll { it.first == accountId }
    }

    override fun upsertSmsCode(record: StoredSmsCode) {
        smsCodes[record.phone] = record
    }

    override fun findSmsCode(phone: String): StoredSmsCode? = smsCodes[phone]

    override fun updateSmsCode(record: StoredSmsCode) {
        smsCodes[record.phone] = record
    }

    override fun deleteSmsCode(phone: String) {
        smsCodes.remove(phone)
    }

    override fun recordSmsIssue(scopeType: String, scopeValue: String, issuedAtMillis: Long) {
        smsIssues += SmsIssue(scopeType, scopeValue, issuedAtMillis)
    }

    override fun countSmsIssues(scopeType: String, scopeValue: String, sinceMillis: Long): Int {
        return smsIssues.count {
            it.scopeType == scopeType &&
                it.scopeValue == scopeValue &&
                it.issuedAtMillis >= sinceMillis
        }
    }

    override fun latestSmsIssueMillis(scopeType: String, scopeValue: String): Long? {
        return smsIssues
            .filter { it.scopeType == scopeType && it.scopeValue == scopeValue }
            .maxOfOrNull { it.issuedAtMillis }
    }

    override fun createSession(session: StoredSession) {
        sessions[session.tokenHash] = session
    }

    override fun findSession(tokenHash: String): StoredSession? = sessions[tokenHash]

    override fun deleteSession(tokenHash: String) {
        sessions.remove(tokenHash)
    }

    override fun deleteSessionsForAccount(accountId: Long) {
        sessions.values.removeAll { it.accountId == accountId }
    }

    override fun upsertRegisteredDevice(device: StoredRegisteredDevice) {
        val key = device.accountId to device.deviceId
        val existing = devices[key]
        devices[key] = if (existing == null) {
            device
        } else {
            device.copy(firstSeenAtMillis = existing.firstSeenAtMillis)
        }
    }

    override fun registeredDevices(accountId: Long): List<StoredRegisteredDevice> {
        return devices.values.filter { it.accountId == accountId }.sortedBy { it.deviceId }
    }

    private data class SmsIssue(
        val scopeType: String,
        val scopeValue: String,
        val issuedAtMillis: Long
    )
}
