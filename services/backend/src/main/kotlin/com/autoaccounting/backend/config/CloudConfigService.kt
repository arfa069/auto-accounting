package com.autoaccounting.backend.config

import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.JdbcAccountStore

class CloudConfigService(
    private val store: CloudConfigStore = InMemoryCloudConfigStore(),
    private val accountService: AccountService
) {
    fun readConfig(accountId: Long): StoredCloudConfig {
        val stored = store.findConfig(accountId) ?: return StoredCloudConfig(
            accountId = accountId,
            updatedAtMillis = 0
        )
        return stored.copy(
            enhancedContextGranted = stored.aiConsentGranted && stored.enhancedContextGranted
        )
    }

    fun mergeAndWriteConfig(
        accountId: Long,
        update: CloudConfigUpdate,
        now: Long = System.currentTimeMillis()
    ): CloudConfigResult {
        val current = readConfig(accountId)
        val aiConsentGranted = update.aiConsentGranted ?: current.aiConsentGranted
        val enhancedContextGranted = aiConsentGranted &&
            (update.enhancedContextGranted ?: current.enhancedContextGranted)
        return writeConfig(
            current.copy(
                aiConsentGranted = aiConsentGranted,
                enhancedContextGranted = enhancedContextGranted,
                featureFlags = update.featureFlags ?: current.featureFlags,
                updatedAtMillis = now
            )
        )
    }

    fun writeConfig(config: StoredCloudConfig): CloudConfigResult {
        if (!accountService.canWriteCloudData(config.accountId)) {
            return CloudConfigResult.DeletionPending
        }
        store.upsertConfig(
            config.copy(
                enhancedContextGranted = config.aiConsentGranted && config.enhancedContextGranted
            )
        )
        return CloudConfigResult.Written
    }

    fun deleteConfig(accountId: Long) {
        store.deleteConfig(accountId)
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
