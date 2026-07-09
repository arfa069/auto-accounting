package com.autoaccounting.backend.config

import com.autoaccounting.backend.account.AccountError
import com.autoaccounting.backend.account.AccountResult
import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.AccountToken
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.cloudConfigRoutes(
    cloudConfigService: CloudConfigService,
    accountService: AccountService
) {
    post("/account/cloud-config/read") {
        val parameters = call.receiveParameters()
        val verified = accountService.verifyToken(parameters["token"].orEmpty())
        if (verified is AccountResult.Failure) {
            call.respondText(
                text = """{"ok":false,"error":"${verified.error.name}","message":"${verified.error.message}"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.Unauthorized
            )
            return@post
        }
        val phone = (verified as AccountResult.Success<AccountToken>).value.phone
        val config = cloudConfigService.readConfig(phone)
        call.respondText(
            text = buildString {
                append("""{"ok":true""")
                append(""","aiConsentGranted":${config.aiConsentGranted}""")
                append(""","enhancedContextGranted":${config.enhancedContextGranted}""")
                append(""","featureFlags":${config.featureFlags}""")
                append("}")
            },
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.OK
        )
    }

    post("/account/cloud-config/write") {
        val parameters = call.receiveParameters()
        val verified = accountService.verifyToken(parameters["token"].orEmpty())
        if (verified is AccountResult.Failure) {
            call.respondText(
                text = """{"ok":false,"error":"${verified.error.name}","message":"${verified.error.message}"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.Unauthorized
            )
            return@post
        }
        val phone = (verified as AccountResult.Success<AccountToken>).value.phone
        val result = cloudConfigService.writeConfig(
            StoredCloudConfig(
                phone = phone,
                aiConsentGranted = parameters["aiConsentGranted"].toBoolean(),
                enhancedContextGranted = parameters["enhancedContextGranted"].toBoolean(),
                featureFlags = parameters["featureFlags"]?.takeIf { it.isNotBlank() } ?: "{}",
                updatedAtMillis = System.currentTimeMillis()
            )
        )
        when (result) {
            is CloudConfigResult.Written -> call.respondText(
                text = """{"ok":true}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK
            )
            is CloudConfigResult.DeletionPending -> call.respondText(
                text = """{"ok":false,"error":"${AccountError.ACCOUNT_DELETION_PENDING.name}","message":"${AccountError.ACCOUNT_DELETION_PENDING.message}"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.Conflict
            )
        }
    }
}
