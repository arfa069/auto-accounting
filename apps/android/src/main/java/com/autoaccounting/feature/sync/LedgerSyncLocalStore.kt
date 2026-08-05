@file:Suppress("TooManyFunctions")

package com.autoaccounting.feature.sync

import androidx.room.withTransaction
import com.autoaccounting.api.LedgerSyncConflictContract
import com.autoaccounting.api.LedgerSyncMutationContract
import com.autoaccounting.api.LedgerSyncMutationResultContract
import com.autoaccounting.api.LedgerSyncRecordContract
import com.autoaccounting.data.local.AccountSyncStateEntity
import com.autoaccounting.data.local.AutoAccountingDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class LedgerSyncLocalStore(
    private val database: AutoAccountingDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    private val recordApplier = LedgerSyncRecordApplier(database, clock, idGenerator)
    private val snapshotOperations = LedgerSyncSnapshotOperations(
        database = database,
        recordApplier = recordApplier,
        clock = clock,
        idGenerator = idGenerator
    )

    val state: Flow<AccountSyncStateEntity?> = database.ledgerSyncDao().observeState()
    val outboxCount: Flow<Int> = database.ledgerSyncDao().observeOutboxCount()
    val conflicts: Flow<List<com.autoaccounting.data.local.AccountSyncConflictEntity>> =
        database.ledgerSyncDao().observeConflicts()

    suspend fun currentState(): AccountSyncStateEntity =
        database.ledgerSyncDao().getState() ?: AccountSyncStateEntity()

    suspend fun formalRecordCount(): Int = database.withTransaction {
        database.categoryDao().getAllCategories().size +
            database.fundingAccountDao().getAllFundingAccounts().size +
            database.ledgerBookDao().getAll().size +
            database.ledgerEntryDao().listAllLedgerEntries().size +
            database.categorizationRuleDao().listRules().size
    }

    suspend fun pendingMutationCount(): Int = database.ledgerSyncDao().outboxCount()

    suspend fun enable(profileKey: String) = database.withTransaction {
        require(profileKey.isNotBlank())
        val current = database.ledgerSyncDao().getState()
        require(current?.profileKey == null || current.profileKey == profileKey) {
            "Local ledger is bound to another account"
        }
        recordApplier.ensureFundingSyncIds()
        database.ledgerSyncDao().upsertState(
            (current ?: AccountSyncStateEntity()).copy(profileKey = profileKey, enabled = true, lastError = null)
        )
    }

    suspend fun disableAndUnbind() = database.withTransaction {
        database.ledgerSyncDao().upsertState(AccountSyncStateEntity())
        database.ledgerSyncDao().deleteAllMetadata()
        database.ledgerSyncDao().deleteAllOutbox()
        database.ledgerSyncDao().deleteAllConflicts()
    }

    suspend fun pauseWithError(message: String) {
        val current = currentState()
        database.ledgerSyncDao().upsertState(current.copy(lastError = message))
    }

    suspend fun markSuccess(cursor: Long) {
        val current = currentState()
        database.ledgerSyncDao().upsertState(
            current.copy(cursor = cursor, lastSuccessAtMillis = clock(), lastError = null)
        )
    }

    suspend fun reconcile() = database.withTransaction {
        if (database.ledgerSyncDao().getState()?.enabled != true) return@withTransaction
        recordApplier.ensureFundingSyncIds()
        snapshotOperations.reconcileOutbox()
    }

    suspend fun listMutations(limit: Int): List<LedgerSyncMutationContract> =
        database.ledgerSyncDao().listOutbox(limit).map { it.toContract() }

    suspend fun applyPushResults(results: List<LedgerSyncMutationResultContract>) =
        database.withTransaction {
            snapshotOperations.applyPushResults(results)
        }

    suspend fun applyRemote(
        records: List<LedgerSyncRecordContract>,
        conflicts: List<LedgerSyncConflictContract>
    ) = database.withTransaction {
        snapshotOperations.applyRemote(records, conflicts)
    }

    suspend fun replaceWithSnapshot(records: List<LedgerSyncRecordContract>) =
        database.withTransaction {
            snapshotOperations.replaceFormalData(records, resetActiveLedger = false)
        }

    suspend fun switchProfileWithSnapshot(
        profileKey: String,
        records: List<LedgerSyncRecordContract>
    ) = database.withTransaction {
        require(profileKey.isNotBlank())
        check(database.ledgerSyncDao().outboxCount() == 0) {
            "Pending sync mutations must be uploaded first"
        }
        database.ledgerSyncDao().upsertState(
            AccountSyncStateEntity(profileKey = profileKey, enabled = true)
        )
        snapshotOperations.replaceFormalData(records, resetActiveLedger = true)
    }

    suspend fun mergeSnapshot(records: List<LedgerSyncRecordContract>) =
        database.withTransaction {
            snapshotOperations.mergeSnapshot(records)
        }

    suspend fun resolved(record: LedgerSyncRecordContract, conflictId: String) =
        database.withTransaction {
            snapshotOperations.resolved(record, conflictId)
        }
}
