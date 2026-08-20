package com.bks.backend.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class DeepSeekProvider internal constructor(
    private val config: AiProviderRuntimeConfig,
    private val transport: ProviderHttpTransport
) : AiProvider {
    override suspend fun suggest(payload: AiCategorizationPayload): AiCategorizationSuggestion {
        if (payload.categoryCandidates.isEmpty()) {
            throw AiProviderException.InvalidResponse
        }
        val response = transport.post(
            uri = config.endpoint,
            headers = config.requestHeaders,
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
            put("stream", false)
            if (config.reasoningMode != AiProviderReasoningMode.Unspecified) {
                put("thinking", buildJsonObject {
                    put(
                        "type",
                        when (config.reasoningMode) {
                            AiProviderReasoningMode.Disabled -> "disabled"
                            AiProviderReasoningMode.Enabled -> "enabled"
                            AiProviderReasoningMode.Unspecified -> error("unreachable")
                        }
                    )
                })
            }
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", categorizationSystemPrompt())
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", categorizationUserPayload(payload))
                })
            })
            if (config.outputMode == AiProviderOutputMode.JsonObject) {
                put("response_format", buildJsonObject {
                    put("type", "json_object")
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
            .takeIf { it["object"]?.jsonPrimitive?.contentOrNull == "chat.completion" }
            ?.let { validRoot ->
                runCatching {
                    val choice = validRoot.getValue("choices")
                        .jsonArray
                        .singleOrNull()
                        ?.jsonObject
                        ?: return@runCatching null
                    choice
                        .takeIf {
                            it["finish_reason"]?.jsonPrimitive?.contentOrNull == "stop"
                        }
                        ?.get("message")
                        ?.jsonObject
                        ?.get("content")
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
                defaultOutputMode = AiProviderOutputMode.JsonObject
            ) ?: return UnavailableAiProvider(AiProviderException.ConfigurationInvalid)
            if (
                config.outputMode !in setOf(
                    AiProviderOutputMode.JsonObject,
                    AiProviderOutputMode.PromptOnly
                )
            ) {
                return UnavailableAiProvider(AiProviderException.ConfigurationInvalid)
            }
            return DeepSeekProvider(
                config = config,
                transport = JdkProviderHttpTransport(config.connectTimeout)
            )
        }
    }
}
