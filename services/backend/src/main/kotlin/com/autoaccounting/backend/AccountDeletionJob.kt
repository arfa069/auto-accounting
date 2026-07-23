package com.autoaccounting.backend

import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.ai.AiCategorizationService
import com.autoaccounting.backend.config.CloudConfigService

class AccountDeletionJob(
    private val accountService: AccountService,
    private val aiCategorizationService: AiCategorizationService,
    private val cloudConfigService: CloudConfigService
) {
    fun runDueDeletion(): List<Long> {
        return accountService.accountsDueForDeletion().mapNotNull { accountId ->
            runCatching {
                aiCategorizationService.deleteLogsForAccount(accountId)
                cloudConfigService.deleteConfig(accountId)
                accountId.takeIf { accountService.finalizeAccountDeletion(accountId) }
            }.getOrNull()
        }
    }
}
