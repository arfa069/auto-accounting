@file:Suppress("LongMethod")

package com.bks.backend.account


import com.bks.api.AccountApiJsonContracts
import com.bks.api.AccountDeletionStatusContract
import com.bks.api.AccountErrorResponseContract
import com.bks.api.AccountSessionResponseContract
import com.bks.backend.receiveParameters
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.accountRoutes(accountService: AccountService) {
    post("/account/verification-code") {
        val parameters = call.receiveParameters()
        val identifier = parameters["identifier"].orEmpty()
        call.respondAccountResult(
            accountService.issueVerificationCode(
                identifier = identifier,
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.origin.remoteHost,
                purpose = parameters["purpose"].orEmpty(),
                contextKey = parameters["contextKey"],
                bearerToken = call.accountBearerToken()
            )
        )
    }

    post("/account/register") {
        val parameters = call.receiveParameters()
        val identifier = parameters["identifier"].orEmpty()
        call.respondAccountResult(
            accountService.registerIdentifier(
                identifier = identifier,
                code = parameters["code"].orEmpty(),
                password = parameters["password"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.origin.remoteHost
            )
        )
    }

    post("/account/login") {
        val parameters = call.receiveParameters()
        val identifier = parameters["identifier"].orEmpty()
        call.respondAccountResult(
            accountService.loginIdentifier(
                identifier = identifier,
                password = parameters["password"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.origin.remoteHost
            )
        )
    }

    post("/account/recover") {
        val parameters = call.receiveParameters()
        val identifier = parameters["identifier"].orEmpty()
        val password = parameters["password"].orEmpty()
        call.respondAccountResult(
            accountService.recoverPasswordByIdentifier(
                identifier = identifier,
                code = parameters["code"].orEmpty(),
                newPassword = password,
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.origin.remoteHost
            )
        )
    }

    post("/account/token/verify") {
        call.respondAccountResult(
            accountService.verifyToken(call.accountBearerToken().orEmpty()),
            includeToken = false
        )
    }

    post("/account/profile/nickname") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.updateNickname(
                token = call.accountBearerToken().orEmpty(),
                nickname = parameters["nickname"].orEmpty()
            ),
            includeToken = false
        )
    }

    post("/account/profile/avatar") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.updateAvatar(
                token = call.accountBearerToken().orEmpty(),
                avatarDataUrl = parameters["avatarDataUrl"].orEmpty()
            ),
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

    post("/account/wechat/exchange") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.exchangeWechatCode(
                code = parameters["code"].orEmpty(),
                bearerToken = call.accountBearerToken(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.origin.remoteHost
            )
        )
    }

    post("/account/wechat/register") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.registerWithWechat(
                wechatTicket = parameters["wechatTicket"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.origin.remoteHost
            )
        )
    }

    post("/account/wechat/link/password") {
        val parameters = call.receiveParameters()
        val identifier = parameters["identifier"].orEmpty()
        call.respondAccountResult(
            accountService.linkWechatWithPassword(
                wechatTicket = parameters["wechatTicket"].orEmpty(),
                identifier = identifier,
                password = parameters["password"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.origin.remoteHost
            )
        )
    }

    post("/account/wechat/link/code") {
        val parameters = call.receiveParameters()
        val identifier = parameters["identifier"].orEmpty()
        call.respondAccountResult(
            accountService.linkWechatWithCode(
                wechatTicket = parameters["wechatTicket"].orEmpty(),
                identifier = identifier,
                code = parameters["code"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.origin.remoteHost
            )
        )
    }

    post("/account/wechat/unlink/password") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.unlinkWechatWithPassword(
                bearerToken = call.accountBearerToken().orEmpty(),
                password = parameters["password"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.origin.remoteHost
            )
        )
    }

    post("/account/wechat/unlink/code") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.unlinkWechatWithCode(
                bearerToken = call.accountBearerToken().orEmpty(),
                identifier = parameters["identifier"].orEmpty(),
                code = parameters["code"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.origin.remoteHost
            )
        )
    }

    post("/account/identifier/link/prepare") {
        val parameters = call.receiveParameters()
        val identifier = parameters["identifier"].orEmpty()
        call.respondAccountResult(
            accountService.prepareIdentifierLink(
                bearerToken = call.accountBearerToken().orEmpty(),
                identifier = identifier,
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.origin.remoteHost,
                replaceExisting = parameters["replaceExisting"].toBoolean()
            )
        )
    }

    post("/account/identifier/link/complete") {
        val parameters = call.receiveParameters()
        val linkTicket = parameters["linkTicket"].orEmpty()
        call.respondAccountResult(
            accountService.confirmIdentifierLink(
                bearerToken = call.accountBearerToken().orEmpty(),
                linkTicket = linkTicket,
                code = parameters["code"].orEmpty(),
                password = parameters["password"],
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.origin.remoteHost
            )
        )
    }

    post("/account/merge/prepare/identifier-password") {
        val parameters = call.receiveParameters()
        val identifier = parameters["identifier"].orEmpty()
        call.respondAccountResult(
            accountService.prepareMergeWithIdentifierPassword(
                bearerToken = call.accountBearerToken().orEmpty(),
                identifier = identifier,
                password = parameters["password"].orEmpty()
            )
        )
    }

    post("/account/merge/confirm") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.confirmMerge(
                bearerToken = call.accountBearerToken().orEmpty(),
                mergeTicket = parameters["mergeTicket"].orEmpty(),
                confirmText = parameters["confirmText"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.origin.remoteHost
            )
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
                is com.bks.api.WechatExchangeResponseContract -> AccountApiJsonContracts.encodeWechatExchangeResponse(value)
                is com.bks.api.IdentifierLinkPrepareResponseContract -> AccountApiJsonContracts.encodeIdentifierLinkPrepareResponse(value)
                is com.bks.api.MergePreviewResponseContract -> AccountApiJsonContracts.encodeMergePreviewResponse(value)
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
        accountId = accountId,
        accountUuid = accountUuid,
        primaryIdentifier = primaryIdentifier,
        identifiers = identifiers,
        token = token.takeIf { includeToken },
        wechatLinked = wechatLinked,
        nickname = nickname,
        avatarUrl = avatarUrl,
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
    AccountError.CODE_SEND_TOO_FREQUENT,
    AccountError.SMS_TOO_FREQUENT -> HttpStatusCode.TooManyRequests
    AccountError.SMS_PROVIDER_UNCONFIGURED,
    AccountError.SMS_SEND_FAILED,
    AccountError.EMAIL_PROVIDER_UNCONFIGURED,
    AccountError.EMAIL_SEND_FAILED,
    AccountError.WECHAT_NOT_CONFIGURED,
    AccountError.WECHAT_SERVICE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
    AccountError.PHONE_ALREADY_REGISTERED,
    AccountError.IDENTIFIER_ALREADY_REGISTERED,
    AccountError.IDENTIFIER_ALREADY_LINKED,
    AccountError.IDENTIFIER_CONFLICT,
    AccountError.WECHAT_ALREADY_LINKED,
    AccountError.PHONE_ALREADY_LINKED,
    AccountError.MERGE_BLOCKED,
    AccountError.LAST_LOGIN_METHOD_CANNOT_UNLINK -> HttpStatusCode.Conflict
    AccountError.PHONE_NOT_REGISTERED,
    AccountError.IDENTIFIER_NOT_REGISTERED -> HttpStatusCode.NotFound
    AccountError.ACCOUNT_DELETION_PENDING,
    AccountError.ACCOUNT_DELETION_NOT_PENDING -> HttpStatusCode.Conflict
    AccountError.INVALID_REQUEST,
    AccountError.VERIFICATION_CODE_WRONG,
    AccountError.VERIFICATION_CODE_EXPIRED,
    AccountError.WECHAT_AUTH_FAILED,
    AccountError.TICKET_EXPIRED,
    AccountError.TICKET_ALREADY_USED -> HttpStatusCode.BadRequest
}
