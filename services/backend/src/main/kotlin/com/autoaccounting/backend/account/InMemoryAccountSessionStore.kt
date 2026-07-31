package com.autoaccounting.backend.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
internal class InMemoryAccountSessionStore(
    state: InMemoryAccountState
) : InMemoryAccountStoreComponent(state), AccountSessionStore {
    override fun createSession(session: StoredSession) {
        sessions[session.tokenHash] = session
    }

    override fun findSession(tokenHash: String): StoredSession? = sessions[tokenHash]

    override fun deleteSession(tokenHash: String) {
        sessions.remove(tokenHash)
    }

    override fun deleteSessionsForAccount(accountId: Long) {
        sessions.entries.removeIf { it.value.accountId == accountId }
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

}

