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

    fun writeConfig(config: StoredCloudConfig): CloudConfigResult {
        if (!accountService.canWriteCloudData(config.phone)) {
            return CloudConfigResult.DeletionPending
        }
        store.upsertConfig(config)
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
