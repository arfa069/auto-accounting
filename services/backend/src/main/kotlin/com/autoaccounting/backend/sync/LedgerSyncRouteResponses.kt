package com.autoaccounting.backend.sync

import com.autoaccounting.api.AccountApiJsonContracts
import com.autoaccounting.api.AccountErrorResponseContract
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

internal suspend fun ApplicationCall.respondServiceFailure(result: LedgerSyncServiceResult<*>) {
    when (result) {
        LedgerSyncServiceResult.InvalidRequest -> respondSyncError("INVALID_REQUEST", HttpStatusCode.BadRequest)
        LedgerSyncServiceResult.DeletionPending ->
            respondSyncError("ACCOUNT_DELETION_PENDING", HttpStatusCode.Conflict)
        LedgerSyncServiceResult.CursorExpired ->
            respondSyncError("SYNC_CURSOR_EXPIRED", HttpStatusCode.Conflict)
        LedgerSyncServiceResult.ConflictMissing ->
            respondSyncError("SYNC_CONFLICT_NOT_FOUND", HttpStatusCode.NotFound)
        LedgerSyncServiceResult.ConflictStale ->
            respondSyncError("SYNC_CONFLICT_STALE", HttpStatusCode.Conflict)
        is LedgerSyncServiceResult.Success -> error("Success must be handled by the route.")
    }
}

internal suspend fun ApplicationCall.respondSync(body: String) {
    respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
}

internal suspend fun ApplicationCall.respondSyncError(code: String, status: HttpStatusCode) {
    respondText(
        AccountApiJsonContracts.encodeErrorResponse(
            AccountErrorResponseContract(error = code, message = syncErrorMessage(code))
        ),
        ContentType.Application.Json,
        status
    )
}

private fun syncErrorMessage(code: String): String = when (code) {
    "SYNC_PAYLOAD_TOO_LARGE" -> "同步数据超过单次请求限制"
    "SYNC_CONFLICT_NOT_FOUND" -> "同步冲突不存在或已解决"
    "SYNC_CONFLICT_STALE" -> "云端数据已更新，请刷新冲突后重试"
    "SYNC_CURSOR_EXPIRED" -> "同步游标已失效，请重新下载云端快照"
    "ACCOUNT_DELETION_PENDING" -> "账号注销冷静期内，同步写入已暂停"
    else -> "同步请求格式不正确"
}
