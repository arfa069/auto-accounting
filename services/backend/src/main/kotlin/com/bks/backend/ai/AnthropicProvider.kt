package com.bks.backend.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AnthropicProvider internal constructor(
    private val config: AiProviderRuntimeConfig,
    private val transport: ProviderHttpTransport
) : AiProvider {
    override suspend fun suggest(payload: AiCategorizationPayload): AiCategorizationSuggestion {
        if (payload.categoryCandidates.isEmpty()) {
            throw AiProviderException.InvalidResponse
        }
        val response = transport.post(
            uri = config.endpoint,
            headers = config.requestHeaders + ("anthropic-version" to config.apiVersion),
            body = buildRequestBody(payload),
            requestTimeout = config.readTimeout
        )
        requireSuccessfulProviderResponse(response)
        return parseResponse(response.body, payload.categoryCandidates)
    }

    private fun buildRequestBody(payload: AiCategorizationPayload): String =
        buildJsonObject {
            put("model", config.model)
            put("max_tokens", 256)
            put("system", categorizationSystemPrompt())
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", categorizationUserPayload(payload))
                })
            })
            if (config.outputMode == AiProviderOutputMode.JsonSchema) {
                put("output_config", buildJsonObject {
                    put("format", buildJsonObject {
                        put("type", "json_schema")
                        put(
                            "schema",
                            categorizationSuggestionSchema(
                                payload = payload,
                                constrainCategoryToCandidates = false
                            )
                        )
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
        val invalidEnvelope = listOf(
            root["type"]?.jsonPrimitive?.contentOrNull != "message",
            root["role"]?.jsonPrimitive?.contentOrNull != "assistant",
            root["stop_reason"]?.jsonPrimitive?.contentOrNull != "end_turn"
        ).any { it }
        val text = root
            .takeUnless { invalidEnvelope }
            ?.let { validRoot ->
                runCatching {
                    validRoot.getValue("content")
                        .jsonArray
                        .asSequence()
                        .map { it.jsonObject }
                        .firstOrNull {
                            it["type"]?.jsonPrimitive?.contentOrNull == "text"
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
                defaultAuthStyle = AiProviderAuthStyle.XApiKey,
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
            return AnthropicProvider(
                config = config,
                transport = JdkProviderHttpTransport(config.connectTimeout)
            )
        }
    }
}
