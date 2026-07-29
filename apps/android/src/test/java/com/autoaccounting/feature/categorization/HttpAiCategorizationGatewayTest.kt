package com.autoaccounting.feature.categorization

import com.autoaccounting.api.AiCategorizationErrorContract
import com.autoaccounting.api.AiCategorizationResponseContract
import com.autoaccounting.api.ApiJsonContracts
import java.io.IOException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpAiCategorizationGatewayTest {
    @Test
    fun postsExpectedUrlAuthorizationAndMinimalForm() = runBlocking {
        val transport = RecordingTransport(
            response = successResponse("餐饮")
        )
        val gateway = HttpAiCategorizationGateway(
            backendUrl = "https://backend.example.test/root/",
            transport = transport
        )

        val result = gateway.suggestCategory("backend-token", minimalPayload())

        assertTrue(result is AiCategorizationGatewayResult.Success)
        val request = transport.requests.single()
        assertEquals("https://backend.example.test/root/ai/categorize", request.url)
        assertEquals("backend-token", request.bearerToken)
        assertEquals("0-50", request.form.getValue("amountRangeLabel"))
        assertEquals(
            listOf("餐饮", "交通"),
            ApiJsonContracts.parseAiCategoryCandidates(request.form.getValue("categoryCandidates"))
        )
        assertEquals("false", request.form.getValue("enhancedContext"))
        assertFalse(request.form.containsKey("amountMinor"))
        assertFalse(request.form.containsKey("note"))
        assertFalse(request.form.containsKey("rawEvidenceText"))
        assertFalse(request.form.values.any { it == "3590" })
    }

    @Test
    fun enhancedContextAddsOnlyAuthorizedOptionalFields() = runBlocking {
        val transport = RecordingTransport(successResponse("餐饮"))
        val gateway = HttpAiCategorizationGateway("https://backend.example.test", transport)

        gateway.suggestCategory(
            "backend-token",
            minimalPayload().copy(
                enhancedContext = true,
                note = "同事聚餐",
                rawEvidenceText = "付款通知"
            )
        )

        val form = transport.requests.single().form
        assertEquals("true", form.getValue("enhancedContext"))
        assertEquals("同事聚餐", form.getValue("note"))
        assertEquals("付款通知", form.getValue("rawEvidenceText"))
    }

    @Test
    fun blankOrInvalidBackendUrlFailsWithoutTransport() = runBlocking {
        val transport = RecordingTransport(successResponse("餐饮"))

        val blank = HttpAiCategorizationGateway(" ", transport)
            .suggestCategory("token", minimalPayload())
        val invalid = HttpAiCategorizationGateway("file:///tmp/backend", transport)
            .suggestCategory("token", minimalPayload())
        val credentialed = HttpAiCategorizationGateway(
            "https://user:password@backend.example.test?redirect=other",
            transport
        ).suggestCategory("token", minimalPayload())

        assertFailure(blank, AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED)
        assertFailure(invalid, AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED)
        assertFailure(credentialed, AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun cleartextRequiresExplicitPrivateTestOptIn() = runBlocking {
        val blockedTransport = RecordingTransport(successResponse("餐饮"))
        val blocked = HttpAiCategorizationGateway(
            backendUrl = "http://10.0.2.2:8080",
            transport = blockedTransport
        ).suggestCategory("token", minimalPayload())
        val publicHttp = HttpAiCategorizationGateway(
            backendUrl = "http://example.com",
            transport = blockedTransport,
            allowHttp = true
        ).suggestCategory("token", minimalPayload())
        val allowedTransport = RecordingTransport(successResponse("餐饮"))
        val allowed = HttpAiCategorizationGateway(
            backendUrl = "http://10.0.2.2:8080",
            transport = allowedTransport,
            allowHttp = true
        ).suggestCategory("token", minimalPayload())

        assertFailure(blocked, AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED)
        assertFailure(publicHttp, AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED)
        assertTrue(blockedTransport.requests.isEmpty())
        assertTrue(allowed is AiCategorizationGatewayResult.Success)
        assertEquals("http://10.0.2.2:8080/ai/categorize", allowedTransport.requests.single().url)
    }

    @Test
    fun blankTokenAndEmptyCandidatesFailBeforeTransport() = runBlocking {
        val transport = RecordingTransport(successResponse("餐饮"))
        val gateway = HttpAiCategorizationGateway("https://backend.example.test", transport)

        val blankToken = gateway.suggestCategory(" ", minimalPayload())
        val emptyCandidates = gateway.suggestCategory(
            "token",
            minimalPayload().copy(categoryCandidates = emptyList())
        )

        assertFailure(blankToken, AiCategorizationFailureReason.INVALID_SESSION)
        assertFailure(emptyCandidates, AiCategorizationFailureReason.CATEGORY_CANDIDATES_REQUIRED)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun successResponseMustBeValidAndWhitelisted() = runBlocking {
        val success = HttpAiCategorizationGateway(
            "https://backend.example.test",
            RecordingTransport(successResponse("餐饮"))
        ).suggestCategory("token", minimalPayload())
        val invalidJson = HttpAiCategorizationGateway(
            "https://backend.example.test",
            RecordingTransport(AiHttpResponse(200, "not-json"))
        ).suggestCategory("token", minimalPayload())
        val outsideCandidates = HttpAiCategorizationGateway(
            "https://backend.example.test",
            RecordingTransport(successResponse("房租"))
        ).suggestCategory("token", minimalPayload())
        val invalidConfidence = HttpAiCategorizationGateway(
            "https://backend.example.test",
            RecordingTransport(
                AiHttpResponse(
                    200,
                    ApiJsonContracts.encodeAiCategorizationResponse(
                        AiCategorizationResponseContract(true, "餐饮", "certain", "测试建议")
                    )
                )
            )
        ).suggestCategory("token", minimalPayload())

        assertEquals("餐饮", (success as AiCategorizationGatewayResult.Success).suggestion.category)
        assertFailure(invalidJson, AiCategorizationFailureReason.INVALID_RESPONSE)
        assertFailure(outsideCandidates, AiCategorizationFailureReason.INVALID_RESPONSE)
        assertFailure(invalidConfidence, AiCategorizationFailureReason.INVALID_RESPONSE)
    }

    @Test
    fun oversizedSuccessBodyIsRejected() = runBlocking {
        val result = HttpAiCategorizationGateway(
            "https://backend.example.test",
            RecordingTransport(AiHttpResponse(200, "x".repeat(64 * 1024 + 1)))
        ).suggestCategory("token", minimalPayload())

        assertFailure(result, AiCategorizationFailureReason.INVALID_RESPONSE)
    }

    @Test
    fun stableHttpErrorsAreMapped() = runBlocking {
        val cases = listOf(
            401 to ("TOKEN_INVALID" to AiCategorizationFailureReason.INVALID_SESSION),
            409 to ("ACCOUNT_DELETION_PENDING" to AiCategorizationFailureReason.ACCOUNT_DELETION_PENDING),
            403 to ("AI_CONSENT_REQUIRED" to AiCategorizationFailureReason.AI_CONSENT_REQUIRED),
            403 to ("ENHANCED_CONTEXT_NOT_AUTHORIZED" to
                AiCategorizationFailureReason.ENHANCED_CONTEXT_NOT_AUTHORIZED),
            400 to ("CATEGORY_CANDIDATES_REQUIRED" to
                AiCategorizationFailureReason.CATEGORY_CANDIDATES_REQUIRED),
            429 to ("PROVIDER_RATE_LIMITED" to AiCategorizationFailureReason.RATE_LIMITED),
            500 to ("PROVIDER_ERROR" to AiCategorizationFailureReason.SERVICE_UNAVAILABLE),
            503 to ("PROVIDER_UNAVAILABLE" to AiCategorizationFailureReason.SERVICE_UNAVAILABLE)
        )

        cases.forEach { (status, expected) ->
            val (code, reason) = expected
            val body = ApiJsonContracts.encodeAiCategorizationError(
                AiCategorizationErrorContract(code, "safe")
            )
            val result = HttpAiCategorizationGateway(
                "https://backend.example.test",
                RecordingTransport(AiHttpResponse(status, body))
            ).suggestCategory("token", minimalPayload())
            assertFailure(result, reason)
        }
    }

    @Test
    fun networkFailureIsStable() = runBlocking {
        val gateway = HttpAiCategorizationGateway(
            "https://backend.example.test",
            object : AiHttpTransport {
                override suspend fun post(
                    url: String,
                    form: Map<String, String>,
                    bearerToken: String
                ): AiHttpResponse = throw IOException("offline payload must not escape")
            }
        )

        val result = gateway.suggestCategory("token", minimalPayload())

        assertFailure(result, AiCategorizationFailureReason.NETWORK_FAILURE)
    }

    @Test
    fun cancellationIsPropagatedToTransport() = runBlocking {
        var transportCancelled = false
        val gateway = HttpAiCategorizationGateway(
            "https://backend.example.test",
            object : AiHttpTransport {
                override suspend fun post(
                    url: String,
                    form: Map<String, String>,
                    bearerToken: String
                ): AiHttpResponse = suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { transportCancelled = true }
                }
            }
        )
        val job = launch {
            gateway.suggestCategory("token", minimalPayload())
        }
        yield()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(transportCancelled)
    }

    private fun successResponse(category: String): AiHttpResponse = AiHttpResponse(
        statusCode = 200,
        body = ApiJsonContracts.encodeAiCategorizationResponse(
            AiCategorizationResponseContract(
                ok = true,
                category = category,
                confidence = "高",
                explanation = "测试建议"
            )
        )
    )

    private fun minimalPayload(): AiCategorizationPayload = AiCategorizationPayload(
        merchantTitle = "午餐",
        sourceLabel = "微信",
        transactionKind = "支出",
        amountRangeLabel = "0-50",
        categoryCandidates = listOf("餐饮", "交通")
    )

    private fun assertFailure(
        result: AiCategorizationGatewayResult,
        expected: AiCategorizationFailureReason
    ) {
        assertEquals(expected, (result as AiCategorizationGatewayResult.Failure).reason)
    }

    private class RecordingTransport(
        private val response: AiHttpResponse
    ) : AiHttpTransport {
        val requests = mutableListOf<Request>()

        override suspend fun post(
            url: String,
            form: Map<String, String>,
            bearerToken: String
        ): AiHttpResponse {
            requests += Request(url, form, bearerToken)
            return response
        }
    }

    private data class Request(
        val url: String,
        val form: Map<String, String>,
        val bearerToken: String
    )
}
