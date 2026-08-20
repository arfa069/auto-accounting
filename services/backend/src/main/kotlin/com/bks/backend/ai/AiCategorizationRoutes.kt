package com.bks.backend.ai

import com.bks.api.AiCategorizationErrorContract
import com.bks.api.AiCategorizationRequestContract
import com.bks.api.AiCategorizationResponseContract
import com.bks.api.ApiJsonContracts
import com.bks.backend.account.AccountError
import com.bks.backend.account.AccountResult
import com.bks.backend.account.AccountService
import com.bks.backend.account.accountBearerToken
import com.bks.backend.account.respondAccountFailure
import com.bks.backend.receiveParameters
import com.bks.backend.config.CloudConfigService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException

fun Route.aiCategorizationRoutes(
    aiCategorizationService: AiCategorizationService,
    accountService: AccountService,
    cloudConfigService: CloudConfigService
) {
    post("/ai/categorize") {
        val account = when (val verified = accountService.verifyToken(call.accountBearerToken().orEmpty())) {
            is AccountResult.Success -> verified.value
            is AccountResult.Failure -> {
                call.respondAccountFailure(verified.error)
                return@post
            }
        }
        if (!accountService.canWriteCloudData(account.accountId)) {
            call.respondAccountFailure(AccountError.ACCOUNT_DELETION_PENDING)
            return@post
        }

        val cloudConfig = cloudConfigService.readConfig(account.accountId)
        if (!cloudConfig.aiConsentGranted) {
            call.respondAiFailure(AiCategorizationError.AI_CONSENT_REQUIRED)
            return@post
        }

        val request = try {
            call.receiveParameters().toAiCategorizationRequest()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            call.respondAiFailure(AiCategorizationError.INVALID_REQUEST)
            return@post
        }
        val suggestion = try {
            aiCategorizationService.suggest(
                accountId = account.accountId,
                request = request,
                enhancedContextAuthorized = cloudConfig.enhancedContextGranted
            )
        } catch (failure: AiCategorizationException) {
            call.respondAiFailure(failure.error)
            return@post
        }
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

private fun Parameters.toAiCategorizationRequest(): AiCategorizationRequestContract {
    val enhancedContext = when (this["enhancedContext"]?.trim()?.lowercase()) {
        null, "", "false" -> false
        "true" -> true
        else -> error("enhancedContext must be a boolean")
    }
    val rawCandidates = this["categoryCandidates"].orEmpty().trim()
    val candidates = if (rawCandidates.startsWith("[")) {
        ApiJsonContracts.parseAiCategoryCandidates(rawCandidates)
    } else {
        rawCandidates.split(',').map(String::trim).filter(String::isNotBlank)
    }
    return AiCategorizationRequestContract(
        merchantTitle = this["merchantTitle"].orEmpty(),
        sourceLabel = this["sourceLabel"].orEmpty(),
        transactionKind = this["transactionKind"].orEmpty(),
        amountRangeLabel = this["amountRangeLabel"].orEmpty(),
        categoryCandidates = candidates,
        enhancedContext = enhancedContext,
        note = this["note"],
        rawEvidenceText = this["rawEvidenceText"]
    )
}

private suspend fun ApplicationCall.respondAiFailure(error: AiCategorizationError) {
    respondText(
        text = ApiJsonContracts.encodeAiCategorizationError(
            AiCategorizationErrorContract(error = error.name, message = error.message)
        ),
        contentType = ContentType.Application.Json,
        status = error.statusCode()
    )
}

private fun AiCategorizationError.statusCode(): HttpStatusCode = when (this) {
    AiCategorizationError.INVALID_REQUEST,
    AiCategorizationError.CATEGORY_CANDIDATES_REQUIRED -> HttpStatusCode.BadRequest
    AiCategorizationError.AI_CONSENT_REQUIRED,
    AiCategorizationError.ENHANCED_CONTEXT_NOT_AUTHORIZED -> HttpStatusCode.Forbidden
    AiCategorizationError.PROVIDER_RATE_LIMITED -> HttpStatusCode.TooManyRequests
    AiCategorizationError.PROVIDER_TIMEOUT -> HttpStatusCode.GatewayTimeout
    AiCategorizationError.PROVIDER_ERROR,
    AiCategorizationError.PROVIDER_INVALID_RESPONSE -> HttpStatusCode.BadGateway
    AiCategorizationError.PROVIDER_UNAVAILABLE,
    AiCategorizationError.PROVIDER_CONFIGURATION_INVALID -> HttpStatusCode.ServiceUnavailable
}
