package com.bks.feature.settings

import androidx.room.withTransaction
import com.bks.data.local.BksDatabase
import com.bks.data.local.CategorizationRuleEntity
import com.bks.data.local.CategoryEntity
import com.bks.data.local.FundingAccountEntity
import com.bks.data.local.IgnoredEntryEntity
import com.bks.data.local.LedgerBookEntity
import com.bks.data.local.LedgerEntryEntity
import com.bks.data.local.LocalSettingsEntity
import com.bks.data.local.LocalSyncMutationRecorder
import com.bks.data.local.PendingEntryEntity
import com.bks.data.local.DEFAULT_LEDGER_BOOK_ID
import com.bks.data.local.DEFAULT_LEDGER_BOOK_NAME
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
    private val database: BksDatabase
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
                settings = (database.localSettingsDao().getById()
                    ?: defaultBackupSettings(ledgerBooks.first().id))
                    .copy(automaticBookkeepingEnabled = false),
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
                database.localSettingsDao().upsert(
                    requireNotNull(snapshot.settings).copy(automaticBookkeepingEnabled = false)
                )
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
