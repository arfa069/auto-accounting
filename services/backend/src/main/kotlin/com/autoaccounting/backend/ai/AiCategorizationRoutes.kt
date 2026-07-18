package com.autoaccounting.backend.ai

import com.autoaccounting.api.AiCategorizationResponseContract
import com.autoaccounting.api.ApiJsonContracts
import com.autoaccounting.backend.account.AccountError
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

fun Route.aiCategorizationRoutes(
    aiCategorizationService: AiCategorizationService,
    accountService: AccountService
) {
    post("/ai/categorize") {
        val parameters = call.receiveParameters()
        val account = when (val verified = accountService.verifyToken(call.accountBearerToken().orEmpty())) {
            is AccountResult.Success -> verified.value
            is AccountResult.Failure -> {
                call.respondAccountFailure(verified.error)
                return@post
            }
        }

        if (!accountService.canWriteCloudData(account.phone)) {
            call.respondAccountFailure(AccountError.ACCOUNT_DELETION_PENDING)
            return@post
        }
        val suggestion = aiCategorizationService.suggest(
            accountPhone = account.phone,
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
