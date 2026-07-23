package com.autoaccounting.backend.config

import com.autoaccounting.api.ApiJsonContracts
import com.autoaccounting.api.CloudConfigContract
import com.autoaccounting.backend.account.AccountResult
import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.accountBearerToken
import com.autoaccounting.backend.account.respondAccountFailure
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
        val account = when (val verified = accountService.verifyToken(call.accountBearerToken().orEmpty())) {
            is AccountResult.Success -> verified.value
            is AccountResult.Failure -> {
                call.respondAccountFailure(verified.error)
                return@post
            }
        }
        val config = cloudConfigService.readConfig(account.accountId)
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
        val account = when (val verified = accountService.verifyToken(call.accountBearerToken().orEmpty())) {
            is AccountResult.Success -> verified.value
            is AccountResult.Failure -> {
                call.respondAccountFailure(verified.error)
                return@post
            }
        }
        val update = try {
            parameters.toCloudConfigUpdate()
        } catch (_: IllegalArgumentException) {
            call.respondAccountFailure(com.autoaccounting.backend.account.AccountError.INVALID_REQUEST)
            return@post
        }
        val result = cloudConfigService.mergeAndWriteConfig(
            accountId = account.accountId,
            update = update,
            now = System.currentTimeMillis()
        )
        when (result) {
            is CloudConfigResult.Written -> call.respondText(
                text = """{"ok":true}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK
            )
            is CloudConfigResult.DeletionPending -> call.respondAccountFailure(
                com.autoaccounting.backend.account.AccountError.ACCOUNT_DELETION_PENDING
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
