package com.autoaccounting.backend.sync

import com.autoaccounting.api.LEDGER_SYNC_MAX_BATCH_SIZE
import com.autoaccounting.api.LedgerSyncConflictChoiceContract
import com.autoaccounting.api.LedgerSyncInitializeResponseContract
import com.autoaccounting.api.LedgerSyncMutationContract
import com.autoaccounting.api.LedgerSyncPayloadContract
import com.autoaccounting.api.LedgerSyncPullResponseContract
import com.autoaccounting.api.LedgerSyncPushResponseContract
import com.autoaccounting.api.LedgerSyncResolveConflictResponseContract
import com.autoaccounting.api.LedgerSyncSnapshotResponseContract
import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.JdbcAccountStore
import java.time.Clock

sealed interface LedgerSyncServiceResult<out T> {
    data class Success<T>(val value: T) : LedgerSyncServiceResult<T>
    data object InvalidRequest : LedgerSyncServiceResult<Nothing>
    data object DeletionPending : LedgerSyncServiceResult<Nothing>
    data object CursorExpired : LedgerSyncServiceResult<Nothing>
    data object ConflictMissing : LedgerSyncServiceResult<Nothing>
    data object ConflictStale : LedgerSyncServiceResult<Nothing>
}

class LedgerSyncService(
    private val store: LedgerSyncStore = InMemoryLedgerSyncStore(),
    private val accountService: AccountService,
    private val clock: Clock = Clock.systemUTC()
) {
    fun initialize(accountId: Long): LedgerSyncInitializeResponseContract {
        val profile = store.getOrCreateProfile(accountId, clock.millis())
        return LedgerSyncInitializeResponseContract(
            profileKey = profile.profileKey,
            recordCount = store.recordCount(accountId),
            currentCursor = store.currentCursor(accountId)
        )
    }

    fun snapshot(accountId: Long, offset: Int, limit: Int): LedgerSyncServiceResult<LedgerSyncSnapshotResponseContract> {
        if (offset < 0 || limit !in 1..LEDGER_SYNC_MAX_BATCH_SIZE) return LedgerSyncServiceResult.InvalidRequest
        val records = store.snapshot(accountId, offset, limit + 1)
        val visible = records.take(limit)
        return LedgerSyncServiceResult.Success(
            LedgerSyncSnapshotResponseContract(
                records = visible,
                nextOffset = (offset + visible.size).takeIf { records.size > limit },
                currentCursor = store.currentCursor(accountId)
            )
        )
    }

    fun push(
        accountId: Long,
        deviceId: String,
        mutations: List<LedgerSyncMutationContract>
    ): LedgerSyncServiceResult<LedgerSyncPushResponseContract> {
        if (!accountService.canWriteCloudData(accountId)) return LedgerSyncServiceResult.DeletionPending
        if (
            deviceId.isBlank() || deviceId.length > MAX_SYNC_DEVICE_ID_LENGTH ||
            mutations.isEmpty() || mutations.size > LEDGER_SYNC_MAX_BATCH_SIZE ||
            mutations.map { it.mutationId }.distinct().size != mutations.size ||
            mutations.any { !it.isValid() }
        ) return LedgerSyncServiceResult.InvalidRequest
        store.getOrCreateProfile(accountId, clock.millis())
        val results = store.push(accountId, deviceId, mutations, clock.millis())
        return LedgerSyncServiceResult.Success(
            LedgerSyncPushResponseContract(results, store.currentCursor(accountId))
        )
    }

    fun pull(
        accountId: Long,
        deviceId: String,
        afterCursor: Long,
        limit: Int
    ): LedgerSyncServiceResult<LedgerSyncPullResponseContract> {
        if (
            deviceId.isBlank() || deviceId.length > MAX_SYNC_DEVICE_ID_LENGTH ||
            afterCursor < 0 || limit !in 1..LEDGER_SYNC_MAX_BATCH_SIZE
        ) {
            return LedgerSyncServiceResult.InvalidRequest
        }
        if (afterCursor > store.currentCursor(accountId)) return LedgerSyncServiceResult.CursorExpired
        val page = store.pull(accountId, afterCursor, limit)
        return LedgerSyncServiceResult.Success(
            LedgerSyncPullResponseContract(page.records, page.conflicts, page.nextCursor, page.hasMore)
        )
    }

    fun resolve(
        accountId: Long,
        conflictId: String,
        expectedCanonicalVersion: Long,
        choice: LedgerSyncConflictChoiceContract
    ): LedgerSyncServiceResult<LedgerSyncResolveConflictResponseContract> {
        if (!accountService.canWriteCloudData(accountId)) return LedgerSyncServiceResult.DeletionPending
        if (
            conflictId.isBlank() || conflictId.length > MAX_SYNC_CONFLICT_ID_LENGTH ||
            expectedCanonicalVersion < 0
        ) return LedgerSyncServiceResult.InvalidRequest
        return when (
            val result = store.resolve(accountId, conflictId, expectedCanonicalVersion, choice, clock.millis())
        ) {
            is LedgerSyncResolutionResult.Resolved -> LedgerSyncServiceResult.Success(
                LedgerSyncResolveConflictResponseContract(result.record)
            )
            LedgerSyncResolutionResult.Missing -> LedgerSyncServiceResult.ConflictMissing
            LedgerSyncResolutionResult.Stale -> LedgerSyncServiceResult.ConflictStale
        }
    }

    fun deleteForAccount(accountId: Long) = store.deleteForAccount(accountId)


    companion object {
        fun fromEnvironment(
            accountService: AccountService,
            env: Map<String, String> = System.getenv()
        ): LedgerSyncService {
            val config = JdbcAccountStore.configFromEnvironment(env)
                ?: error("AUTO_ACCOUNTING_DATABASE_URL is required for ledger sync persistence.")
            return LedgerSyncService(
                store = JdbcLedgerSyncStore(config.jdbcUrl, config.username, config.password),
                accountService = accountService
            )
        }
    }
}

private fun LedgerSyncMutationContract.isValid(): Boolean {
    if (
        mutationId.isBlank() || mutationId.length > MAX_SYNC_MUTATION_ID_LENGTH ||
        entityId.isBlank() || entityId.length > MAX_SYNC_ENTITY_ID_LENGTH ||
        baseVersion < 0
    ) return false
    if (deleted != (payload == null)) return false
    return when (val value = payload) {
        null -> true
        is LedgerSyncPayloadContract.Category -> value.id == entityId && value.name.isNotBlank()
        is LedgerSyncPayloadContract.FundingAccount -> value.syncId == entityId && value.label.isNotBlank()
        is LedgerSyncPayloadContract.LedgerBook -> value.id == entityId && value.name.isNotBlank()
        is LedgerSyncPayloadContract.LedgerEntry ->
            value.id == entityId && value.ledgerBookId.isNotBlank() && value.amountMinor > 0 &&
                value.currency == "CNY" && value.merchantTitle.isNotBlank()
        is LedgerSyncPayloadContract.CategorizationRule -> value.id == entityId && value.category.isNotBlank()
    }
}

internal const val MAX_SYNC_DEVICE_ID_LENGTH = 128
private const val MAX_SYNC_MUTATION_ID_LENGTH = 64
private const val MAX_SYNC_ENTITY_ID_LENGTH = 128
private const val MAX_SYNC_CONFLICT_ID_LENGTH = 64
