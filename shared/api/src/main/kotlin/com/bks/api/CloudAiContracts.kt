package com.bks.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class AiCategorizationRequestContract(
    val merchantTitle: String,
    val sourceLabel: String,
    val transactionKind: String,
    val amountRangeLabel: String,
    val categoryCandidates: List<String> = emptyList(),
    val enhancedContext: Boolean = false,
    val note: String? = null,
    val rawEvidenceText: String? = null
)

data class AiCategorizationResponseContract(
    val ok: Boolean,
    val category: String,
    val confidence: String,
    val explanation: String
)

data class AiCategorizationErrorContract(
    val error: String,
    val message: String
)

data class CloudConfigContract(
    val ok: Boolean,
    val aiConsentGranted: Boolean,
    val enhancedContextGranted: Boolean,
    val featureFlags: Map<String, Boolean> = emptyMap(),
    val defaultFundingAccountSyncId: String? = null,
    val supportsDefaultFundingAccount: Boolean = false
)

object ApiJsonContracts {
    private val json = Json

    fun encodeAiCategorizationRequest(request: AiCategorizationRequestContract): String {
        return buildJsonObject {
            put("merchantTitle", request.merchantTitle)
            put("sourceLabel", request.sourceLabel)
            put("transactionKind", request.transactionKind)
            put("amountRangeLabel", request.amountRangeLabel)
            put("categoryCandidates", categoryCandidatesArray(request.categoryCandidates))
            put("enhancedContext", request.enhancedContext)
            if (request.enhancedContext) {
                request.note?.let { put("note", it) }
                request.rawEvidenceText?.let { put("rawEvidenceText", it) }
            }
        }.toString()
    }

    fun parseAiCategorizationRequest(body: String): AiCategorizationRequestContract {
        val root = json.parseToJsonElement(body).jsonObject
        return AiCategorizationRequestContract(
            merchantTitle = root.requiredString("merchantTitle"),
            sourceLabel = root.requiredString("sourceLabel"),
            transactionKind = root.requiredString("transactionKind"),
            amountRangeLabel = root.requiredString("amountRangeLabel"),
            categoryCandidates = root["categoryCandidates"]
                ?.jsonArray
                ?.map(JsonElement::requiredStringContent)
                .orEmpty(),
            enhancedContext = root["enhancedContext"]?.jsonPrimitive?.booleanOrNull ?: false,
            note = root.optionalString("note"),
            rawEvidenceText = root.optionalString("rawEvidenceText")
        )
    }

    fun encodeAiCategoryCandidates(categoryCandidates: List<String>): String {
        return categoryCandidatesArray(categoryCandidates).toString()
    }

    fun parseAiCategoryCandidates(serialized: String): List<String> {
        return json.parseToJsonElement(serialized).jsonArray.map { element ->
            element.requiredStringContent()
        }
    }

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

    fun encodeAiCategorizationError(error: AiCategorizationErrorContract): String {
        return buildJsonObject {
            put("ok", false)
            put("error", error.error)
            put("message", error.message)
        }.toString()
    }

    fun parseAiCategorizationError(body: String): AiCategorizationErrorContract {
        val root = json.parseToJsonElement(body).jsonObject
        require(!root.requiredBoolean("ok")) { "Expected an AI categorization error response." }
        return AiCategorizationErrorContract(
            error = root.requiredString("error"),
            message = root.requiredString("message")
        )
    }

    fun encodeCloudConfigResponse(response: CloudConfigContract): String {
        return buildJsonObject {
            put("ok", response.ok)
            put("aiConsentGranted", response.aiConsentGranted)
            put("enhancedContextGranted", response.enhancedContextGranted)
            put("featureFlags", featureFlagsObject(response.featureFlags))
            response.defaultFundingAccountSyncId?.let { put("defaultFundingAccountSyncId", it) }
            put("supportsDefaultFundingAccount", response.supportsDefaultFundingAccount)
        }.toString()
    }

    fun parseCloudConfigResponse(body: String): CloudConfigContract {
        val root = json.parseToJsonElement(body).jsonObject
        return CloudConfigContract(
            ok = root.requiredBoolean("ok"),
            aiConsentGranted = root.requiredBoolean("aiConsentGranted"),
            enhancedContextGranted = root.requiredBoolean("enhancedContextGranted"),
            featureFlags = root["featureFlags"]?.jsonObject?.toBooleanMap().orEmpty(),
            defaultFundingAccountSyncId = root["defaultFundingAccountSyncId"]?.requiredStringContent(),
            supportsDefaultFundingAccount = root["supportsDefaultFundingAccount"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }

    fun encodeFeatureFlags(featureFlags: Map<String, Boolean>): String {
        return featureFlagsObject(featureFlags).toString()
    }

    fun parseFeatureFlags(serialized: String): Map<String, Boolean> {
        return json.parseToJsonElement(serialized).jsonObject.toBooleanMap()
    }

    private fun categoryCandidatesArray(categoryCandidates: List<String>): JsonArray {
        return buildJsonArray {
            categoryCandidates.forEach { add(JsonPrimitive(it)) }
        }
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
    return getValue(name).requiredStringContent()
}

private fun JsonObject.optionalString(name: String): String? {
    return this[name]?.requiredStringContent()
}

private fun JsonElement.requiredStringContent(): String {
    val primitive = jsonPrimitive
    require(primitive.isString) { "Expected a JSON string." }
    return primitive.content
}

private fun JsonObject.toBooleanMap(): Map<String, Boolean> {
    return entries.associate { (key, value) ->
        key to (value.jsonPrimitive.booleanOrNull
            ?: error("Feature flag `$key` must be a boolean value."))
    }
}
