@file:Suppress("LongMethod")

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
                ipAddress = call.request.local.remoteHost,
                purpose = parameters["purpose"].orEmpty(),
                contextKey = parameters["contextKey"],
                bearerToken = call.accountBearerToken()
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

    post("/account/wechat/exchange") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.exchangeWechatCode(
                code = parameters["code"].orEmpty(),
                bearerToken = call.accountBearerToken(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.local.remoteHost
            )
        )
    }

    post("/account/wechat/register") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.registerWithWechat(
                wechatTicket = parameters["wechatTicket"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.local.remoteHost
            )
        )
    }

    post("/account/wechat/link/password") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.linkWechatWithPassword(
                wechatTicket = parameters["wechatTicket"].orEmpty(),
                phone = parameters["phone"].orEmpty(),
                password = parameters["password"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.local.remoteHost
            )
        )
    }

    post("/account/wechat/link/sms") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.linkWechatWithSms(
                wechatTicket = parameters["wechatTicket"].orEmpty(),
                phone = parameters["phone"].orEmpty(),
                code = parameters["code"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.local.remoteHost
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
                ipAddress = call.request.local.remoteHost
            )
        )
    }

    post("/account/wechat/unlink/sms") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.unlinkWechatWithSms(
                bearerToken = call.accountBearerToken().orEmpty(),
                code = parameters["code"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.local.remoteHost
            )
        )
    }

    post("/account/phone/link/prepare") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.preparePhoneLink(
                bearerToken = call.accountBearerToken().orEmpty(),
                phone = parameters["phone"].orEmpty(),
                code = parameters["code"].orEmpty()
            )
        )
    }

    post("/account/phone/link/complete") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.completePhoneLink(
                bearerToken = call.accountBearerToken().orEmpty(),
                phoneTicket = parameters["phoneTicket"].orEmpty(),
                password = parameters["password"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.local.remoteHost
            )
        )
    }


    post("/account/merge/prepare/phone-password") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.prepareMergeWithPhonePassword(
                bearerToken = call.accountBearerToken().orEmpty(),
                phone = parameters["phone"].orEmpty(),
                password = parameters["password"].orEmpty()
            )
        )
    }

    post("/account/merge/confirm") {
        val parameters = call.receiveParameters()
        call.respondAccountResult(
            accountService.confirmMerge(
                bearerToken = call.accountBearerToken().orEmpty(),
                mergeTicket = parameters["mergeTicket"].orEmpty().ifBlank { parameters["ticket"].orEmpty() },
                confirmText = parameters["confirmText"].orEmpty(),
                deviceId = parameters["deviceId"].orEmpty(),
                ipAddress = call.request.local.remoteHost
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
                is com.autoaccounting.api.WechatExchangeResponseContract -> AccountApiJsonContracts.encodeWechatExchangeResponse(value)
                is com.autoaccounting.api.PhoneLinkPrepareResponseContract -> AccountApiJsonContracts.encodePhoneLinkPrepareResponse(value)
                is com.autoaccounting.api.MergePreviewResponseContract -> AccountApiJsonContracts.encodeMergePreviewResponse(value)
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
    AccountError.SMS_TOO_FREQUENT -> HttpStatusCode.TooManyRequests
    AccountError.SMS_PROVIDER_UNCONFIGURED,
    AccountError.SMS_SEND_FAILED,
    AccountError.WECHAT_NOT_CONFIGURED,
    AccountError.WECHAT_SERVICE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
    AccountError.PHONE_ALREADY_REGISTERED,
    AccountError.WECHAT_ALREADY_LINKED,
    AccountError.PHONE_ALREADY_LINKED,
    AccountError.MERGE_BLOCKED,
    AccountError.LAST_LOGIN_METHOD_CANNOT_UNLINK -> HttpStatusCode.Conflict
    AccountError.PHONE_NOT_REGISTERED -> HttpStatusCode.NotFound
    AccountError.ACCOUNT_DELETION_PENDING,
    AccountError.ACCOUNT_DELETION_NOT_PENDING -> HttpStatusCode.Conflict
    AccountError.INVALID_REQUEST,
    AccountError.VERIFICATION_CODE_WRONG,
    AccountError.VERIFICATION_CODE_EXPIRED,
    AccountError.WECHAT_AUTH_FAILED,
    AccountError.TICKET_EXPIRED,
    AccountError.TICKET_ALREADY_USED -> HttpStatusCode.BadRequest
}
