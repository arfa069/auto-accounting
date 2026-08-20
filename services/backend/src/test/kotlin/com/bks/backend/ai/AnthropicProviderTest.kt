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

class AnthropicProviderTest {
    private val ephemeralApiKey = "test-${UUID.randomUUID()}"

    @Test
    fun messagesRequestUsesAnthropicProtocolAndParsesSuggestion() = withFakeServer { server ->
        val captured = AtomicReference<CapturedRequest>()
        server.createContext("/anthropic/v1/messages") { exchange ->
            captured.set(exchange.capture())
            exchange.respond(200, messageResponse("餐饮", "高", "午餐场景"))
        }

        val result = runBlocking {
            provider(server).suggest(samplePayload())
        }

        val request = captured.get()
        val root = PROVIDER_JSON.parseToJsonElement(request.body).jsonObject
        assertEquals("/anthropic/v1/messages", request.path)
        assertEquals(ephemeralApiKey, request.apiKey)
        assertEquals("2023-06-01", request.apiVersion)
        assertEquals("deepseek-v4-flash", root.getValue("model").jsonPrimitive.content)
        assertEquals(
            "json_schema",
            root.getValue("output_config")
                .jsonObject
                .getValue("format")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content
        )
        val categorySchema = root.getValue("output_config")
            .jsonObject
            .getValue("format")
            .jsonObject
            .getValue("schema")
            .jsonObject
            .getValue("properties")
            .jsonObject
            .getValue("category")
            .jsonObject
        assertFalse(categorySchema.containsKey("enum"))
        assertEquals(1, root.getValue("messages").jsonArray.size)
        assertEquals("餐饮", result.category)
        assertEquals("午餐场景", result.explanation)
    }

    @Test
    fun truncatedOrUnexpectedStructuredResponseIsRejected() = withFakeServer { server ->
        val response = AtomicReference(
            messageResponse("餐饮", "中", "不完整", stopReason = "max_tokens")
        )
        server.createContext("/anthropic/v1/messages") { exchange ->
            exchange.capture()
            exchange.respond(200, response.get())
        }
        val provider = provider(server)

        val truncated = runBlocking {
            runCatching { provider.suggest(samplePayload()) }.exceptionOrNull()
        }
        response.set(
            messageResponseWithText(
                """{"category":"餐饮","confidence":"中","explanation":"ok","extra":true}"""
            )
        )
        val unexpected = runBlocking {
            runCatching { provider.suggest(samplePayload()) }.exceptionOrNull()
        }

        assertTrue(truncated === AiProviderException.InvalidResponse)
        assertTrue(unexpected === AiProviderException.InvalidResponse)
    }

    @Test
    fun upstreamFailureIsMappedWithoutLeakingResponseBody() = withFakeServer { server ->
        server.createContext("/anthropic/v1/messages") { exchange ->
            exchange.capture()
            exchange.respond(500, """{"error":{"message":"provider failure"}}""")
        }

        val failure = runBlocking {
            runCatching { provider(server).suggest(samplePayload()) }.exceptionOrNull()
        }

        assertTrue(failure === AiProviderException.UpstreamFailure)
    }

    private fun provider(server: HttpServer): AiProvider =
        AnthropicProvider.fromEnvironment(
            mapOf(
                "BKS_AI_API_KEY" to ephemeralApiKey,
                "BKS_AI_MODEL" to "deepseek-v4-flash",
                "BKS_AI_ENDPOINT" to
                    "http://127.0.0.1:${server.address.port}/anthropic/v1/messages",
                "BKS_AI_AUTH_STYLE" to "x-api-key",
                "BKS_AI_OUTPUT_MODE" to "json-schema",
                "BKS_AI_API_VERSION" to "2023-06-01",
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

    private fun messageResponse(
        category: String,
        confidence: String,
        explanation: String,
        stopReason: String = "end_turn"
    ): String = messageResponseWithText(
        text = """{"category":"$category","confidence":"$confidence","explanation":"$explanation"}""",
        stopReason = stopReason
    )

    private fun messageResponseWithText(
        text: String,
        stopReason: String = "end_turn"
    ): String = buildJsonObject {
        put("type", "message")
        put("role", "assistant")
        put("stop_reason", stopReason)
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
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
            apiKey = requestHeaders.getFirst("x-api-key"),
            apiVersion = requestHeaders.getFirst("anthropic-version"),
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
        val apiKey: String?,
        val apiVersion: String?,
        val body: String
    )
}
