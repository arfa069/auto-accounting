package com.autoaccounting.backend.config

import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.JdbcAccountStore

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

    fun mergeAndWriteConfig(
        phone: String,
        update: CloudConfigUpdate,
        now: Long = System.currentTimeMillis()
    ): CloudConfigResult {
        val current = readConfig(phone)
        return writeConfig(
            current.copy(
                aiConsentGranted = update.aiConsentGranted ?: current.aiConsentGranted,
                enhancedContextGranted = update.enhancedContextGranted ?: current.enhancedContextGranted,
                featureFlags = update.featureFlags ?: current.featureFlags,
                updatedAtMillis = now
            )
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

    companion object {
        fun fromEnvironment(
            accountService: AccountService,
            env: Map<String, String> = System.getenv()
        ): CloudConfigService {
            val jdbcConfig = JdbcAccountStore.configFromEnvironment(env)
                ?: error("AUTO_ACCOUNTING_DATABASE_URL is required for backend cloud config persistence.")
            return CloudConfigService(
                store = JdbcCloudConfigStore(
                    jdbcUrl = jdbcConfig.jdbcUrl,
                    username = jdbcConfig.username,
                    password = jdbcConfig.password
                ),
                accountService = accountService
            )
        }
    }
}

sealed interface CloudConfigResult {
    data object Written : CloudConfigResult
    data object DeletionPending : CloudConfigResult
}

data class CloudConfigUpdate(
    val aiConsentGranted: Boolean? = null,
    val enhancedContextGranted: Boolean? = null,
    val featureFlags: Map<String, Boolean>? = null
)
