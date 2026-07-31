package com.autoaccounting.backend.account

internal data class VerificationSendLog(
    val channelType: String,
    val scopeType: String,
    val scopeValue: String,
    val issuedAtMillis: Long
)

internal class InMemoryAccountState {
    val lock = Any()
    var nextAccountId = 1L
    val accounts = mutableMapOf<Long, StoredAccount>()
    val passwordCredentials = mutableMapOf<Long, StoredPasswordCredential>()
    val accountIdentifiers = mutableMapOf<Pair<String, String>, StoredAccountIdentifier>()
    val verificationCodes = mutableMapOf<Triple<String, String, String>, StoredVerificationCode>()
    val verificationSendLogs = mutableListOf<VerificationSendLog>()
    val sessions = mutableMapOf<String, StoredSession>()
    val devices = mutableMapOf<Pair<Long, String>, StoredRegisteredDevice>()
    val wechatIdentities = mutableMapOf<Long, StoredWechatIdentity>()
    val profiles = mutableMapOf<Long, StoredAccountProfile>()
    val oneTimeTickets = mutableMapOf<String, StoredOneTimeTicket>()
}

internal abstract class InMemoryAccountStoreComponent(
    protected val state: InMemoryAccountState
) {
    protected var nextAccountId: Long
        get() = state.nextAccountId
        set(value) { state.nextAccountId = value }
    protected val accounts get() = state.accounts
    protected val passwordCredentials get() = state.passwordCredentials
    protected val accountIdentifiers get() = state.accountIdentifiers
    protected val verificationCodes get() = state.verificationCodes
    protected val verificationSendLogs get() = state.verificationSendLogs
    protected val sessions get() = state.sessions
    protected val devices get() = state.devices
    protected val wechatIdentities get() = state.wechatIdentities
    protected val profiles get() = state.profiles
    protected val oneTimeTickets get() = state.oneTimeTickets

    protected fun hashStoredToken(token: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(digest)
    }

    protected fun stateFindWechatIdentityByOpenid(appId: String, openid: String): StoredWechatIdentity? =
        wechatIdentities.values.firstOrNull { it.appId == appId && it.openid == openid }

    protected fun stateFindWechatIdentityByUnionid(unionid: String): StoredWechatIdentity? =
        wechatIdentities.values.firstOrNull { it.unionid == unionid }

    protected fun stateUpsertRegisteredDevice(device: StoredRegisteredDevice) {
        val key = device.accountId to device.deviceId
        val existing = devices[key]
        devices[key] = if (existing == null) {
            device
        } else {
            device.copy(firstSeenAtMillis = minOf(existing.firstSeenAtMillis, device.firstSeenAtMillis))
        }
    }

    protected fun stateCreateSession(session: StoredSession) {
        sessions[session.tokenHash] = session
    }

    protected fun stateDeleteSessionsForAccount(accountId: Long) {
        sessions.entries.removeAll { it.value.accountId == accountId }
    }

    protected fun stateDeleteVerificationCode(code: StoredVerificationCode) {
        verificationCodes.remove(Triple(code.identifierType, code.normalizedIdentifier, code.purpose))
    }
}
