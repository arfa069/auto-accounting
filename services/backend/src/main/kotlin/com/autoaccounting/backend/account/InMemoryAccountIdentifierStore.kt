package com.autoaccounting.backend.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
internal class InMemoryAccountIdentifierStore(
    state: InMemoryAccountState
) : InMemoryAccountStoreComponent(state), AccountIdentifierStore {
    override fun findAccountByIdentifier(identifierType: String, normalizedValue: String): StoredAccount? {
        val id = accountIdentifiers[identifierType to normalizedValue] ?: return null
        return accounts[id.accountId]
    }

    override fun findPasswordCredentialByAccountId(accountId: Long): StoredPasswordCredential? {
        return passwordCredentials[accountId]
    }

    override fun findIdentifiersByAccountId(accountId: Long): List<StoredAccountIdentifier> {
        return accountIdentifiers.values.filter { it.accountId == accountId }
    }

    override fun findIdentifierByValue(identifierType: String, normalizedValue: String): StoredAccountIdentifier? {
        return accountIdentifiers[identifierType to normalizedValue]
    }

    override fun updatePasswordCredential(credential: StoredPasswordCredential) {
        passwordCredentials[credential.accountId] = credential
    }

    override fun resetPasswordAndRotateSession(
        credential: StoredPasswordCredential,
        verificationIdentifierType: String,
        verificationNormalizedIdentifier: String,
        verificationPurpose: String,
        deviceId: String,
        ipAddress: String,
        now: Long,
        tokenGenerator: () -> String
    ): AccountResult<AccountToken> = synchronized(state.lock) {
        if (accounts[credential.accountId] == null) return AccountResult.Failure(AccountError.LOGIN_FAILED)
        updatePasswordCredential(credential)
        sessions.entries.removeAll { it.value.accountId == credential.accountId }
        verificationCodes.remove(
            Triple(verificationIdentifierType, verificationNormalizedIdentifier, verificationPurpose)
        )
        if (deviceId.isNotBlank()) {
            val key = credential.accountId to deviceId
            val existing = devices[key]
            devices[key] = StoredRegisteredDevice(
                accountId = credential.accountId,
                deviceId = deviceId,
                firstSeenAtMillis = existing?.firstSeenAtMillis ?: now,
                lastSeenAtMillis = now,
                ipAddress = ipAddress
            )
        }
        val token = tokenGenerator()
        val tokenHash = hashStoredToken(token)
        sessions[tokenHash] = StoredSession(tokenHash, credential.accountId, deviceId, now)
        return AccountResult.Success(AccountToken(accountId = credential.accountId, token = token))
    }

    override fun createAccountWithIdentifier(
        primaryIdentifierType: String,
        rawValue: String,
        normalizedValue: String,
        passwordSalt: String?,
        passwordHash: String?,
        verified: Boolean,
        now: Long
    ): StoredAccount? = synchronized(state.lock) {
        val key = primaryIdentifierType to normalizedValue
        if (accountIdentifiers.containsKey(key)) return null

        val accountId = nextAccountId++
        val account = StoredAccount(
            accountId = accountId,
            primaryIdentifierType = primaryIdentifierType,
            createdAtMillis = now
        )
        accounts[accountId] = account

        val identifier = StoredAccountIdentifier(
            id = accountId * 10,
            accountId = accountId,
            identifierType = primaryIdentifierType,
            rawValue = rawValue,
            normalizedValue = normalizedValue,
            verified = verified,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        accountIdentifiers[key] = identifier

        if (passwordSalt != null && passwordHash != null) {
            val pwd = StoredPasswordCredential(
                accountId = accountId,
                passwordSalt = passwordSalt,
                passwordHash = passwordHash,
                failedLoginCount = 0,
                lockedUntilMillis = 0,
                updatedAtMillis = now
            )
            passwordCredentials[accountId] = pwd
        }
        return account
    }

    override fun addIdentifierToAccount(
        accountId: Long,
        identifierType: String,
        rawValue: String,
        normalizedValue: String,
        verified: Boolean,
        now: Long
    ): Boolean = synchronized(state.lock) {
        if (!accounts.containsKey(accountId)) return false
        val key = identifierType to normalizedValue
        if (accountIdentifiers.containsKey(key)) return false
        if (accountIdentifiers.values.any { it.accountId == accountId && it.identifierType == identifierType }) return false

        accountIdentifiers[key] = StoredAccountIdentifier(
            id = accountId * 10 + accountIdentifiers.size,
            accountId = accountId,
            identifierType = identifierType,
            rawValue = rawValue,
            normalizedValue = normalizedValue,
            verified = verified,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        return true
    }

    override fun completeIdentifierLink(
        ticketHash: String,
        accountId: Long,
        identifierType: String,
        rawValue: String,
        normalizedValue: String,
        newPasswordSalt: String?,
        newPasswordHash: String?,
        deviceId: String,
        ipAddress: String,
        now: Long,
        tokenGenerator: () -> String,
        replaceExisting: Boolean
    ): AccountResult<AccountToken> = synchronized(state.lock) {
        val ticket = oneTimeTickets[ticketHash]
            ?: return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        if (!ticket.isUsableIdentifierLink(accountId, now)) {
            return AccountResult.Failure(AccountError.TICKET_EXPIRED)
        }
        if (accountIdentifiers.containsKey(identifierType to normalizedValue)) {
            return AccountResult.Failure(AccountError.IDENTIFIER_CONFLICT)
        }
        val existingOfType = accountIdentifiers.values.find {
            it.accountId == accountId && it.identifierType == identifierType
        }
        if (replaceExisting != (existingOfType != null)) {
            return AccountResult.Failure(AccountError.IDENTIFIER_CONFLICT)
        }

        val account = accounts[accountId] ?: return AccountResult.Failure(AccountError.TOKEN_INVALID)
        val existingPassword = passwordCredentials[accountId]
        if (existingPassword == null && (newPasswordSalt == null || newPasswordHash == null)) {
            return AccountResult.Failure(AccountError.INVALID_REQUEST)
        }
        val token = tokenGenerator()
        if (existingPassword == null) {
            passwordCredentials[accountId] = StoredPasswordCredential(
                accountId = accountId,
                passwordSalt = requireNotNull(newPasswordSalt),
                passwordHash = requireNotNull(newPasswordHash),
                updatedAtMillis = now
            )
        }
        if (existingOfType == null) {
            val added = addIdentifierToAccount(accountId, identifierType, rawValue, normalizedValue, true, now)
            check(added) { "Identifier link preconditions changed while holding the store lock" }
        } else {
            accountIdentifiers.remove(existingOfType.identifierType to existingOfType.normalizedValue)
            accountIdentifiers[identifierType to normalizedValue] = existingOfType.copy(
                rawValue = rawValue,
                normalizedValue = normalizedValue,
                updatedAtMillis = now
            )
        }
        if (account.primaryIdentifierType == null) {
            accounts[accountId] = account.copy(primaryIdentifierType = identifierType)
        }
        oneTimeTickets[ticketHash] = ticket.copy(usedAtMillis = now)
        verificationCodes.remove(Triple(identifierType, normalizedValue, "IDENTIFIER_LINK"))
        sessions.entries.removeAll { it.value.accountId == accountId }
        if (deviceId.isNotBlank()) {
            devices[accountId to deviceId] = StoredRegisteredDevice(accountId, deviceId, now, now, ipAddress)
        }
        val tokenHash = hashStoredToken(token)
        sessions[tokenHash] = StoredSession(tokenHash, accountId, deviceId, now)
        return AccountResult.Success(AccountToken(accountId = accountId, token = token))
    }

}

private fun StoredOneTimeTicket.isUsableIdentifierLink(accountId: Long, now: Long): Boolean {
    if (ticketType !in setOf("IDENTIFIER_LINK", "IDENTIFIER_REPLACE")) return false
    if (this.accountId != accountId || usedAtMillis != null) return false
    return expiresAtMillis >= now
}

