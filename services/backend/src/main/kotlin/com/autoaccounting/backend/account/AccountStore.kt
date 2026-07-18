package com.autoaccounting.backend.account

data class StoredUser(
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
    val phone: String,
    val deviceId: String = "",
    val issuedAtMillis: Long
)

data class StoredRegisteredDevice(
    val phone: String,
    val deviceId: String,
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long,
    val ipAddress: String = ""
)

interface AccountStore {
    fun findUser(phone: String): StoredUser?
    fun createUser(user: StoredUser): Boolean
    fun updateUser(user: StoredUser)
    fun usersPendingDeletion(): List<StoredUser>
    fun deleteUser(phone: String)

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
    fun deleteSessionsForPhone(phone: String)

    fun upsertRegisteredDevice(device: StoredRegisteredDevice)
    fun registeredDevices(phone: String): List<StoredRegisteredDevice>
}

class InMemoryAccountStore : AccountStore {
    private val users = mutableMapOf<String, StoredUser>()
    private val smsCodes = mutableMapOf<String, StoredSmsCode>()
    private val smsIssues = mutableListOf<SmsIssue>()
    private val sessions = mutableMapOf<String, StoredSession>()
    private val devices = mutableMapOf<Pair<String, String>, StoredRegisteredDevice>()

    override fun findUser(phone: String): StoredUser? = users[phone]

    override fun createUser(user: StoredUser): Boolean {
        if (users.containsKey(user.phone)) return false
        users[user.phone] = user
        return true
    }

    override fun updateUser(user: StoredUser) {
        users[user.phone] = user
    }

    override fun usersPendingDeletion(): List<StoredUser> {
        return users.values.filter { it.deletionRequestedAtMillis != null }
    }

    override fun deleteUser(phone: String) {
        users.remove(phone)
        sessions.values.removeAll { it.phone == phone }
        devices.keys.removeAll { it.first == phone }
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

    override fun deleteSessionsForPhone(phone: String) {
        sessions.values.removeAll { it.phone == phone }
    }

    override fun upsertRegisteredDevice(device: StoredRegisteredDevice) {
        val key = device.phone to device.deviceId
        val existing = devices[key]
        devices[key] = if (existing == null) {
            device
        } else {
            device.copy(firstSeenAtMillis = existing.firstSeenAtMillis)
        }
    }

    override fun registeredDevices(phone: String): List<StoredRegisteredDevice> {
        return devices.values.filter { it.phone == phone }.sortedBy { it.deviceId }
    }

    private data class SmsIssue(
        val scopeType: String,
        val scopeValue: String,
        val issuedAtMillis: Long
    )
}
