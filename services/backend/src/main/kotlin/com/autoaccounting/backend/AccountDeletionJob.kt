package com.autoaccounting.backend

import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.ai.AiCategorizationService

class AccountDeletionJob(
    private val accountService: AccountService,
    private val aiCategorizationService: AiCategorizationService
) {
    fun runDueDeletion(): List<String> {
        val deletedPhones = accountService.deleteDueAccounts()
        deletedPhones.forEach(aiCategorizationService::deleteLogsForAccount)
        return deletedPhones
    }
}
