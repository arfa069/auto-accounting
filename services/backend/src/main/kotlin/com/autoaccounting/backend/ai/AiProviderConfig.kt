package com.autoaccounting.backend.ai

import java.net.URI
import java.time.Duration

internal enum class AiProviderAuthStyle {
    Bearer,
    XApiKey,
    ApiKey
}

internal enum class AiProviderOutputMode {
    JsonSchema,
    JsonObject,
    PromptOnly
}

internal enum class AiProviderReasoningMode {
    Disabled,
    Enabled,
    Unspecified
}

internal data class AiProviderRuntimeConfig(
    val apiKey: String,
    val model: String,
    val endpoint: URI,
    val requestHeaders: Map<String, String>,
    val connectTimeout: Duration,
    val readTimeout: Duration,
    val outputMode: AiProviderOutputMode,
    val reasoningMode: AiProviderReasoningMode,
    val apiVersion: String
)

internal fun Map<String, String>.aiProviderRuntimeConfig(
    defaultAuthStyle: AiProviderAuthStyle,
    defaultOutputMode: AiProviderOutputMode
): AiProviderRuntimeConfig? {
    val apiKey = this["AUTO_ACCOUNTING_AI_API_KEY"].orEmpty().trim()
    val model = this["AUTO_ACCOUNTING_AI_MODEL"].orEmpty().trim()
    val endpoint = this["AUTO_ACCOUNTING_AI_ENDPOINT"]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { URI(it) }.getOrNull() }
    val authStyle = parseAuthStyle(this["AUTO_ACCOUNTING_AI_AUTH_STYLE"], defaultAuthStyle)
    val outputMode = parseOutputMode(this["AUTO_ACCOUNTING_AI_OUTPUT_MODE"], defaultOutputMode)
    val reasoningMode = parseReasoningMode(this["AUTO_ACCOUNTING_AI_REASONING_MODE"])
    val apiVersion = this["AUTO_ACCOUNTING_AI_API_VERSION"]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: DEFAULT_API_VERSION
    val connectTimeout = positiveTimeout(
        "AUTO_ACCOUNTING_AI_CONNECT_TIMEOUT_MILLIS",
        DEFAULT_CONNECT_TIMEOUT_MILLIS
    )
    val readTimeout = positiveTimeout(
        "AUTO_ACCOUNTING_AI_READ_TIMEOUT_MILLIS",
        DEFAULT_READ_TIMEOUT_MILLIS
    )
    val valid = listOf(
        apiKey.isValidProviderCredential(),
        model.isValidProviderModel(),
        endpoint?.isAllowedProviderEndpoint() == true,
        authStyle != null,
        outputMode != null,
        reasoningMode != null,
        connectTimeout != null,
        readTimeout != null,
        API_VERSION_PATTERN.matches(apiVersion)
    ).all { it }
    return if (valid) {
        AiProviderRuntimeConfig(
            apiKey = apiKey,
            model = model,
            endpoint = requireNotNull(endpoint),
            requestHeaders = requireNotNull(authStyle).requestHeaders(apiKey),
            connectTimeout = Duration.ofMillis(requireNotNull(connectTimeout)),
            readTimeout = Duration.ofMillis(requireNotNull(readTimeout)),
            outputMode = requireNotNull(outputMode),
            reasoningMode = requireNotNull(reasoningMode),
            apiVersion = apiVersion
        )
    } else {
        null
    }
}

private fun Map<String, String>.positiveTimeout(name: String, defaultValue: Long): Long? {
    val raw = this[name]?.trim()?.takeIf(String::isNotBlank) ?: return defaultValue
    return raw.toLongOrNull()?.takeIf { it in 1..MAX_TIMEOUT_MILLIS }
}

private fun String.isValidProviderCredential(): Boolean =
    isNotBlank() && length <= MAX_CREDENTIAL_LENGTH && none(Char::isISOControl)

private fun String.isValidProviderModel(): Boolean =
    isNotBlank() && length <= MAX_MODEL_LENGTH && none(Char::isISOControl)

private fun URI.isAllowedProviderEndpoint(): Boolean {
    val forbiddenParts = listOf(
        host.isNullOrBlank(),
        userInfo != null,
        query != null,
        fragment != null
    )
    if (forbiddenParts.any { it }) return false
    if (scheme.equals("https", ignoreCase = true)) return true
    if (!scheme.equals("http", ignoreCase = true)) return false
    val normalizedHost = host.lowercase()
    return listOf(
        normalizedHost == "localhost",
        normalizedHost == "::1",
        normalizedHost.startsWith("127.")
    ).any { it }
}

private fun parseAuthStyle(
    raw: String?,
    defaultValue: AiProviderAuthStyle
): AiProviderAuthStyle? = when (raw?.trim()?.lowercase().orEmpty()) {
    "" -> defaultValue
    "bearer" -> AiProviderAuthStyle.Bearer
    "x-api-key" -> AiProviderAuthStyle.XApiKey
    "api-key" -> AiProviderAuthStyle.ApiKey
    else -> null
}

private fun parseOutputMode(
    raw: String?,
    defaultValue: AiProviderOutputMode
): AiProviderOutputMode? = when (raw?.trim()?.lowercase().orEmpty()) {
    "" -> defaultValue
    "json-schema" -> AiProviderOutputMode.JsonSchema
    "json-object" -> AiProviderOutputMode.JsonObject
    "prompt-only" -> AiProviderOutputMode.PromptOnly
    else -> null
}

private fun parseReasoningMode(raw: String?): AiProviderReasoningMode? =
    when (raw?.trim()?.lowercase().orEmpty()) {
        "", "unspecified" -> AiProviderReasoningMode.Unspecified
        "disabled" -> AiProviderReasoningMode.Disabled
        "enabled" -> AiProviderReasoningMode.Enabled
        else -> null
    }

private fun AiProviderAuthStyle.requestHeaders(apiKey: String): Map<String, String> =
    when (this) {
        AiProviderAuthStyle.Bearer -> mapOf("Authorization" to "Bearer $apiKey")
        AiProviderAuthStyle.XApiKey -> mapOf("x-api-key" to apiKey)
        AiProviderAuthStyle.ApiKey -> mapOf("api-key" to apiKey)
    }

private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000L
private const val DEFAULT_READ_TIMEOUT_MILLIS = 60_000L
private const val MAX_TIMEOUT_MILLIS = 300_000L
private const val MAX_CREDENTIAL_LENGTH = 4_096
private const val MAX_MODEL_LENGTH = 120
private const val DEFAULT_API_VERSION = "2023-06-01"
private val API_VERSION_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
