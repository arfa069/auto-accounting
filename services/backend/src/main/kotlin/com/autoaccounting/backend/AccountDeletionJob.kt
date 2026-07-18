package com.autoaccounting.backend

import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.ai.AiCategorizationService
import com.autoaccounting.backend.config.CloudConfigService

class AccountDeletionJob(
    private val accountService: AccountService,
    private val aiCategorizationService: AiCategorizationService,
    private val cloudConfigService: CloudConfigService
) {
    fun runDueDeletion(): List<String> {
        return accountService.accountsDueForDeletion().mapNotNull { phone ->
            runCatching {
                aiCategorizationService.deleteLogsForAccount(phone)
                cloudConfigService.deleteConfig(phone)
                phone.takeIf { accountService.finalizeAccountDeletion(phone) }
            }.getOrNull()
        }
    }
}
