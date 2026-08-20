package com.bks.backend.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
internal class InMemoryAccountLifecycleStore(
    state: InMemoryAccountState
) : InMemoryAccountStoreComponent(state), AccountLifecycleStore {
    override fun findAccount(accountId: Long): StoredAccount? = accounts[accountId]

    override fun updateAccountDeletionRequestedAt(
        accountId: Long,
        requestedAtMillis: Long?
    ): Unit = synchronized(state.lock) {
        val account = accounts[accountId] ?: return
        accounts[accountId] = account.copy(deletionRequestedAtMillis = requestedAtMillis)
    }

    override fun cancelAccountDeletion(accountId: Long): Boolean = synchronized(state.lock) {
        val account = accounts[accountId] ?: return@synchronized false
        if (account.deletionRequestedAtMillis == null || account.deletionClaimedAtMillis != null) {
            return@synchronized false
        }
        accounts[accountId] = account.copy(deletionRequestedAtMillis = null)
        true
    }

    override fun claimAccountDeletion(
        accountId: Long,
        cutoffMillis: Long,
        claimedAtMillis: Long
    ): Boolean = synchronized(state.lock) {
        val account = accounts[accountId] ?: return@synchronized false
        val requestedAtMillis = account.deletionRequestedAtMillis ?: return@synchronized false
        if (account.deletionClaimedAtMillis != null || requestedAtMillis > cutoffMillis) {
            return@synchronized false
        }
        accounts[accountId] = account.copy(deletionClaimedAtMillis = claimedAtMillis)
        true
    }

    override fun accountsPendingDeletion(): List<StoredAccount> {
        return accounts.values.filter { it.deletionRequestedAtMillis != null }
    }

    override fun deleteAccount(accountId: Long) {
        passwordCredentials.remove(accountId)
        accountIdentifiers.entries.removeIf { it.value.accountId == accountId }
        accounts.remove(accountId)
        sessions.values.removeAll { it.accountId == accountId }
        devices.keys.removeAll { it.first == accountId }
        wechatIdentities.remove(accountId)
        profiles.remove(accountId)
    }


}

