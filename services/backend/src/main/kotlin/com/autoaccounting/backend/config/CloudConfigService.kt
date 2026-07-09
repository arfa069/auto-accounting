package com.autoaccounting.backend.config

import com.autoaccounting.backend.account.AccountService

class CloudConfigService(
    private val store: CloudConfigStore = InMemoryCloudConfigStore(),
    private val accountService: AccountService
) {
    fun readConfig(phone: String): StoredCloudConfig {
        return store.findConfig(phone) ?: StoredCloudConfig(
            phone = phone,
            updatedAtMillis = 0
        )
    }

    fun writeConfig(
        phone: String,
        aiConsentGranted: Boolean,
        enhancedContextGranted: Boolean,
        featureFlags: String,
        updatedAtMillis: Long
    ): CloudConfigResult {
        if (!accountService.canWriteCloudData(phone)) {
            return CloudConfigResult.DeletionPending
        }
        store.upsertConfig(
            StoredCloudConfig(
                phone = phone,
                aiConsentGranted = aiConsentGranted,
                enhancedContextGranted = enhancedContextGranted,
                featureFlags = featureFlags,
                updatedAtMillis = updatedAtMillis
            )
        )
        return CloudConfigResult.Written
    }

    fun deleteConfig(phone: String) {
        store.deleteConfig(phone)
    }
}

sealed interface CloudConfigResult {
    data object Written : CloudConfigResult
    data object DeletionPending : CloudConfigResult
}
