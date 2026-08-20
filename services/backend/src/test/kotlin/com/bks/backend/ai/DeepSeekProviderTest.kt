package com.bks.backend.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekProviderTest {
    private val ephemeralApiKey = "test-${UUID.randomUUID()}"

    @Test
    fun chatCompletionsRequestUsesDeepSeekProtocolAndParsesSuggestion() = withFakeServer { server ->
        val captured = AtomicReference<CapturedRequest>()
        server.createContext("/chat/completions") { exchange ->
            captured.set(exchange.capture())
            exchange.respond(200, chatCompletion("餐饮", "高", "午餐场景"))
        }

        val result = runBlocking {
            provider(server).suggest(samplePayload())
        }

        val request = captured.get()
        val root = PROVIDER_JSON.parseToJsonElement(request.body).jsonObject
        assertEquals("/chat/completions", request.path)
        assertEquals("Bearer $ephemeralApiKey", request.authorization)
        assertEquals("deepseek-v4-flash", root.getValue("model").jsonPrimitive.content)
        assertEquals(
            "disabled",
            root.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content
        )
        assertEquals(
            "json_object",
            root.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content
        )
        val userPayload = root.getValue("messages")
            .jsonArray[1]
            .jsonObject
            .getValue("content")
            .jsonPrimitive
            .content
        assertFalse(userPayload.contains("amountMinor"))
        assertFalse(userPayload.contains("rawEvidenceText"))
        assertEquals("餐饮", result.category)
        assertEquals("高", result.confidenceLabel)
    }

    @Test
    fun nonStopOrOutOfCandidateResponseIsRejected() = withFakeServer { server ->
        val response = AtomicReference(chatCompletion("餐饮", "中", "正常", finishReason = "length"))
        server.createContext("/chat/completions") { exchange ->
            exchange.capture()
            exchange.respond(200, response.get())
        }
        val provider = provider(server)

        val truncated = runBlocking {
            runCatching { provider.suggest(samplePayload()) }.exceptionOrNull()
        }
        response.set(chatCompletion("购物", "中", "不在候选中"))
        val outOfCandidate = runBlocking {
            runCatching { provider.suggest(samplePayload()) }.exceptionOrNull()
        }

        assertTrue(truncated === AiProviderException.InvalidResponse)
        assertTrue(outOfCandidate === AiProviderException.InvalidResponse)
    }

    @Test
    fun rateLimitIsMappedWithoutParsingBody() = withFakeServer { server ->
        server.createContext("/chat/completions") { exchange ->
            exchange.capture()
            exchange.respond(429, """{"error":{"message":"limited"}}""")
        }

        val failure = runBlocking {
            runCatching { provider(server).suggest(samplePayload()) }.exceptionOrNull()
        }

        assertTrue(failure === AiProviderException.RateLimited)
    }

    private fun provider(server: HttpServer): AiProvider =
        DeepSeekProvider.fromEnvironment(
            mapOf(
                "BKS_AI_API_KEY" to ephemeralApiKey,
                "BKS_AI_MODEL" to "deepseek-v4-flash",
                "BKS_AI_ENDPOINT" to
                    "http://127.0.0.1:${server.address.port}/chat/completions",
                "BKS_AI_AUTH_STYLE" to "bearer",
                "BKS_AI_OUTPUT_MODE" to "json-object",
                "BKS_AI_REASONING_MODE" to "disabled",
                "BKS_AI_CONNECT_TIMEOUT_MILLIS" to "1000",
                "BKS_AI_READ_TIMEOUT_MILLIS" to "2000"
            )
        )

    private fun samplePayload() = AiCategorizationPayload(
        merchantTitle = "午餐",
        sourceLabel = "微信",
        transactionKind = "支出",
        amountRangeLabel = "0-50",
        categoryCandidates = listOf("餐饮", "交通")
    )

    private fun chatCompletion(
        category: String,
        confidence: String,
        explanation: String,
        finishReason: String = "stop"
    ): String = buildJsonObject {
        put("object", "chat.completion")
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("finish_reason", finishReason)
                put("message", buildJsonObject {
                    put("role", "assistant")
                    put(
                        "content",
                        """{"category":"$category","confidence":"$confidence","explanation":"$explanation"}"""
                    )
                })
            })
        })
    }.toString()

    private fun withFakeServer(block: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.start()
            block(server)
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.capture(): CapturedRequest {
        val body = requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        return CapturedRequest(
            path = requestURI.path,
            authorization = requestHeaders.getFirst("Authorization"),
            body = body
        )
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private data class CapturedRequest(
        val path: String,
        val authorization: String?,
        val body: String
    )
}
