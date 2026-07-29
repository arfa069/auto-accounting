package com.autoaccounting.backend.ai

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class OpenAiHttpResponse(
    val statusCode: Int,
    val body: String
)

internal fun interface OpenAiHttpTransport {
    suspend fun post(
        uri: URI,
        apiKey: String,
        body: String,
        requestTimeout: Duration
    ): OpenAiHttpResponse
}

internal class JdkOpenAiHttpTransport(
    private val ioDispatcher: CoroutineDispatcher,
    private val sendRequest: (HttpRequest) -> HttpResponse<String>
) : OpenAiHttpTransport {
    constructor(
        connectTimeout: Duration,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        ioDispatcher = ioDispatcher,
        sendRequest = createJdkHttpSender(connectTimeout)
    )

    override suspend fun post(
        uri: URI,
        apiKey: String,
        body: String,
        requestTimeout: Duration
    ): OpenAiHttpResponse = runInterruptible(ioDispatcher) {
        val request = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .build()
        try {
            val response = sendRequest(request)
            OpenAiHttpResponse(response.statusCode(), response.body())
        } catch (_: HttpTimeoutException) {
            throw AiProviderException.TimedOut
        } catch (_: IOException) {
            throw AiProviderException.UpstreamFailure
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("OpenAI request interrupted").apply {
                initCause(interrupted)
            }
        }
    }
}

private fun createJdkHttpSender(
    connectTimeout: Duration
): (HttpRequest) -> HttpResponse<String> {
    val client = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    return { request ->
        client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
    }
}

class OpenAiProvider internal constructor(
    private val apiKey: String,
    private val model: String,
    private val responsesUri: URI,
    private val requestTimeout: Duration,
    private val transport: OpenAiHttpTransport
) : AiProvider {
    override suspend fun suggest(payload: AiCategorizationPayload): AiCategorizationSuggestion {
        if (payload.categoryCandidates.isEmpty()) {
            throw AiProviderException.InvalidResponse
        }
        val requestBody = buildRequestBody(payload)
        val response = transport.post(
            uri = responsesUri,
            apiKey = apiKey,
            body = requestBody,
            requestTimeout = requestTimeout
        )
        when {
            response.statusCode == 429 -> throw AiProviderException.RateLimited
            response.statusCode !in 200..299 -> throw AiProviderException.UpstreamFailure
            response.body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES -> {
                throw AiProviderException.InvalidResponse
            }
        }
        return parseResponse(response.body, payload.categoryCandidates)
    }

    private fun buildRequestBody(payload: AiCategorizationPayload): String {
        val userPayload = buildJsonObject {
            put("merchantTitle", payload.merchantTitle)
            put("sourceLabel", payload.sourceLabel)
            put("transactionKind", payload.transactionKind)
            put("amountRangeLabel", payload.amountRangeLabel)
            put("categoryCandidates", jsonStringArray(payload.categoryCandidates))
            payload.note?.let { put("note", it) }
            payload.rawEvidenceText?.let { put("rawEvidenceText", it) }
        }.toString()
        return buildJsonObject {
            put("model", model)
            put("store", false)
            put("max_output_tokens", 256)
            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put(
                        "content",
                        "你是账目分类建议器。只能从候选分类中选择一个；不要确认、写入或修改账本。" +
                            "confidence 只能为低、中、高；explanation 使用简短中文，不复述原始证据。"
                    )
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPayload)
                })
            })
            put("text", buildJsonObject {
                put("format", buildJsonObject {
                    put("type", "json_schema")
                    put("name", "accounting_category_suggestion")
                    put("strict", true)
                    put("schema", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("category", buildJsonObject {
                                put("type", "string")
                                put("enum", jsonStringArray(payload.categoryCandidates))
                            })
                            put("confidence", buildJsonObject {
                                put("type", "string")
                                put("enum", jsonStringArray(listOf("低", "中", "高")))
                            })
                            put("explanation", buildJsonObject {
                                put("type", "string")
                            })
                        })
                        put("required", jsonStringArray(listOf("category", "confidence", "explanation")))
                        put("additionalProperties", false)
                    })
                })
            })
        }.toString()
    }

    private fun parseResponse(
        body: String,
        categoryCandidates: List<String>
    ): AiCategorizationSuggestion {
        try {
            val root = JSON.parseToJsonElement(body).jsonObject
            if (root["status"]?.jsonPrimitive?.contentOrNull != "completed") {
                throw AiProviderException.InvalidResponse
            }
            val text = root["output"]
                ?.jsonArray
                ?.asSequence()
                ?.map { it.jsonObject }
                ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
                ?.flatMap { message ->
                    (message["content"]?.jsonArray?.asSequence() ?: emptySequence())
                }
                ?.map { it.jsonObject }
                ?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: throw AiProviderException.InvalidResponse
            if (text.toByteArray(Charsets.UTF_8).size > MAX_STRUCTURED_OUTPUT_BYTES) {
                throw AiProviderException.InvalidResponse
            }
            val result = JSON.parseToJsonElement(text).jsonObject
            if (result.keys != structuredOutputFields) {
                throw AiProviderException.InvalidResponse
            }
            val category = result.requiredString("category").trim()
            val confidence = result.requiredString("confidence").trim()
            val explanation = result.requiredString("explanation").trim()
            if (
                category !in categoryCandidates ||
                confidence !in setOf("低", "中", "高") ||
                explanation.isBlank() ||
                explanation.length > MAX_EXPLANATION_LENGTH
            ) {
                throw AiProviderException.InvalidResponse
            }
            return AiCategorizationSuggestion(
                category = category,
                confidenceLabel = confidence,
                explanation = explanation
            )
        } catch (error: AiProviderException) {
            throw error
        } catch (_: RuntimeException) {
            throw AiProviderException.InvalidResponse
        }
    }

    companion object {
        private val JSON = Json
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val DEFAULT_MODEL = "gpt-4o-mini"
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000L
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 15_000L
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val MAX_STRUCTURED_OUTPUT_BYTES = 4 * 1024
        private const val MAX_EXPLANATION_LENGTH = 240
        private val structuredOutputFields = setOf("category", "confidence", "explanation")

        fun fromEnvironment(env: Map<String, String>): AiProvider {
            val apiKey = env["AUTO_ACCOUNTING_OPENAI_API_KEY"].orEmpty().trim()
            val model = env["AUTO_ACCOUNTING_OPENAI_MODEL"]
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: DEFAULT_MODEL
            val baseUrl = env["AUTO_ACCOUNTING_OPENAI_BASE_URL"]
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: DEFAULT_BASE_URL
            val connectTimeout = env.positiveTimeout(
                "AUTO_ACCOUNTING_OPENAI_CONNECT_TIMEOUT_MILLIS",
                DEFAULT_CONNECT_TIMEOUT_MILLIS
            ) ?: return UnavailableAiProvider(AiProviderException.ConfigurationInvalid)
            val readTimeout = env.positiveTimeout(
                "AUTO_ACCOUNTING_OPENAI_READ_TIMEOUT_MILLIS",
                DEFAULT_READ_TIMEOUT_MILLIS
            ) ?: return UnavailableAiProvider(AiProviderException.ConfigurationInvalid)
            val baseUri = runCatching { URI(baseUrl.trimEnd('/')) }.getOrNull()
            if (apiKey.isBlank()) {
                return UnavailableAiProvider(AiProviderException.ConfigurationInvalid)
            }
            if (model.isBlank() || model.length > 120) {
                return UnavailableAiProvider(AiProviderException.ConfigurationInvalid)
            }
            if (baseUri == null || !baseUri.isAllowedProviderBaseUrl()) {
                return UnavailableAiProvider(AiProviderException.ConfigurationInvalid)
            }
            val responsesUri = runCatching { URI("${baseUri.toString().trimEnd('/')}/responses") }
                .getOrNull()
                ?: return UnavailableAiProvider(AiProviderException.ConfigurationInvalid)
            val connectDuration = Duration.ofMillis(connectTimeout)
            return OpenAiProvider(
                apiKey = apiKey,
                model = model,
                responsesUri = responsesUri,
                requestTimeout = Duration.ofMillis(readTimeout),
                transport = JdkOpenAiHttpTransport(connectDuration)
            )
        }
    }
}

private fun jsonStringArray(values: List<String>): JsonArray = buildJsonArray {
    values.forEach { add(JsonPrimitive(it)) }
}

private fun JsonObject.requiredString(name: String): String {
    val primitive = getValue(name).jsonPrimitive
    require(primitive.isString) { "Expected a JSON string." }
    return primitive.content
}

private fun Map<String, String>.positiveTimeout(name: String, defaultValue: Long): Long? {
    val raw = this[name]?.trim()?.takeIf(String::isNotBlank) ?: return defaultValue
    return raw.toLongOrNull()?.takeIf { it in 1..60_000 }
}

private fun URI.isAllowedProviderBaseUrl(): Boolean {
    if (host.isNullOrBlank() || userInfo != null || query != null || fragment != null) return false
    if (scheme.equals("https", ignoreCase = true)) return true
    if (!scheme.equals("http", ignoreCase = true)) return false
    val normalizedHost = host.lowercase()
    return normalizedHost == "localhost" || normalizedHost == "::1" || normalizedHost.startsWith("127.")
}
