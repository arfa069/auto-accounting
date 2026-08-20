package com.bks.feature.categorization

import com.bks.api.AiCategorizationRequestContract
import com.bks.api.ApiJsonContracts
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class AiHttpResponse(
    val statusCode: Int,
    val body: String
)

internal interface AiHttpTransport {
    suspend fun post(
        url: String,
        form: Map<String, String>,
        bearerToken: String
    ): AiHttpResponse
}

internal class HttpUrlConnectionAiTransport(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AiHttpTransport {
    override suspend fun post(
        url: String,
        form: Map<String, String>,
        bearerToken: String
    ): AiHttpResponse = withContext(ioDispatcher) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8"
                )
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
                val requestBody = form.entries.joinToString("&") { (name, value) ->
                    "${name.formEncode()}=${value.formEncode()}"
                }.toByteArray(Charsets.UTF_8)
                connection.outputStream.use { output -> output.write(requestBody) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (continuation.isActive) {
                    continuation.resume(AiHttpResponse(statusCode = status, body = body))
                }
            } catch (cause: Exception) {
                if (continuation.isActive) continuation.resumeWithException(cause)
            } finally {
                connection.disconnect()
            }
        }
    }
}

private const val MAX_EXPLANATION_LENGTH = 240
private const val MAX_RESPONSE_BYTES = 64 * 1024

class HttpAiCategorizationGateway internal constructor(
    backendUrl: String,
    private val transport: AiHttpTransport = HttpUrlConnectionAiTransport(),
    allowHttp: Boolean = false
) : AiCategorizationGateway {
    private val endpointUrl = backendUrl.toBackendEndpointOrNull(
        path = "/ai/categorize",
        allowHttp = allowHttp
    )

    override suspend fun suggestCategory(
        token: String,
        payload: AiCategorizationPayload
    ): AiCategorizationGatewayResult {
        val url = endpointUrl ?: return failure(AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED)
        if (token.isBlank()) return failure(AiCategorizationFailureReason.INVALID_SESSION)
        if (payload.categoryCandidates.none(String::isNotBlank)) {
            return failure(AiCategorizationFailureReason.CATEGORY_CANDIDATES_REQUIRED)
        }
        val contract = payload.toRequestContract()
        val response = try {
            transport.post(url, contract.toForm(), token)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            return failure(AiCategorizationFailureReason.NETWORK_FAILURE)
        } catch (_: RuntimeException) {
            return failure(AiCategorizationFailureReason.NETWORK_FAILURE)
        }

        if (response.statusCode !in 200..299) {
            return mapError(response)
        }
        if (response.body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            return failure(AiCategorizationFailureReason.INVALID_RESPONSE)
        }
        return try {
            val parsed = ApiJsonContracts.parseAiCategorizationResponse(response.body)
            require(parsed.ok)
            require(parsed.category in payload.categoryCandidates)
            require(parsed.confidence in setOf("低", "中", "高"))
            require(parsed.explanation.isNotBlank())
            require(parsed.explanation.length <= MAX_EXPLANATION_LENGTH)
            AiCategorizationGatewayResult.Success(
                AiCategorizationResponse(
                    category = parsed.category,
                    confidenceLabel = parsed.confidence,
                    explanation = parsed.explanation
                )
            )
        } catch (_: RuntimeException) {
            failure(AiCategorizationFailureReason.INVALID_RESPONSE)
        }
    }

    private fun mapError(response: AiHttpResponse): AiCategorizationGatewayResult.Failure {
        val errorCode = runCatching {
            ApiJsonContracts.parseAiCategorizationError(response.body).error
        }.getOrNull()
        val reason = when {
            response.statusCode == 401 || errorCode == "TOKEN_INVALID" ->
                AiCategorizationFailureReason.INVALID_SESSION
            response.statusCode == 409 || errorCode == "ACCOUNT_DELETION_PENDING" ->
                AiCategorizationFailureReason.ACCOUNT_DELETION_PENDING
            errorCode == "AI_CONSENT_REQUIRED" ->
                AiCategorizationFailureReason.AI_CONSENT_REQUIRED
            errorCode == "ENHANCED_CONTEXT_NOT_AUTHORIZED" ->
                AiCategorizationFailureReason.ENHANCED_CONTEXT_NOT_AUTHORIZED
            errorCode == "CATEGORY_CANDIDATES_REQUIRED" ->
                AiCategorizationFailureReason.CATEGORY_CANDIDATES_REQUIRED
            response.statusCode == 429 || errorCode == "PROVIDER_RATE_LIMITED" ->
                AiCategorizationFailureReason.RATE_LIMITED
            response.statusCode >= 500 ->
                AiCategorizationFailureReason.SERVICE_UNAVAILABLE
            else -> AiCategorizationFailureReason.INVALID_RESPONSE
        }
        return failure(reason)
    }
}

private fun AiCategorizationPayload.toRequestContract(): AiCategorizationRequestContract {
    return AiCategorizationRequestContract(
        merchantTitle = merchantTitle,
        sourceLabel = sourceLabel,
        transactionKind = transactionKind,
        amountRangeLabel = amountRangeLabel,
        categoryCandidates = categoryCandidates,
        enhancedContext = enhancedContext,
        note = note.takeIf { enhancedContext },
        rawEvidenceText = rawEvidenceText.takeIf { enhancedContext }
    )
}

private fun AiCategorizationRequestContract.toForm(): Map<String, String> = buildMap {
    put("merchantTitle", merchantTitle)
    put("sourceLabel", sourceLabel)
    put("transactionKind", transactionKind)
    put("amountRangeLabel", amountRangeLabel)
    put("categoryCandidates", ApiJsonContracts.encodeAiCategoryCandidates(categoryCandidates))
    put("enhancedContext", enhancedContext.toString())
    if (enhancedContext) {
        note?.takeIf(String::isNotBlank)?.let { put("note", it) }
        rawEvidenceText?.takeIf(String::isNotBlank)?.let { put("rawEvidenceText", it) }
    }
}

private fun String.formEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

private fun failure(reason: AiCategorizationFailureReason) =
    AiCategorizationGatewayResult.Failure(reason)
