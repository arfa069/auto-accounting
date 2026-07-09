package com.autoaccounting.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class AiCategorizationRequestContract(
    val merchantTitle: String,
    val sourceLabel: String,
    val transactionKind: String,
    val amountRangeLabel: String,
    val categoryCandidates: List<String> = emptyList(),
    val note: String? = null,
    val rawEvidenceText: String? = null
)

data class AiCategorizationResponseContract(
    val ok: Boolean,
    val category: String,
    val confidence: String,
    val explanation: String
)

data class CloudConfigContract(
    val ok: Boolean,
    val aiConsentGranted: Boolean,
    val enhancedContextGranted: Boolean,
    val featureFlags: Map<String, Boolean> = emptyMap()
)

object ApiJsonContracts {
    private val json = Json

    fun encodeAiCategorizationResponse(response: AiCategorizationResponseContract): String {
        return buildJsonObject {
            put("ok", response.ok)
            put("category", response.category)
            put("confidence", response.confidence)
            put("explanation", response.explanation)
        }.toString()
    }

    fun parseAiCategorizationResponse(body: String): AiCategorizationResponseContract {
        val root = json.parseToJsonElement(body).jsonObject
        return AiCategorizationResponseContract(
            ok = root.requiredBoolean("ok"),
            category = root.requiredString("category"),
            confidence = root.requiredString("confidence"),
            explanation = root.requiredString("explanation")
        )
    }

    fun encodeCloudConfigResponse(response: CloudConfigContract): String {
        return buildJsonObject {
            put("ok", response.ok)
            put("aiConsentGranted", response.aiConsentGranted)
            put("enhancedContextGranted", response.enhancedContextGranted)
            put("featureFlags", featureFlagsObject(response.featureFlags))
        }.toString()
    }

    fun parseCloudConfigResponse(body: String): CloudConfigContract {
        val root = json.parseToJsonElement(body).jsonObject
        return CloudConfigContract(
            ok = root.requiredBoolean("ok"),
            aiConsentGranted = root.requiredBoolean("aiConsentGranted"),
            enhancedContextGranted = root.requiredBoolean("enhancedContextGranted"),
            featureFlags = root["featureFlags"]?.jsonObject?.toBooleanMap().orEmpty()
        )
    }

    fun encodeFeatureFlags(featureFlags: Map<String, Boolean>): String {
        return featureFlagsObject(featureFlags).toString()
    }

    fun parseFeatureFlags(serialized: String): Map<String, Boolean> {
        return json.parseToJsonElement(serialized).jsonObject.toBooleanMap()
    }

    private fun featureFlagsObject(featureFlags: Map<String, Boolean>): JsonObject {
        return buildJsonObject {
            featureFlags.toSortedMap().forEach { (key, value) ->
                put(key, JsonPrimitive(value))
            }
        }
    }
}

private fun JsonObject.requiredBoolean(name: String): Boolean {
    return getValue(name).jsonPrimitive.boolean
}

private fun JsonObject.requiredString(name: String): String {
    return getValue(name).jsonPrimitive.content
}

private fun JsonObject.toBooleanMap(): Map<String, Boolean> {
    return entries.associate { (key, value) ->
        key to (value.jsonPrimitive.booleanOrNull
            ?: error("Feature flag `$key` must be a boolean value."))
    }
}
