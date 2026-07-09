package com.autoaccounting.backend.config

data class StoredCloudConfig(
    val phone: String,
    val aiConsentGranted: Boolean = false,
    val enhancedContextGranted: Boolean = false,
    val featureFlags: Map<String, Boolean> = emptyMap(),
    val updatedAtMillis: Long
)

interface CloudConfigStore {
    fun findConfig(phone: String): StoredCloudConfig?
    fun upsertConfig(config: StoredCloudConfig)
    fun deleteConfig(phone: String)
}

class InMemoryCloudConfigStore : CloudConfigStore {
    private val configs = mutableMapOf<String, StoredCloudConfig>()

    override fun findConfig(phone: String): StoredCloudConfig? = configs[phone]

    override fun upsertConfig(config: StoredCloudConfig) {
        configs[config.phone] = config
    }

    override fun deleteConfig(phone: String) {
        configs.remove(phone)
    }
}
