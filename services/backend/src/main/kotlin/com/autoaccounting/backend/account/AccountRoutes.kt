package com.autoaccounting.backend.account

import com.autoaccounting.api.AccountApiJsonContracts
import com.autoaccounting.api.AccountDeletionStatusContract
import com.autoaccounting.api.AccountErrorResponseContract
import com.autoaccounting.api.AccountSessionResponseContract
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.accountRoutes(accountService: AccountService) {
    post("/account/sms") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.issueSmsCode(
                phone = parameters["phone"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.local.remoteHost
            )
        )
    }

    post("/account/register") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.register(
                phone = parameters["phone"].orEmpty(),
                code = parameters["code"].orEmpty(),
                password = parameters["password"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.local.remoteHost
            )
        )
    }

    post("/account/login") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.login(
                phone = parameters["phone"].orEmpty(),
                password = parameters["password"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.local.remoteHost
            )
        )
    }

    post("/account/recover") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.recoverPassword(
                phone = parameters["phone"].orEmpty(),
                code = parameters["code"].orEmpty(),
                newPassword = parameters["password"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.local.remoteHost
            )
        )
    }

    post("/account/token/verify") {
        call.respondAccountResult(
            accountService.verifyToken(call.accountBearerToken().orEmpty()),
            includeToken = false
        )
    }

    post("/account/logout") {
        call.respondAccountResult(accountService.signOut(call.accountBearerToken().orEmpty()))
    }

    post("/account/delete/request") {
        call.respondAccountResult(
            accountService.requestAccountDeletion(call.accountBearerToken().orEmpty())
        )
    }

    post("/account/delete/cancel") {
        call.respondAccountResult(
            accountService.cancelAccountDeletion(call.accountBearerToken().orEmpty())
        )
    }

    post("/account/delete/status") {
        when (val result = accountService.getAccountDeletionStatus(call.accountBearerToken().orEmpty())) {
            is AccountResult.Success -> call.respondJson(
                AccountApiJsonContracts.encodeDeletionStatusResponse(
                    result.value?.toContract() ?: AccountDeletionStatusContract()
                ),
                HttpStatusCode.OK
            )
            is AccountResult.Failure -> call.respondAccountFailure(result.error)
        }
    }
}

internal fun ApplicationCall.accountBearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    val prefix = "Bearer "
    if (!header.startsWith(prefix, ignoreCase = true)) return null
    return header.substring(prefix.length).trim().takeIf(String::isNotBlank)
}

internal suspend fun ApplicationCall.respondAccountFailure(error: AccountError) {
    respondJson(
        AccountApiJsonContracts.encodeErrorResponse(
            AccountErrorResponseContract(error = error.name, message = error.message)
        ),
        error.statusCode()
    )
}

private suspend fun ApplicationCall.respondAccountResult(
    result: AccountResult<*>,
    includeToken: Boolean = true
) {
    when (result) {
        is AccountResult.Success<*> -> {
            val body = when (val value = result.value) {
                is AccountToken -> AccountApiJsonContracts.encodeSessionResponse(
                    value.toContract(includeToken)
                )
                is AccountDeletionStatus -> AccountApiJsonContracts.encodeDeletionStatusResponse(value.toContract())
                else -> AccountApiJsonContracts.encodeSuccessResponse()
            }
            respondJson(body, HttpStatusCode.OK)
        }
        is AccountResult.Failure -> respondAccountFailure(result.error)
    }
}

private suspend fun ApplicationCall.respondJson(body: String, status: HttpStatusCode) {
    respondText(
        text = body,
        contentType = ContentType.Application.Json,
        status = status
    )
}

private fun AccountToken.toContract(includeToken: Boolean): AccountSessionResponseContract {
    return AccountSessionResponseContract(
        phone = phone,
        token = token.takeIf { includeToken },
        deletionStatus = deletionStatus?.toContract() ?: AccountDeletionStatusContract()
    )
}

private fun AccountDeletionStatus.toContract(): AccountDeletionStatusContract {
    return AccountDeletionStatusContract(
        pending = true,
        requestedAtMillis = requestedAtMillis,
        finalDeletionAtMillis = finalDeletionAtMillis
    )
}

private fun AccountError.statusCode(): HttpStatusCode = when (this) {
    AccountError.TOKEN_INVALID,
    AccountError.LOGIN_FAILED -> HttpStatusCode.Unauthorized
    AccountError.ACCOUNT_LOCKED,
    AccountError.SMS_TOO_FREQUENT -> HttpStatusCode.TooManyRequests
    AccountError.SMS_PROVIDER_UNCONFIGURED,
    AccountError.SMS_SEND_FAILED -> HttpStatusCode.ServiceUnavailable
    AccountError.PHONE_ALREADY_REGISTERED -> HttpStatusCode.Conflict
    AccountError.PHONE_NOT_REGISTERED -> HttpStatusCode.NotFound
    AccountError.ACCOUNT_DELETION_PENDING,
    AccountError.ACCOUNT_DELETION_NOT_PENDING -> HttpStatusCode.Conflict
    AccountError.INVALID_REQUEST,
    AccountError.VERIFICATION_CODE_WRONG,
    AccountError.VERIFICATION_CODE_EXPIRED -> HttpStatusCode.BadRequest
}
