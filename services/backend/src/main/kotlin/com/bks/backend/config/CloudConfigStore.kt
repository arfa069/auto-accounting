package com.bks.backend.config

data class StoredCloudConfig(
    val accountId: Long,
    val aiConsentGranted: Boolean = false,
    val enhancedContextGranted: Boolean = false,
    val featureFlags: Map<String, Boolean> = emptyMap(),
    val updatedAtMillis: Long,
    val defaultFundingAccountSyncId: String? = null
)

interface CloudConfigStore {
    fun findConfig(accountId: Long): StoredCloudConfig?
    fun upsertConfig(config: StoredCloudConfig)
    fun deleteConfig(accountId: Long)
}

class InMemoryCloudConfigStore : CloudConfigStore {
    private val configs = mutableMapOf<Long, StoredCloudConfig>()

    override fun findConfig(accountId: Long): StoredCloudConfig? = configs[accountId]

    override fun upsertConfig(config: StoredCloudConfig) {
        configs[config.accountId] = config
    }

    override fun deleteConfig(accountId: Long) {
        configs.remove(accountId)
    }
}
