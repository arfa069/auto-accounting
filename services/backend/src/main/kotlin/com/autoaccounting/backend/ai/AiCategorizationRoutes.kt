package com.autoaccounting.backend.ai

import com.autoaccounting.backend.account.AccountError
import com.autoaccounting.backend.account.AccountService
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
        if (accountPhone != null && accountService?.canWriteCloudData(accountPhone) == false) {
            call.respondText(
                text = """{"ok":false,"error":"${AccountError.ACCOUNT_DELETION_PENDING.name}","message":"${AccountError.ACCOUNT_DELETION_PENDING.message}"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.Conflict
            )
            return@post
        }
        val suggestion = aiCategorizationService.suggest(
            accountPhone = accountPhone,
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
            text = """{"ok":true,"category":"${suggestion.category}","confidence":"${suggestion.confidenceLabel}","explanation":"${suggestion.explanation}"}""",
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.OK
        )
    }
}
