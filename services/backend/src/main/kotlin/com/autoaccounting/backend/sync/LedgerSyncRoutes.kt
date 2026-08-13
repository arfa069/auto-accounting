package com.autoaccounting.backend.sync

import com.autoaccounting.api.LEDGER_SYNC_MAX_REQUEST_BYTES
import com.autoaccounting.api.LedgerSyncJsonContracts
import com.autoaccounting.backend.account.AccountResult
import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.accountBearerToken
import com.autoaccounting.backend.account.respondAccountFailure
import com.autoaccounting.backend.receiveText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.ledgerSyncRoutes(service: LedgerSyncService, accountService: AccountService) {
    registerInitializeRoute(service, accountService)
    registerSnapshotRoute(service, accountService)
    registerPushRoute(service, accountService)
    registerPullRoute(service, accountService)
    registerResolveRoute(service, accountService)
}

private fun Route.registerInitializeRoute(service: LedgerSyncService, accountService: AccountService) {
    post("/account/ledger-sync/initialize") {
        val accountId = call.verifiedAccountId(accountService) ?: return@post
        val request = call.parseBodyOrReject(LedgerSyncJsonContracts::parseInitializeRequest) ?: return@post
        if (!request.deviceId.isValidLedgerSyncDeviceId()) {
            return@post call.respondSyncError("INVALID_REQUEST", HttpStatusCode.BadRequest)
        }
        call.respondSync(LedgerSyncJsonContracts.encodeInitializeResponse(service.initialize(accountId)))
    }
}

private fun Route.registerSnapshotRoute(service: LedgerSyncService, accountService: AccountService) {
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
}

private fun Route.registerPushRoute(service: LedgerSyncService, accountService: AccountService) {
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
}

private fun Route.registerPullRoute(service: LedgerSyncService, accountService: AccountService) {
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
}

private fun Route.registerResolveRoute(service: LedgerSyncService, accountService: AccountService) {
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
    val body = try {
        receiveText(LEDGER_SYNC_MAX_REQUEST_BYTES)
    } catch (_: PayloadTooLargeException) {
        respondSyncError("SYNC_PAYLOAD_TOO_LARGE", HttpStatusCode.PayloadTooLarge)
        return null
    }
    return runCatching { parser(body) }.getOrElse {
        respondSyncError("INVALID_REQUEST", HttpStatusCode.BadRequest)
        null
    }
}
