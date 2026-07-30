package com.autoaccounting.backend.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val MAX_PROVIDER_RESPONSE_BYTES = 64 * 1024
internal const val MAX_STRUCTURED_OUTPUT_BYTES = 4 * 1024
private const val MAX_PROVIDER_EXPLANATION_LENGTH = 240
private val structuredOutputFields = setOf("category", "confidence", "explanation")
private val supportedConfidenceLabels = setOf("低", "中", "高")
internal val PROVIDER_JSON = Json
private data class ParsedSuggestionFields(
    val keys: Set<String>,
    val category: String,
    val confidence: String,
    val explanation: String
)

internal fun categorizationSystemPrompt(): String =
    "你是账目分类建议器。只能从候选分类中选择一个；不要确认、写入或修改账本。" +
        "仅输出 JSON 对象，字段必须且只能为 category、confidence、explanation；" +
        "confidence 只能为低、中、高；explanation 使用简短中文，不复述原始证据。"

internal fun categorizationUserPayload(payload: AiCategorizationPayload): String =
    buildJsonObject {
        put("merchantTitle", payload.merchantTitle)
        put("sourceLabel", payload.sourceLabel)
        put("transactionKind", payload.transactionKind)
        put("amountRangeLabel", payload.amountRangeLabel)
        put("categoryCandidates", jsonStringArray(payload.categoryCandidates))
        payload.note?.let { put("note", it) }
        payload.rawEvidenceText?.let { put("rawEvidenceText", it) }
    }.toString()

internal fun categorizationSuggestionSchema(
    payload: AiCategorizationPayload,
    constrainCategoryToCandidates: Boolean = true
): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("category", buildJsonObject {
                put("type", "string")
                if (constrainCategoryToCandidates) {
                    put("enum", jsonStringArray(payload.categoryCandidates))
                }
            })
            put("confidence", buildJsonObject {
                put("type", "string")
                put("enum", jsonStringArray(supportedConfidenceLabels.toList()))
            })
            put("explanation", buildJsonObject {
                put("type", "string")
            })
        })
        put("required", jsonStringArray(structuredOutputFields.toList()))
        put("additionalProperties", false)
    }

internal fun parseCategorizationSuggestion(
    text: String,
    categoryCandidates: List<String>
): AiCategorizationSuggestion {
    val parsed = text
        .takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_STRUCTURED_OUTPUT_BYTES }
        ?.let {
            runCatching {
                val result = PROVIDER_JSON.parseToJsonElement(it).jsonObject
                ParsedSuggestionFields(
                    keys = result.keys,
                    category = result.requiredString("category").trim(),
                    confidence = result.requiredString("confidence").trim(),
                    explanation = result.requiredString("explanation").trim()
                )
            }.getOrNull()
        }
        ?: throw AiProviderException.InvalidResponse
    val invalidFields = listOf(
        parsed.keys != structuredOutputFields,
        parsed.category !in categoryCandidates,
        parsed.confidence !in supportedConfidenceLabels,
        parsed.explanation.isBlank(),
        parsed.explanation.length > MAX_PROVIDER_EXPLANATION_LENGTH
    ).any { it }
    if (invalidFields) {
        throw AiProviderException.InvalidResponse
    }
    return AiCategorizationSuggestion(
        category = parsed.category,
        confidenceLabel = parsed.confidence,
        explanation = parsed.explanation
    )
}

internal fun requireSuccessfulProviderResponse(response: ProviderHttpResponse) {
    val failure = when {
        response.statusCode == 429 -> AiProviderException.RateLimited
        response.statusCode !in 200..299 -> AiProviderException.UpstreamFailure
        response.body.toByteArray(Charsets.UTF_8).size > MAX_PROVIDER_RESPONSE_BYTES -> {
            AiProviderException.InvalidResponse
        }
        else -> null
    }
    if (failure != null) {
        throw failure
    }
}

internal fun parseProviderJsonObject(body: String): JsonObject? =
    runCatching { PROVIDER_JSON.parseToJsonElement(body).jsonObject }.getOrNull()

internal fun jsonStringArray(values: Collection<String>): JsonArray = buildJsonArray {
    values.forEach { add(JsonPrimitive(it)) }
}

internal fun JsonObject.requiredString(name: String): String {
    val primitive = getValue(name).jsonPrimitive
    require(primitive.isString) { "Expected a JSON string." }
    return primitive.content
}
