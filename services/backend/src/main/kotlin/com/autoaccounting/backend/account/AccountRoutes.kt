package com.autoaccounting.backend.account

import io.ktor.http.ContentType
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
        val result = accountService.issueSmsCode(
            phone = parameters["phone"].orEmpty(),
            deviceId = parameters["deviceId"].orEmpty(),
            ipAddress = parameters["ipAddress"] ?: call.request.local.remoteHost
        )
        call.respondAccountResult(result)
    }

    post("/account/register") {
        val parameters = call.receiveParameters()
        val result = accountService.register(
            phone = parameters["phone"].orEmpty(),
            code = parameters["code"].orEmpty(),
            password = parameters["password"].orEmpty(),
            deviceId = parameters["deviceId"].orEmpty(),
            ipAddress = parameters["ipAddress"] ?: call.request.local.remoteHost
        )
        call.respondAccountResult(result)
    }

    post("/account/login") {
        val parameters = call.receiveParameters()
        val result = accountService.login(
            phone = parameters["phone"].orEmpty(),
            password = parameters["password"].orEmpty(),
            deviceId = parameters["deviceId"].orEmpty(),
            ipAddress = parameters["ipAddress"] ?: call.request.local.remoteHost
        )
        call.respondAccountResult(result)
    }

    post("/account/recover") {
        val parameters = call.receiveParameters()
        val result = accountService.recoverPassword(
            phone = parameters["phone"].orEmpty(),
            code = parameters["code"].orEmpty(),
            newPassword = parameters["password"].orEmpty(),
            deviceId = parameters["deviceId"].orEmpty(),
            ipAddress = parameters["ipAddress"] ?: call.request.local.remoteHost
        )
        call.respondAccountResult(result)
    }

    post("/account/token/verify") {
        val parameters = call.receiveParameters()
        val result = accountService.verifyToken(
            token = parameters["token"].orEmpty()
        )
        call.respondAccountResult(result)
    }

    post("/account/delete/request") {
        val parameters = call.receiveParameters()
        val result = accountService.requestAccountDeletion(
            phone = parameters["phone"].orEmpty()
        )
        call.respondAccountResult(result)
    }

    post("/account/delete/cancel") {
        val parameters = call.receiveParameters()
        val result = accountService.cancelAccountDeletion(
            phone = parameters["phone"].orEmpty()
        )
        call.respondAccountResult(result)
    }

    post("/account/delete/status") {
        val parameters = call.receiveParameters()
        val result = accountService.getAccountDeletionStatus(
            phone = parameters["phone"].orEmpty()
        )
        call.respondAccountResult(result)
    }

    post("/account/cloud-config") {
        val parameters = call.receiveParameters()
        val result = when (val verified = accountService.verifyToken(parameters["token"].orEmpty())) {
            is AccountResult.Failure -> verified
            is AccountResult.Success -> accountService.writeCloudConfiguration(verified.value.phone)
        }
        call.respondAccountResult(result)
    }
}

private suspend fun ApplicationCall.respondAccountResult(result: AccountResult<*>) {
    when (result) {
        is AccountResult.Success<*> -> {
            val value = result.value
            respondText(
                text = when (value) {
                    is AccountToken -> """{"ok":true,"phone":"${value.phone}","token":"${value.token}"}"""
                    is AccountDeletionStatus -> """{"ok":true,"phone":"${value.phone}","requestedAtMillis":${value.requestedAtMillis},"finalDeletionAtMillis":${value.finalDeletionAtMillis}}"""
                    else -> """{"ok":true}"""
                },
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK
            )
        }
        is AccountResult.Failure -> respondText(
            text = """{"ok":false,"error":"${result.error.name}","message":"${result.error.message}"}""",
            contentType = ContentType.Application.Json,
            status = result.error.statusCode()
        )
    }
}

private fun AccountError.statusCode(): HttpStatusCode = when (this) {
    AccountError.TOKEN_INVALID -> HttpStatusCode.Unauthorized
    AccountError.LOGIN_FAILED -> HttpStatusCode.Unauthorized
    AccountError.ACCOUNT_LOCKED -> HttpStatusCode.TooManyRequests
    AccountError.SMS_TOO_FREQUENT -> HttpStatusCode.TooManyRequests
    AccountError.SMS_PROVIDER_UNCONFIGURED,
    AccountError.SMS_SEND_FAILED -> HttpStatusCode.ServiceUnavailable
    AccountError.PHONE_ALREADY_REGISTERED -> HttpStatusCode.Conflict
    AccountError.PHONE_NOT_REGISTERED -> HttpStatusCode.NotFound
    AccountError.ACCOUNT_DELETION_PENDING -> HttpStatusCode.Conflict
    AccountError.ACCOUNT_DELETION_NOT_PENDING -> HttpStatusCode.Conflict
    AccountError.VERIFICATION_CODE_WRONG,
    AccountError.VERIFICATION_CODE_EXPIRED -> HttpStatusCode.BadRequest
}
