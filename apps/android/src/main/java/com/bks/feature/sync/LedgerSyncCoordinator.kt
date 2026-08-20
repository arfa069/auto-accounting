package com.bks.feature.sync

import com.bks.api.LEDGER_SYNC_MAX_BATCH_SIZE
import com.bks.api.LedgerSyncConflictChoiceContract
import com.bks.api.LedgerSyncRecordContract
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LedgerSyncPreview(
    val profileKey: String,
    val localRecordCount: Int,
    val cloudRecordCount: Int,
    val insecureHttpTestMode: Boolean
)

enum class LedgerSyncInitialMode {
    MERGE,
    REPLACE_LOCAL
}

sealed interface LedgerSyncOperationResult<out T> {
    data class Success<T>(val value: T) : LedgerSyncOperationResult<T>
    data class Failure(val code: String?, val message: String, val retryable: Boolean) : LedgerSyncOperationResult<Nothing>
}

class LedgerSyncCoordinator(
    private val localStore: LedgerSyncLocalStore,
    private val repository: LedgerSyncRepository,
    private val deviceId: () -> String
) {
    suspend fun preview(token: String): LedgerSyncOperationResult<LedgerSyncPreview> = mutex.withLock {
        when (val initialized = repository.initialize(token, deviceId())) {
            is LedgerSyncRemoteResult.Failure -> initialized.toOperationFailure()
            is LedgerSyncRemoteResult.Success -> LedgerSyncOperationResult.Success(
                LedgerSyncPreview(
                    profileKey = initialized.value.profileKey,
                    localRecordCount = localStore.formalRecordCount(),
                    cloudRecordCount = initialized.value.recordCount,
                    insecureHttpTestMode = repository.insecureHttpTestMode
                )
            )
        }
    }

    suspend fun enable(
        token: String,
        mode: LedgerSyncInitialMode
    ): LedgerSyncOperationResult<Unit> = mutex.withLock {
        val initialized = when (val result = repository.initialize(token, deviceId())) {
            is LedgerSyncRemoteResult.Failure -> return@withLock result.toOperationFailure()
            is LedgerSyncRemoteResult.Success -> result.value
        }
        val snapshot = if (initialized.recordCount > 0) {
            when (val result = fetchSnapshot(token)) {
                is LedgerSyncOperationResult.Failure -> return@withLock result
                is LedgerSyncOperationResult.Success -> result.value
            }
        } else {
            SnapshotDownload(emptyList(), initialized.currentCursor)
        }
        val current = localStore.currentState()
        if (current.profileKey != null && current.profileKey != initialized.profileKey) {
            return@withLock LedgerSyncOperationResult.Failure(
                "SYNC_ACCOUNT_SWITCH_REQUIRED",
                "当前本机账本已绑定另一账户，请先完成账户切换",
                false
            )
        }
        localStore.enable(initialized.profileKey)
        when {
            snapshot.records.isEmpty() -> Unit
            mode == LedgerSyncInitialMode.REPLACE_LOCAL || localStore.formalRecordCount() == 0 ->
                localStore.replaceWithSnapshot(snapshot.records)
            else -> localStore.mergeSnapshot(snapshot.records)
        }
        synchronizeLocked(token)
    }

    suspend fun synchronize(token: String): LedgerSyncOperationResult<Unit> = mutex.withLock {
        synchronizeLocked(token)
    }

    suspend fun switchAccount(token: String): LedgerSyncOperationResult<Unit> = mutex.withLock {
        if (localStore.pendingMutationCount() > 0) {
            return@withLock LedgerSyncOperationResult.Failure(
                "SYNC_PENDING_CHANGES",
                "原账户仍有待上传数据，请先恢复原账户完成同步或导出加密备份",
                false
            )
        }
        val initialized = when (val result = repository.initialize(token, deviceId())) {
            is LedgerSyncRemoteResult.Failure -> return@withLock result.toOperationFailure()
            is LedgerSyncRemoteResult.Success -> result.value
        }
        val snapshot = when (val result = fetchSnapshot(token)) {
            is LedgerSyncOperationResult.Failure -> return@withLock result
            is LedgerSyncOperationResult.Success -> result.value
        }
        localStore.switchProfileWithSnapshot(initialized.profileKey, snapshot.records)
        localStore.reconcile()
        synchronizeLocked(token)
    }

    suspend fun resolveConflict(
        token: String,
        conflictId: String,
        expectedVersion: Long,
        choice: LedgerSyncConflictChoiceContract
    ): LedgerSyncOperationResult<Unit> = mutex.withLock {
        when (val result = repository.resolve(token, conflictId, expectedVersion, choice)) {
            is LedgerSyncRemoteResult.Failure -> {
                if (result.code == "SYNC_CONFLICT_STALE") synchronizeLocked(token)
                result.toOperationFailure()
            }
            is LedgerSyncRemoteResult.Success -> {
                localStore.resolved(result.value.record, conflictId)
                LedgerSyncOperationResult.Success(Unit)
            }
        }
    }

    private suspend fun synchronizeLocked(token: String): LedgerSyncOperationResult<Unit> {
        val state = localStore.currentState()
        if (!state.enabled) return LedgerSyncOperationResult.Success(Unit)
        val initialized = when (val result = repository.initialize(token, deviceId())) {
            is LedgerSyncRemoteResult.Failure -> {
                localStore.pauseWithError(result.message)
                return result.toOperationFailure()
            }
            is LedgerSyncRemoteResult.Success -> result.value
        }
        if (state.profileKey != initialized.profileKey) {
            localStore.pauseWithError("当前登录账户与本机同步账户不同，请确认切换账户数据")
            return LedgerSyncOperationResult.Failure(
                "SYNC_ACCOUNT_SWITCH_REQUIRED",
                "当前登录账户与本机同步账户不同，请确认切换账户数据",
                false
            )
        }
        localStore.reconcile()
        val pushed = pushMutations(token)
        if (pushed is LedgerSyncOperationResult.Failure) return pushed
        return pullMutations(token)
    }

    private suspend fun pushMutations(token: String): LedgerSyncOperationResult<Unit> {
        while (true) {
            val mutations = localStore.listMutations(LEDGER_SYNC_MAX_BATCH_SIZE)
            if (mutations.isEmpty()) break
            when (val pushed = repository.push(token, deviceId(), mutations)) {
                is LedgerSyncRemoteResult.Failure -> {
                    localStore.pauseWithError(pushed.message)
                    return pushed.toOperationFailure()
                }
                is LedgerSyncRemoteResult.Success -> {
                    localStore.applyPushResults(pushed.value.results)
                    localStore.reconcile()
                }
            }
        }
        return LedgerSyncOperationResult.Success(Unit)
    }

    private suspend fun pullMutations(token: String): LedgerSyncOperationResult<Unit> {
        var cursor = localStore.currentState().cursor
        while (true) {
            when (val pulled = repository.pull(token, deviceId(), cursor)) {
                is LedgerSyncRemoteResult.Failure -> {
                    if (pulled.code == "SYNC_CURSOR_EXPIRED") {
                        return recoverExpiredCursor(token)
                    }
                    localStore.pauseWithError(pulled.message)
                    return pulled.toOperationFailure()
                }
                is LedgerSyncRemoteResult.Success -> {
                    localStore.applyRemote(pulled.value.records, pulled.value.conflicts)
                    cursor = pulled.value.nextCursor
                    if (!pulled.value.hasMore) break
                }
            }
        }
        localStore.markSuccess(cursor)
        return LedgerSyncOperationResult.Success(Unit)
    }

    private suspend fun recoverExpiredCursor(token: String): LedgerSyncOperationResult<Unit> {
        val snapshot = when (val result = fetchSnapshot(token)) {
            is LedgerSyncOperationResult.Failure -> return result
            is LedgerSyncOperationResult.Success -> result.value
        }
        localStore.replaceWithSnapshot(snapshot.records)
        var cursor = snapshot.cursor
        while (true) {
            when (val pulled = repository.pull(token, deviceId(), cursor)) {
                is LedgerSyncRemoteResult.Failure -> {
                    localStore.pauseWithError(pulled.message)
                    return pulled.toOperationFailure()
                }
                is LedgerSyncRemoteResult.Success -> {
                    localStore.applyRemote(pulled.value.records, pulled.value.conflicts)
                    cursor = pulled.value.nextCursor
                    if (!pulled.value.hasMore) break
                }
            }
        }
        localStore.markSuccess(cursor)
        return LedgerSyncOperationResult.Success(Unit)
    }

    private suspend fun fetchSnapshot(token: String): LedgerSyncOperationResult<SnapshotDownload> {
        val records = mutableListOf<LedgerSyncRecordContract>()
        var offset = 0
        var cursor = 0L
        while (true) {
            when (val page = repository.snapshot(token, offset)) {
                is LedgerSyncRemoteResult.Failure -> return page.toOperationFailure()
                is LedgerSyncRemoteResult.Success -> {
                    records += page.value.records
                    cursor = page.value.currentCursor
                    offset = page.value.nextOffset ?: break
                }
            }
        }
        return LedgerSyncOperationResult.Success(SnapshotDownload(records, cursor))
    }

    companion object {
        private val mutex = Mutex()
    }

    private data class SnapshotDownload(
        val records: List<LedgerSyncRecordContract>,
        val cursor: Long
    )
}

private fun LedgerSyncRemoteResult.Failure.toOperationFailure() =
    LedgerSyncOperationResult.Failure(code, message, retryable)
