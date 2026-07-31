package com.autoaccounting.backend.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
internal class InMemoryAccountProfileStore(
    state: InMemoryAccountState
) : InMemoryAccountStoreComponent(state), AccountProfileStore {
    override fun findProfileByAccountId(accountId: Long): StoredAccountProfile? = profiles[accountId]

    override fun upsertProfile(profile: StoredAccountProfile) {
        check(accounts.containsKey(profile.accountId))
        profiles[profile.accountId] = profile
    }

}

