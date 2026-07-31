package com.autoaccounting.backend.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
internal class InMemoryAccountVerificationStore(
    state: InMemoryAccountState
) : InMemoryAccountStoreComponent(state), AccountVerificationStore {
    override fun upsertVerificationCode(code: StoredVerificationCode) {
        verificationCodes[Triple(code.identifierType, code.normalizedIdentifier, code.purpose)] = code
    }

    override fun findVerificationCode(identifierType: String, normalizedIdentifier: String, purpose: String): StoredVerificationCode? {
        return verificationCodes[Triple(identifierType, normalizedIdentifier, purpose)]
    }

    override fun deleteVerificationCode(identifierType: String, normalizedIdentifier: String, purpose: String) {
        verificationCodes.remove(Triple(identifierType, normalizedIdentifier, purpose))
    }

    override fun recordVerificationSendLog(channelType: String, scopeType: String, scopeValue: String, issuedAtMillis: Long) {
        verificationSendLogs += VerificationSendLog(channelType, scopeType, scopeValue, issuedAtMillis)
    }

    override fun countVerificationSendLogs(channelType: String, scopeType: String, scopeValue: String, sinceMillis: Long): Int {
        return verificationSendLogs.count {
            it.channelType == channelType && it.scopeType == scopeType && it.scopeValue == scopeValue && it.issuedAtMillis >= sinceMillis
        }
    }

    override fun latestVerificationSendLogMillis(channelType: String, scopeType: String, scopeValue: String): Long? {
        return verificationSendLogs
            .filter { it.channelType == channelType && it.scopeType == scopeType && it.scopeValue == scopeValue }
            .maxOfOrNull { it.issuedAtMillis }
    }

}

