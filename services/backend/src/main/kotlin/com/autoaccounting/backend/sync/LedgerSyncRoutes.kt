package com.autoaccounting.backend.sync

import com.autoaccounting.api.AccountApiJsonContracts
import com.autoaccounting.api.AccountErrorResponseContract
import com.autoaccounting.api.LEDGER_SYNC_MAX_REQUEST_BYTES
import com.autoaccounting.api.LedgerSyncJsonContracts
import com.autoaccounting.backend.account.AccountResult
import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.accountBearerToken
import com.autoaccounting.backend.account.respondAccountFailure
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.ledgerSyncRoutes(service: LedgerSyncService, accountService: AccountService) {
    post("/account/ledger-sync/initialize") {
        val accountId = call.verifiedAccountId(accountService) ?: return@post
        val request = call.parseBodyOrReject(LedgerSyncJsonContracts::parseInitializeRequest) ?: return@post
        if (request.deviceId.isBlank() || request.deviceId.length > MAX_SYNC_DEVICE_ID_LENGTH) {
            return@post call.respondSyncError("INVALID_REQUEST", HttpStatusCode.BadRequest)
        }
        call.respondSync(LedgerSyncJsonContracts.encodeInitializeResponse(service.initialize(accountId)))
    }

    post("/account/ledger-sync/snapshot") {
        val accountId = call.verifiedAccountId(accountService) ?: return@post
        val request = call.parseBodyOrReject(LedgerSyncJsonContracts::parseSnapshotRequest) ?: return@post
        when (val result = service.snapshot(accountId, request.offset, request.limit)) {
            is LedgerSyncServiceResult.Success -> call.respondSync(
                LedgerSyncJsonContracts.encodeSnapshotResponse(result.value)
            )
            else -> call.respondServiceFailure(result)
        }
    }

    post("/account/ledger-sync/push") {
        val accountId = call.verifiedAccountId(accountService) ?: return@post
        val request = call.parseBodyOrReject(LedgerSyncJsonContracts::parsePushRequest) ?: return@post
        when (val result = service.push(accountId, request.deviceId, request.mutations)) {
            is LedgerSyncServiceResult.Success -> call.respondSync(
                LedgerSyncJsonContracts.encodePushResponse(result.value)
            )
            else -> call.respondServiceFailure(result)
        }
    }

    post("/account/ledger-sync/pull") {
        val accountId = call.verifiedAccountId(accountService) ?: return@post
        val request = call.parseBodyOrReject(LedgerSyncJsonContracts::parsePullRequest) ?: return@post
        when (val result = service.pull(accountId, request.deviceId, request.afterCursor, request.limit)) {
            is LedgerSyncServiceResult.Success -> call.respondSync(
                LedgerSyncJsonContracts.encodePullResponse(result.value)
            )
            else -> call.respondServiceFailure(result)
        }
    }

    post("/account/ledger-sync/conflicts/resolve") {
        val accountId = call.verifiedAccountId(accountService) ?: return@post
        val request = call.parseBodyOrReject(LedgerSyncJsonContracts::parseResolveRequest) ?: return@post
        when (
            val result = service.resolve(
                accountId, request.conflictId, request.expectedCanonicalVersion, request.choice
            )
        ) {
            is LedgerSyncServiceResult.Success -> call.respondSync(
                LedgerSyncJsonContracts.encodeResolveResponse(result.value)
            )
            else -> call.respondServiceFailure(result)
        }
    }
}

private suspend fun ApplicationCall.verifiedAccountId(accountService: AccountService): Long? =
    when (val verified = accountService.verifyToken(accountBearerToken().orEmpty())) {
        is AccountResult.Success -> verified.value.accountId
        is AccountResult.Failure -> {
            respondAccountFailure(verified.error)
            null
        }
    }

private suspend fun <T> ApplicationCall.parseBodyOrReject(parser: (String) -> T): T? {
    val body = receiveText()
    if (body.toByteArray(Charsets.UTF_8).size > LEDGER_SYNC_MAX_REQUEST_BYTES) {
        respondSyncError("SYNC_PAYLOAD_TOO_LARGE", HttpStatusCode.PayloadTooLarge)
        return null
    }
    return runCatching { parser(body) }.getOrElse {
        respondSyncError("INVALID_REQUEST", HttpStatusCode.BadRequest)
        null
    }
}

private suspend fun ApplicationCall.respondServiceFailure(result: LedgerSyncServiceResult<*>) {
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

private suspend fun ApplicationCall.respondSync(body: String) {
    respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
}

private suspend fun ApplicationCall.respondSyncError(code: String, status: HttpStatusCode) {
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
