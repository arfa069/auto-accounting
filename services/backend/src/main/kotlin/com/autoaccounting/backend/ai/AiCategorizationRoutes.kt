package com.autoaccounting.backend.ai

import com.autoaccounting.api.AiCategorizationResponseContract
import com.autoaccounting.api.ApiJsonContracts
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

fun Route.aiCategorizationRoutes(
    aiCategorizationService: AiCategorizationService,
    accountService: AccountService? = null
) {
    post("/ai/categorize") {
        val parameters = call.receiveParameters()
        val accountPhone = parameters["accountPhone"]?.takeIf { it.isNotBlank() }
        val token = parameters["token"]?.takeIf { it.isNotBlank() }
        val resolvedPhone = if (token != null) {
            when (val verified = accountService?.verifyToken(token)) {
                is AccountResult.Success -> verified.value.phone
                else -> null
            }
        } else {
            null
        }
        val finalPhone = accountPhone ?: resolvedPhone

        if (finalPhone != null && accountService?.canWriteCloudData(finalPhone) == false) {
            call.respondText(
                text = """{"ok":false,"error":"${AccountError.ACCOUNT_DELETION_PENDING.name}","message":"${AccountError.ACCOUNT_DELETION_PENDING.message}"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.Conflict
            )
            return@post
        }
        val suggestion = aiCategorizationService.suggest(
            accountPhone = finalPhone,
            merchantTitle = parameters["merchantTitle"].orEmpty(),
            sourceLabel = parameters["sourceLabel"].orEmpty(),
            transactionKind = parameters["transactionKind"].orEmpty(),
            amountMinor = parameters["amountMinor"]?.toLongOrNull() ?: 0L,
            categoryCandidates = parameters["categoryCandidates"].orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() },
            note = parameters["note"],
            rawEvidenceText = parameters["rawEvidenceText"],
            enhancedContext = parameters["enhancedContext"].toBoolean()
        )
        call.respondText(
            text = ApiJsonContracts.encodeAiCategorizationResponse(
                AiCategorizationResponseContract(
                    ok = true,
                    category = suggestion.category,
                    confidence = suggestion.confidenceLabel,
                    explanation = suggestion.explanation
                )
            ),
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.OK
        )
    }
}
