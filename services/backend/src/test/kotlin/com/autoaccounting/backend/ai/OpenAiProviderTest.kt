package com.autoaccounting.backend.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProviderTest {
    private val ephemeralApiKey = UUID.randomUUID().toString()

    @Test
    fun responsesRequestUsesStrictSchemaAndMinimalPayload() = withFakeServer { server ->
        val captured = AtomicReference<CapturedRequest>()
        server.respond { exchange ->
            captured.set(exchange.capture())
            exchange.respond(200, completedResponse("餐饮", "高", "候选分类匹配"))
        }

        val suggestion = runBlocking {
            provider(server).suggest(samplePayload())
        }

        assertEquals("餐饮", suggestion.category)
        val request = requireNotNull(captured.get())
        assertEquals("/v1/responses", request.path)
        assertEquals("Bearer $ephemeralApiKey", request.authorization)
        val root = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("gpt-4o-mini", root.getValue("model").jsonPrimitive.content)
        assertFalse(root.getValue("store").jsonPrimitive.boolean)
        val format = root.getValue("text").jsonObject.getValue("format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        assertTrue(format.getValue("strict").jsonPrimitive.boolean)
        val categoryEnum = format.getValue("schema").jsonObject
            .getValue("properties").jsonObject
            .getValue("category").jsonObject
            .getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("餐饮", "交通"), categoryEnum)
        val userContent = root.getValue("input").jsonArray[1].jsonObject
            .getValue("content").jsonPrimitive.content
        val userPayload = Json.parseToJsonElement(userContent).jsonObject
        assertEquals("0-50", userPayload.getValue("amountRangeLabel").jsonPrimitive.content)
        assertFalse(userPayload.containsKey("amountMinor"))
        assertFalse(userPayload.containsKey("note"))
        assertFalse(userPayload.containsKey("rawEvidenceText"))
        assertFalse(request.body.contains("backend-token"))
    }

    @Test
    fun enhancedContextIsIncludedOnlyWhenPresentInValidatedPayload() = withFakeServer { server ->
        val capturedBody = AtomicReference<String>()
        server.respond { exchange ->
            capturedBody.set(exchange.capture().body)
            exchange.respond(200, completedResponse("餐饮", "中", "测试建议"))
        }

        runBlocking {
            provider(server).suggest(
                samplePayload().copy(
                    note = "同事聚餐",
                    rawEvidenceText = "付款通知"
                )
            )
        }

        val root = Json.parseToJsonElement(requireNotNull(capturedBody.get())).jsonObject
        val userContent = root.getValue("input").jsonArray[1].jsonObject
            .getValue("content").jsonPrimitive.content
        val userPayload = Json.parseToJsonElement(userContent).jsonObject
        assertEquals("同事聚餐", userPayload.getValue("note").jsonPrimitive.content)
        assertEquals("付款通知", userPayload.getValue("rawEvidenceText").jsonPrimitive.content)
    }

    @Test
    fun emptyCandidatesAreRejectedBeforeHttpCall() = withFakeServer { server ->
        val called = AtomicReference(false)
        server.respond { exchange ->
            called.set(true)
            exchange.respond(200, completedResponse("餐饮", "高", "不应调用"))
        }

        val failure = runBlocking {
            runCatching {
                provider(server).suggest(samplePayload().copy(categoryCandidates = emptyList()))
            }.exceptionOrNull()
        }

        assertTrue(failure === AiProviderException.InvalidResponse)
        assertFalse(called.get())
    }

    @Test
    fun oversizedAndWrongTypedStructuredResponsesAreRejected() = withFakeServer { server ->
        val body = AtomicReference("x".repeat(64 * 1024 + 1))
        server.respond { exchange ->
            exchange.capture()
            exchange.respond(200, body.get())
        }
        val provider = provider(server)

        val oversized = runBlocking {
            runCatching { provider.suggest(samplePayload()) }.exceptionOrNull()
        }
        val structured =
            """{"category":"餐饮","confidence":"高","explanation":123}""".replace("\"", "\\\"")
        body.set(
            """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"$structured"}]}]}"""
        )
        val wrongType = runBlocking {
            runCatching { provider.suggest(samplePayload()) }.exceptionOrNull()
        }

        assertTrue(oversized === AiProviderException.InvalidResponse)
        assertTrue(wrongType === AiProviderException.InvalidResponse)
    }

    @Test
    fun rateLimitAndServerFailuresAreMappedWithoutUpstreamBody() = withFakeServer { server ->
        val responseCode = AtomicReference(429)
        server.respond { exchange ->
            exchange.capture()
            exchange.respond(responseCode.get(), "upstream-secret-body")
        }
        val provider = provider(server)

        val rateFailure = runBlocking {
            runCatching { provider.suggest(samplePayload()) }.exceptionOrNull()
        }
        responseCode.set(500)
        val serverFailure = runBlocking {
            runCatching { provider.suggest(samplePayload()) }.exceptionOrNull()
        }

        assertTrue(rateFailure === AiProviderException.RateLimited)
        assertTrue(serverFailure === AiProviderException.UpstreamFailure)
        assertFalse(rateFailure.toString().contains("upstream-secret-body"))
        assertFalse(serverFailure.toString().contains("upstream-secret-body"))
    }


    @Test
    fun responseCategoryMustRemainInsideCandidates() = withFakeServer { server ->
        server.respond { exchange ->
            exchange.capture()
            exchange.respond(200, completedResponse("房租", "高", "越界建议"))
        }

        val failure = runBlocking {
            runCatching { provider(server).suggest(samplePayload()) }.exceptionOrNull()
        }

        assertTrue(failure === AiProviderException.InvalidResponse)
    }

    @Test
    fun malformedAndNonCompletedResponsesAreRejected() = withFakeServer { server ->
        val body = AtomicReference("not-json")
        server.respond { exchange ->
            exchange.capture()
            exchange.respond(200, body.get())
        }
        val provider = provider(server)

        val malformed = runBlocking {
            runCatching { provider.suggest(samplePayload()) }.exceptionOrNull()
        }
        body.set("""{"status":"incomplete","output":[]}""")
        val incomplete = runBlocking {
            runCatching { provider.suggest(samplePayload()) }.exceptionOrNull()
        }

        assertTrue(malformed === AiProviderException.InvalidResponse)
        assertTrue(incomplete === AiProviderException.InvalidResponse)
    }

    @Test
    fun structuredOutputWithUnexpectedFieldsIsRejected() = withFakeServer { server ->
        server.respond { exchange ->
            exchange.capture()
            val structured = """{"category":"餐饮","confidence":"高","explanation":"ok","extra":true}"""
                .replace("\"", "\\\"")
            exchange.respond(
                200,
                """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"$structured"}]}]}"""
            )
        }

        val failure = runBlocking {
            runCatching { provider(server).suggest(samplePayload()) }.exceptionOrNull()
        }

        assertTrue(failure === AiProviderException.InvalidResponse)
    }

    @Test
    fun jdkTransportKeepsTimeoutAndIoFailureMappings() = runBlocking {
        val timeoutTransport = JdkProviderHttpTransport(
            ioDispatcher = Dispatchers.IO,
            sendRequest = { throw HttpTimeoutException("timeout") }
        )
        val timeoutFailure = runCatching {
            timeoutTransport.post(
                uri = URI("https://api.openai.com/v1/responses"),
                headers = mapOf("Authorization" to "Bearer test-key"),
                body = "{}",
                requestTimeout = Duration.ofSeconds(1)
            )
        }.exceptionOrNull()

        val ioTransport = JdkProviderHttpTransport(
            ioDispatcher = Dispatchers.IO,
            sendRequest = { throw IOException("network") }
        )
        val ioFailure = runCatching {
            ioTransport.post(
                uri = URI("https://api.openai.com/v1/responses"),
                headers = mapOf("Authorization" to "Bearer test-key"),
                body = "{}",
                requestTimeout = Duration.ofSeconds(1)
            )
        }.exceptionOrNull()

        assertTrue(timeoutFailure === AiProviderException.TimedOut)
        assertTrue(ioFailure === AiProviderException.UpstreamFailure)
    }

    @Test
    fun cancellingCoroutineInterruptsBlockingJdkSend() = runBlocking {
        val entered = CountDownLatch(1)
        val blocker = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val transport = JdkProviderHttpTransport(
            ioDispatcher = Dispatchers.IO,
            sendRequest = {
                entered.countDown()
                try {
                    blocker.await()
                    error("blocking sender should have been interrupted")
                } catch (failure: InterruptedException) {
                    interrupted.set(true)
                    throw failure
                }
            }
        )

        val requestJob = launch(start = CoroutineStart.UNDISPATCHED) {
            transport.post(
                uri = URI("https://api.openai.com/v1/responses"),
                headers = mapOf("Authorization" to "Bearer test-key"),
                body = "{}",
                requestTimeout = Duration.ofSeconds(30)
            )
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            withTimeout(2_000) {
                requestJob.cancelAndJoin()
            }

            assertTrue(requestJob.isCancelled)
            assertTrue(interrupted.get())
        } finally {
            blocker.countDown()
        }
    }

    @Test
    fun requestTimeoutIsMappedWithoutRealProviderCall() = withFakeServer { server ->
        val entered = CountDownLatch(1)
        server.respond { exchange ->
            exchange.capture()
            entered.countDown()
            Thread.sleep(2_000)
            runCatching { exchange.respond(200, completedResponse("餐饮", "中", "late")) }
        }
        val provider = provider(server, readTimeoutMillis = 500)

        runBlocking {
            val requestJob = async(Dispatchers.IO) {
                runCatching { provider.suggest(samplePayload()) }.exceptionOrNull()
            }
            try {
                assertTrue("fake server did not receive the request", entered.await(2, TimeUnit.SECONDS))
                val failure = withTimeout(2_000) { requestJob.await() }
                assertTrue(failure === AiProviderException.TimedOut)
            } finally {
                requestJob.cancelAndJoin()
            }
        }
    }

    private fun provider(server: HttpServer, readTimeoutMillis: Long = 2_000): AiProvider {
        return OpenAiProvider.fromEnvironment(
            mapOf(
                "AUTO_ACCOUNTING_AI_API_KEY" to ephemeralApiKey,
                "AUTO_ACCOUNTING_AI_MODEL" to "gpt-4o-mini",
                "AUTO_ACCOUNTING_AI_ENDPOINT" to
                    "http://127.0.0.1:${server.address.port}/v1/responses",
                "AUTO_ACCOUNTING_AI_AUTH_STYLE" to "bearer",
                "AUTO_ACCOUNTING_AI_OUTPUT_MODE" to "json-schema",
                "AUTO_ACCOUNTING_AI_CONNECT_TIMEOUT_MILLIS" to "1000",
                "AUTO_ACCOUNTING_AI_READ_TIMEOUT_MILLIS" to readTimeoutMillis.toString()
            )
        )
    }

    private fun samplePayload() = AiCategorizationPayload(
        merchantTitle = "午餐",
        sourceLabel = "微信",
        transactionKind = "支出",
        amountRangeLabel = "0-50",
        categoryCandidates = listOf("餐饮", "交通")
    )

    private fun completedResponse(
        category: String,
        confidence: String,
        explanation: String
    ): String {
        val structured = """{"category":"$category","confidence":"$confidence","explanation":"$explanation"}"""
            .replace("\"", "\\\"")
        return """{
            "status":"completed",
            "output":[{
                "type":"message",
                "content":[{"type":"output_text","text":"$structured"}]
            }]
        }""".trimIndent()
    }

    private fun withFakeServer(block: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.start()
            block(server)
        } finally {
            server.stop(0)
        }
    }

    private fun HttpServer.respond(handler: (HttpExchange) -> Unit) {
        createContext("/v1/responses", handler)
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
