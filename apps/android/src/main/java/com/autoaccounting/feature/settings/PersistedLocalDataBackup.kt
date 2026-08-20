package com.autoaccounting.feature.settings

import androidx.room.withTransaction
import com.autoaccounting.data.local.AutoAccountingDatabase
import com.autoaccounting.data.local.CategorizationRuleEntity
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.IgnoredEntryEntity
import com.autoaccounting.data.local.LedgerBookEntity
import com.autoaccounting.data.local.LedgerEntryEntity
import com.autoaccounting.data.local.LocalSettingsEntity
import com.autoaccounting.data.local.LocalSyncMutationRecorder
import com.autoaccounting.data.local.PendingEntryEntity
import com.autoaccounting.data.local.DEFAULT_LEDGER_BOOK_ID
import com.autoaccounting.data.local.DEFAULT_LEDGER_BOOK_NAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PersistedLocalDataSnapshot(
    val categories: List<CategoryEntity>,
    val fundingAccounts: List<FundingAccountEntity>,
    val pendingEntries: List<PendingEntryEntity>,
    val ledgerEntries: List<LedgerEntryEntity>,
    val ignoredEntries: List<IgnoredEntryEntity>,
    val categorizationRules: List<CategorizationRuleEntity>,
    val settings: LocalSettingsEntity?,
    val ledgerBooks: List<LedgerBookEntity> = listOf(defaultBackupLedgerBook())
)

class LocalDataBackupRepository(
    private val database: AutoAccountingDatabase
) {
    suspend fun exportEncryptedBackup(passphrase: String): String = withContext(Dispatchers.Default) {
        val snapshot = database.withTransaction {
            val ledgerBooks = database.ledgerBookDao().getAll()
                .ifEmpty { listOf(defaultBackupLedgerBook()) }
            PersistedLocalDataSnapshot(
                categories = database.categoryDao().getAllCategories(),
                fundingAccounts = database.fundingAccountDao().getAllFundingAccounts(),
                pendingEntries = database.pendingEntryDao().listPendingEntries(),
                ledgerEntries = database.ledgerEntryDao().listAllLedgerEntries(),
                ignoredEntries = database.ignoredEntryDao().listAll(),
                categorizationRules = database.categorizationRuleDao().listRules(),
                settings = database.localSettingsDao().getById()
                    ?: defaultBackupSettings(ledgerBooks.first().id),
                ledgerBooks = ledgerBooks
            )
        }
        encryptPersistedLocalData(snapshot, passphrase)
    }

    suspend fun importEncryptedBackup(
        backupText: String,
        passphrase: String
    ) {
        val snapshot = decodeAndValidate(backupText, passphrase)
        withContext(Dispatchers.IO) {
            database.withTransaction {
                database.ledgerEntryDao().deleteAll()
                database.pendingEntryDao().deleteAll()
                database.ignoredEntryDao().deleteAll()
                database.fundingAccountDao().deleteAll()
                database.categoryDao().deleteAll()
                database.ledgerBookDao().deleteAll()
                database.categorizationRuleDao().deleteAll()
                database.localSettingsDao().deleteAll()

                database.categoryDao().upsertAll(snapshot.categories)
                database.fundingAccountDao().upsertAll(snapshot.fundingAccounts)
                database.ledgerBookDao().insertAll(snapshot.ledgerBooks)
                database.pendingEntryDao().upsertAll(snapshot.pendingEntries)
                database.ledgerEntryDao().upsertAll(snapshot.ledgerEntries)
                database.ignoredEntryDao().upsertAll(snapshot.ignoredEntries)
                database.categorizationRuleDao().upsertAll(snapshot.categorizationRules)
                database.localSettingsDao().upsert(requireNotNull(snapshot.settings))
                LocalSyncMutationRecorder(database, System::currentTimeMillis).reconcileAll()
            }
        }
    }

    suspend fun validateEncryptedBackup(
        backupText: String,
        passphrase: String
    ) {
        decodeAndValidate(backupText, passphrase)
    }

    private suspend fun decodeAndValidate(
        backupText: String,
        passphrase: String
    ): PersistedLocalDataSnapshot = withContext(Dispatchers.Default) {
        decryptPersistedLocalData(backupText, passphrase).validated()
    }
}

internal fun defaultBackupLedgerBook(): LedgerBookEntity = LedgerBookEntity(
    id = DEFAULT_LEDGER_BOOK_ID,
    name = DEFAULT_LEDGER_BOOK_NAME,
    createdAtEpochMillis = 0
)

internal fun defaultBackupSettings(activeLedgerId: String): LocalSettingsEntity =
    LocalSettingsEntity(
        aiConsentGranted = false,
        enhancedContextGranted = false,
        activeLedgerId = activeLedgerId
    )
