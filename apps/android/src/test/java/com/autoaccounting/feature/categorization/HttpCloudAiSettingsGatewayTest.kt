package com.autoaccounting.feature.categorization

import com.autoaccounting.api.ApiJsonContracts
import com.autoaccounting.api.CloudConfigContract
import java.io.IOException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCloudAiSettingsGatewayTest {
    @Test
    fun defaultFundingAccountWriteUsesEmptyStringToClearAndReturnsCapability() = runBlocking {
        val transport = RecordingSettingsTransport(
            AiHttpResponse(
                200,
                ApiJsonContracts.encodeCloudConfigResponse(
                    CloudConfigContract(
                        ok = true,
                        aiConsentGranted = false,
                        enhancedContextGranted = false,
                        defaultFundingAccountSyncId = "funding-1",
                        supportsDefaultFundingAccount = true
                    )
                )
            )
        )
        val result = HttpCloudAiSettingsGateway("https://backend.example.test", transport)
            .writeDefaultFundingAccount("token", null)

        assertTrue(result is CloudAiSettingsGatewayResult.Success)
        assertEquals("", transport.requests.single().form["defaultFundingAccountSyncId"])
        val success = result as CloudAiSettingsGatewayResult.Success
        assertEquals("funding-1", success.defaultFundingAccountSyncId)
        assertTrue(success.supportsDefaultFundingAccount)
    }

    @Test
    fun failedDefaultWriteCanBeRetriedWithoutChangingRequestSemantics() = runBlocking {
        val transport = object : AiHttpTransport {
            var attempts = 0
            override suspend fun post(url: String, form: Map<String, String>, bearerToken: String): AiHttpResponse {
                attempts++
                return if (attempts == 1) AiHttpResponse(503, "{}") else AiHttpResponse(
                    200,
                    ApiJsonContracts.encodeCloudConfigResponse(
                        CloudConfigContract(true, false, false, defaultFundingAccountSyncId = "funding-2", supportsDefaultFundingAccount = true)
                    )
                )
            }
        }
        val gateway = HttpCloudAiSettingsGateway("https://backend.example.test", transport)
        assertTrue(gateway.writeDefaultFundingAccount("token", "funding-2") is CloudAiSettingsGatewayResult.Failure)
        val retry = gateway.writeDefaultFundingAccount("token", "funding-2")
        assertTrue(retry is CloudAiSettingsGatewayResult.Success)
        assertEquals(2, transport.attempts)
    }
    @Test
    fun readsAndWritesCloudAiConsentThroughBackend() = runBlocking {
        val transport = RecordingSettingsTransport(
            AiHttpResponse(
                200,
                ApiJsonContracts.encodeCloudConfigResponse(
                    CloudConfigContract(
                        ok = true,
                        aiConsentGranted = true,
                        enhancedContextGranted = false
                    )
                )
            )
        )
        val gateway = HttpCloudAiSettingsGateway(
            backendUrl = "https://backend.example.test/root/",
            transport = transport
        )

        val read = gateway.read("backend-token")
        val write = gateway.write(
            "backend-token",
            AiCategorizationSettings(
                aiConsentGranted = true,
                enhancedContextGranted = false
            )
        )

        assertTrue(read is CloudAiSettingsGatewayResult.Success)
        assertTrue(write is CloudAiSettingsGatewayResult.Success)
        assertEquals(
            "https://backend.example.test/root/account/cloud-config/read",
            transport.requests[0].url
        )
        assertEquals(
            "https://backend.example.test/root/account/cloud-config/write",
            transport.requests[1].url
        )
        assertEquals("backend-token", transport.requests[1].bearerToken)
        assertEquals("true", transport.requests[1].form.getValue("aiConsentGranted"))
        assertEquals("false", transport.requests[1].form.getValue("enhancedContextGranted"))
    }

    @Test
    fun disablingAiAlsoDisablesEnhancedContextInForm() = runBlocking {
        val transport = RecordingSettingsTransport(
            AiHttpResponse(
                200,
                ApiJsonContracts.encodeCloudConfigResponse(
                    CloudConfigContract(ok = true, aiConsentGranted = false, enhancedContextGranted = false)
                )
            )
        )
        val gateway = HttpCloudAiSettingsGateway("https://backend.example.test", transport)

        gateway.write(
            "token",
            AiCategorizationSettings(
                aiConsentGranted = false,
                enhancedContextGranted = true
            )
        )

        val form = transport.requests.single().form
        assertEquals("false", form.getValue("aiConsentGranted"))
        assertEquals("false", form.getValue("enhancedContextGranted"))
    }

    @Test
    fun cleartextSettingsSyncRequiresPrivateTestOptIn() = runBlocking {
        val response = AiHttpResponse(
            200,
            ApiJsonContracts.encodeCloudConfigResponse(
                CloudConfigContract(ok = true, aiConsentGranted = false, enhancedContextGranted = false)
            )
        )
        val blockedTransport = RecordingSettingsTransport(response)
        val blocked = HttpCloudAiSettingsGateway(
            backendUrl = "http://10.0.2.2:8080",
            transport = blockedTransport
        ).read("token")
        val publicHttp = HttpCloudAiSettingsGateway(
            backendUrl = "http://example.com",
            transport = blockedTransport,
            allowHttp = true
        ).read("token")
        val allowedTransport = RecordingSettingsTransport(response)
        val allowed = HttpCloudAiSettingsGateway(
            backendUrl = "http://10.0.2.2:8080",
            transport = allowedTransport,
            allowHttp = true
        ).read("token")

        assertSettingsFailure(blocked, AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED)
        assertSettingsFailure(publicHttp, AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED)
        assertTrue(blockedTransport.requests.isEmpty())
        assertTrue(allowed is CloudAiSettingsGatewayResult.Success)
    }

    @Test
    fun invalidConfigurationSessionHttpAndBodyFailuresAreStable() = runBlocking {
        val transport = RecordingSettingsTransport(AiHttpResponse(200, "not-json"))
        val blankUrl = HttpCloudAiSettingsGateway(" ", transport).read("token")
        val blankToken = HttpCloudAiSettingsGateway(
            "https://backend.example.test",
            transport
        ).read(" ")
        val invalidBody = HttpCloudAiSettingsGateway(
            "https://backend.example.test",
            transport
        ).read("token")
        val deletion = HttpCloudAiSettingsGateway(
            "https://backend.example.test",
            RecordingSettingsTransport(AiHttpResponse(409, "{}"))
        ).read("token")

        assertSettingsFailure(blankUrl, AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED)
        assertSettingsFailure(blankToken, AiCategorizationFailureReason.INVALID_SESSION)
        assertSettingsFailure(invalidBody, AiCategorizationFailureReason.INVALID_RESPONSE)
        assertSettingsFailure(deletion, AiCategorizationFailureReason.ACCOUNT_DELETION_PENDING)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun networkFailureAndCancellationArePreserved() = runBlocking {
        val network = HttpCloudAiSettingsGateway(
            "https://backend.example.test",
            object : AiHttpTransport {
                override suspend fun post(
                    url: String,
                    form: Map<String, String>,
                    bearerToken: String
                ): AiHttpResponse = throw IOException("offline")
            }
        ).read("token")
        assertSettingsFailure(network, AiCategorizationFailureReason.NETWORK_FAILURE)

        var cancelled = false
        val gateway = HttpCloudAiSettingsGateway(
            "https://backend.example.test",
            object : AiHttpTransport {
                override suspend fun post(
                    url: String,
                    form: Map<String, String>,
                    bearerToken: String
                ): AiHttpResponse = suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { cancelled = true }
                }
            }
        )
        val job = launch { gateway.read("token") }
        yield()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(cancelled)
    }

    private fun assertSettingsFailure(
        result: CloudAiSettingsGatewayResult,
        expected: AiCategorizationFailureReason
    ) {
        assertTrue(result is CloudAiSettingsGatewayResult.Failure)
        assertEquals(expected, (result as CloudAiSettingsGatewayResult.Failure).reason)
    }

    private data class RecordedSettingsRequest(
        val url: String,
        val form: Map<String, String>,
        val bearerToken: String
    )

    private class RecordingSettingsTransport(
        private val response: AiHttpResponse
    ) : AiHttpTransport {
        val requests = mutableListOf<RecordedSettingsRequest>()

        override suspend fun post(
            url: String,
            form: Map<String, String>,
            bearerToken: String
        ): AiHttpResponse {
            requests += RecordedSettingsRequest(url, form, bearerToken)
            return response
        }
    }
}
