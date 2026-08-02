package com.autoaccounting.backend.sync

import com.autoaccounting.api.LedgerSyncConflictChoiceContract
import com.autoaccounting.api.LedgerSyncInitializeResponseContract
import com.autoaccounting.api.LedgerSyncMutationContract
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
        if (!isValidLedgerSyncSnapshotRequest(offset, limit)) return LedgerSyncServiceResult.InvalidRequest
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
        if (!isValidLedgerSyncPushRequest(deviceId, mutations)) return LedgerSyncServiceResult.InvalidRequest
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
        if (!isValidLedgerSyncPullRequest(deviceId, afterCursor, limit)) {
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
        if (!isValidLedgerSyncResolveRequest(conflictId, expectedCanonicalVersion)) {
            return LedgerSyncServiceResult.InvalidRequest
        }
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
