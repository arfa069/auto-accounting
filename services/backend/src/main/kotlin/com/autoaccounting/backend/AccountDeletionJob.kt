package com.autoaccounting.backend

import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.ai.AiCategorizationService
import com.autoaccounting.backend.config.CloudConfigService

class AccountDeletionJob(
    private val accountService: AccountService,
    private val aiCategorizationService: AiCategorizationService,
    private val cloudConfigService: CloudConfigService? = null
) {
    fun runDueDeletion(): List<String> {
        val deletedPhones = accountService.deleteDueAccounts()
        deletedPhones.forEach { phone ->
            aiCategorizationService.deleteLogsForAccount(phone)
            cloudConfigService?.deleteConfig(phone)
        }
        return deletedPhones
    }
}
