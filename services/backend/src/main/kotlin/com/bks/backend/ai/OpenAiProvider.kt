package com.bks.backend.ai

import java.net.URI
import java.time.Duration
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class OpenAiProvider internal constructor(
    private val model: String,
    private val responsesUri: URI,
    private val requestHeaders: Map<String, String>,
    private val requestTimeout: Duration,
    private val outputMode: AiProviderOutputMode,
    private val transport: ProviderHttpTransport
) : AiProvider {
    override suspend fun suggest(payload: AiCategorizationPayload): AiCategorizationSuggestion {
        if (payload.categoryCandidates.isEmpty()) {
            throw AiProviderException.InvalidResponse
        }
        val response = transport.post(
            uri = responsesUri,
            headers = requestHeaders,
            body = buildRequestBody(payload),
            requestTimeout = requestTimeout
        )
        requireSuccessfulProviderResponse(response)
        return parseResponse(response.body, payload.categoryCandidates)
    }

    private fun buildRequestBody(payload: AiCategorizationPayload): String =
        buildJsonObject {
            put("model", model)
            put("store", false)
            put("max_output_tokens", 256)
            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", categorizationSystemPrompt())
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", categorizationUserPayload(payload))
                })
            })
            if (outputMode == AiProviderOutputMode.JsonSchema) {
                put("text", buildJsonObject {
                    put("format", buildJsonObject {
                        put("type", "json_schema")
                        put("name", "accounting_category_suggestion")
                        put("strict", true)
                        put("schema", categorizationSuggestionSchema(payload))
                    })
                })
            }
        }.toString()

    private fun parseResponse(
        body: String,
        categoryCandidates: List<String>
    ): AiCategorizationSuggestion {
        val root = parseProviderJsonObject(body)
            ?: throw AiProviderException.InvalidResponse
        val text = root
            .takeIf { it["status"]?.jsonPrimitive?.contentOrNull == "completed" }
            ?.let { validRoot ->
                runCatching {
                    validRoot.getValue("output")
                        .jsonArray
                        .asSequence()
                        .map { it.jsonObject }
                        .filter { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
                        .flatMap { message ->
                            message.getValue("content").jsonArray.asSequence()
                        }
                        .map { it.jsonObject }
                        .firstOrNull {
                            it["type"]?.jsonPrimitive?.contentOrNull == "output_text"
                        }
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.contentOrNull
                }.getOrNull()
            }
            ?: throw AiProviderException.InvalidResponse
        return parseCategorizationSuggestion(text, categoryCandidates)
    }

    companion object {
        fun fromEnvironment(env: Map<String, String>): AiProvider {
            val config = env.aiProviderRuntimeConfig(
                defaultAuthStyle = AiProviderAuthStyle.Bearer,
                defaultOutputMode = AiProviderOutputMode.JsonSchema
            ) ?: return UnavailableAiProvider(AiProviderException.ConfigurationInvalid)
            if (
                config.outputMode !in setOf(
                    AiProviderOutputMode.JsonSchema,
                    AiProviderOutputMode.PromptOnly
                ) ||
                config.reasoningMode != AiProviderReasoningMode.Unspecified
            ) {
                return UnavailableAiProvider(AiProviderException.ConfigurationInvalid)
            }
            return OpenAiProvider(
                model = config.model,
                responsesUri = config.endpoint,
                requestHeaders = config.requestHeaders,
                requestTimeout = config.readTimeout,
                outputMode = config.outputMode,
                transport = JdkProviderHttpTransport(config.connectTimeout)
            )
        }
    }
}
