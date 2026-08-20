package com.bks.backend

import com.bks.backend.account.AccountService
import com.bks.backend.ai.AiCategorizationService
import com.bks.backend.config.CloudConfigService
import com.bks.backend.sync.LedgerSyncService

class AccountDeletionJob(
    private val accountService: AccountService,
    private val aiCategorizationService: AiCategorizationService,
    private val cloudConfigService: CloudConfigService,
    private val ledgerSyncService: LedgerSyncService? = null
) {
    fun runDueDeletion(): List<Long> {
        return accountService.accountsDueForDeletion().mapNotNull { accountId ->
            runCatching {
                aiCategorizationService.deleteLogsForAccount(accountId)
                cloudConfigService.deleteConfig(accountId)
                ledgerSyncService?.deleteForAccount(accountId)
                accountId.takeIf { accountService.finalizeAccountDeletion(accountId) }
            }.getOrNull()
        }
    }
}
