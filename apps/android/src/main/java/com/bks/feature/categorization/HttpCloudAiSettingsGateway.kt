package com.bks.feature.categorization

import com.bks.api.ApiJsonContracts
import com.bks.feature.isPrivateTestHost
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException

sealed interface CloudAiSettingsGatewayResult {
    data class Success(
        val settings: AiCategorizationSettings,
        val defaultFundingAccountSyncId: String? = null,
        val supportsDefaultFundingAccount: Boolean = false
    ) : CloudAiSettingsGatewayResult
    data class Failure(val reason: AiCategorizationFailureReason) : CloudAiSettingsGatewayResult
}

interface CloudAiSettingsGateway {
    suspend fun read(token: String): CloudAiSettingsGatewayResult

    suspend fun write(
        token: String,
        settings: AiCategorizationSettings
    ): CloudAiSettingsGatewayResult

    suspend fun writeDefaultFundingAccount(
        token: String,
        syncId: String?
    ): CloudAiSettingsGatewayResult = CloudAiSettingsGatewayResult.Failure(
        AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED
    )
}

class HttpCloudAiSettingsGateway internal constructor(
    backendUrl: String,
    private val transport: AiHttpTransport = HttpUrlConnectionAiTransport(),
    allowHttp: Boolean = false
) : CloudAiSettingsGateway {
    private val readUrl = backendUrl.toBackendEndpointOrNull(
        path = "/account/cloud-config/read",
        allowHttp = allowHttp
    )
    private val writeUrl = backendUrl.toBackendEndpointOrNull(
        path = "/account/cloud-config/write",
        allowHttp = allowHttp
    )

    override suspend fun read(token: String): CloudAiSettingsGatewayResult {
        return execute(readUrl, token, emptyMap())
    }

    override suspend fun write(
        token: String,
        settings: AiCategorizationSettings
    ): CloudAiSettingsGatewayResult {
        return execute(
            writeUrl,
            token,
            mapOf(
                "aiConsentGranted" to settings.aiConsentGranted.toString(),
                "enhancedContextGranted" to
                    (settings.aiConsentGranted && settings.enhancedContextGranted).toString()
            )
        )
    }

    override suspend fun writeDefaultFundingAccount(
        token: String,
        syncId: String?
    ): CloudAiSettingsGatewayResult = execute(
        writeUrl,
        token,
        mapOf("defaultFundingAccountSyncId" to (syncId ?: ""))
    )

    private suspend fun execute(
        url: String?,
        token: String,
        form: Map<String, String>
    ): CloudAiSettingsGatewayResult {
        val endpoint = url
            ?: return CloudAiSettingsGatewayResult.Failure(
                AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED
            )
        if (token.isBlank()) {
            return CloudAiSettingsGatewayResult.Failure(
                AiCategorizationFailureReason.INVALID_SESSION
            )
        }
        val response = try {
            transport.post(endpoint, form, token)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            return CloudAiSettingsGatewayResult.Failure(
                AiCategorizationFailureReason.NETWORK_FAILURE
            )
        } catch (_: RuntimeException) {
            return CloudAiSettingsGatewayResult.Failure(
                AiCategorizationFailureReason.NETWORK_FAILURE
            )
        }
        if (response.statusCode !in 200..299) {
            return CloudAiSettingsGatewayResult.Failure(response.toFailureReason())
        }
        return try {
            val contract = ApiJsonContracts.parseCloudConfigResponse(response.body)
            require(contract.ok)
            require(contract.aiConsentGranted || !contract.enhancedContextGranted)
            CloudAiSettingsGatewayResult.Success(
                AiCategorizationSettings(
                    aiConsentGranted = contract.aiConsentGranted,
                    enhancedContextGranted = contract.enhancedContextGranted
                ),
                defaultFundingAccountSyncId = contract.defaultFundingAccountSyncId,
                supportsDefaultFundingAccount = contract.supportsDefaultFundingAccount
            )
        } catch (_: RuntimeException) {
            CloudAiSettingsGatewayResult.Failure(
                AiCategorizationFailureReason.INVALID_RESPONSE
            )
        }
    }
}

private fun AiHttpResponse.toFailureReason(): AiCategorizationFailureReason = when {
    statusCode == 401 -> AiCategorizationFailureReason.INVALID_SESSION
    statusCode == 409 -> AiCategorizationFailureReason.ACCOUNT_DELETION_PENDING
    statusCode == 429 -> AiCategorizationFailureReason.RATE_LIMITED
    statusCode >= 500 -> AiCategorizationFailureReason.SERVICE_UNAVAILABLE
    else -> AiCategorizationFailureReason.INVALID_RESPONSE
}

internal fun String.toBackendEndpointOrNull(path: String, allowHttp: Boolean): String? {
    val baseUrl = trim().trimEnd('/')
    if (baseUrl.isBlank()) return null
    val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return null
    if (uri.host.isNullOrBlank()) return null
    if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
    val transportAllowed = uri.scheme.equals("https", true) || (
        uri.scheme.equals("http", true) && allowHttp && uri.host.isPrivateTestHost()
    )
    if (!transportAllowed) return null
    return "$baseUrl$path"
}
