package com.autoaccounting.backend.config

import com.autoaccounting.api.ApiJsonContracts
import com.autoaccounting.api.CloudConfigContract
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
            text = ApiJsonContracts.encodeCloudConfigResponse(
                CloudConfigContract(
                    ok = true,
                    aiConsentGranted = config.aiConsentGranted,
                    enhancedContextGranted = config.enhancedContextGranted,
                    featureFlags = config.featureFlags
                )
            ),
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
        val update = try {
            parameters.toCloudConfigUpdate()
        } catch (error: IllegalArgumentException) {
            call.respondText(
                text = """{"ok":false,"error":"INVALID_REQUEST","message":"${error.message.orEmpty()}"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.BadRequest
            )
            return@post
        }
        val result = cloudConfigService.mergeAndWriteConfig(
            phone = phone,
            update = update,
            now = System.currentTimeMillis()
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

private fun io.ktor.http.Parameters.toCloudConfigUpdate(): CloudConfigUpdate {
    return CloudConfigUpdate(
        aiConsentGranted = parseOptionalBoolean("aiConsentGranted"),
        enhancedContextGranted = parseOptionalBoolean("enhancedContextGranted"),
        featureFlags = this["featureFlags"]
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                try {
                    ApiJsonContracts.parseFeatureFlags(raw)
                } catch (_: RuntimeException) {
                    throw IllegalArgumentException(
                        "featureFlags must be a JSON object with boolean values."
                    )
                }
            }
    )
}

private fun io.ktor.http.Parameters.parseOptionalBoolean(name: String): Boolean? {
    val value = this[name]?.trim()?.lowercase() ?: return null
    return when (value) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("$name must be true or false.")
    }
}
