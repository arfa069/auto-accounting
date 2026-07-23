package com.autoaccounting.feature.account

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpAccountRepositoryTest {
    @Test
    fun registerSendsInstallationIdAndParsesSession() = runBlocking {
        val transport = RecordingTransport(
            AccountHttpResponse(
                200,
                """{"ok":true,"phone":"13800138000","token":"token-1","deletionPending":false,"requestedAtMillis":null,"finalDeletionAtMillis":null}"""
            )
        )
        val repository = repository(transport)

        val result = repository.register("13800138000", "123456", "Aa123456!")

        assertEquals(
            AccountRepositoryResult.Success(AccountCredentials("13800138000", "token-1")),
            result
        )
        assertEquals("/account/register", transport.lastUrl?.removePrefix("https://example.test"))
        assertEquals("install-id", transport.lastForm?.get("deviceId"))
        assertNull(transport.lastBearerToken)
    }

    @Test
    fun verifyUsesBearerPreservesTokenAndReadsServerDeletionDeadline() = runBlocking {
        val transport = RecordingTransport(
            AccountHttpResponse(
                200,
                """{"ok":true,"phone":"13800138000","deletionPending":true,"requestedAtMillis":1000,"finalDeletionAtMillis":604801000}"""
            )
        )
        val repository = repository(transport)

        val result = repository.verifySession(AccountCredentials("13800138000", "token-1"))
            as AccountRepositoryResult.Success

        assertEquals("token-1", result.value.token)
        assertTrue(result.value.deletionState.isPending)
        assertEquals(604_801_000L, result.value.deletionState.finalDeletionAtEpochMillis)
        assertEquals("token-1", transport.lastBearerToken)
        assertEquals(emptyMap<String, String>(), transport.lastForm)
    }

    @Test
    fun configurationNetworkInvalidSessionRateLimitAndBusinessFailuresStayDistinct() = runBlocking {
        val missing = HttpAccountRepository("", { "install-id" }, RecordingTransport(okResponse()))
            .requestSmsCode("13800138000") as AccountRepositoryResult.Failure
        assertEquals(AccountFailureKind.ConfigurationMissing, missing.kind)

        val networkTransport = RecordingTransport(IOException("offline"))
        val network = repository(networkTransport).requestSmsCode("13800138000")
            as AccountRepositoryResult.Failure
        assertEquals(AccountFailureKind.Network, network.kind)
        assertEquals(1, networkTransport.callCount)

        val invalid = repository(
            RecordingTransport(errorResponse(401, "TOKEN_INVALID", "expired"))
        ).verifySession(AccountCredentials("13800138000", "token")) as AccountRepositoryResult.Failure
        assertEquals(AccountFailureKind.InvalidSession, invalid.kind)

        val limited = repository(
            RecordingTransport(errorResponse(429, "SMS_TOO_FREQUENT", "slow down"))
        ).requestSmsCode("13800138000") as AccountRepositoryResult.Failure
        assertEquals(AccountFailureKind.RateLimited, limited.kind)

        val business = repository(
            RecordingTransport(errorResponse(409, "PHONE_ALREADY_REGISTERED", "registered"))
        ).register("13800138000", "123456", "Aa123456!") as AccountRepositoryResult.Failure
        assertEquals(AccountFailureKind.Service, business.kind)
        assertEquals("PHONE_ALREADY_REGISTERED", business.code)
    }

    @Test
    fun malformedJsonIsInvalidResponseAndIsNotRetried() = runBlocking {
        val transport = RecordingTransport(AccountHttpResponse(200, "not-json"))

        val result = repository(transport).login("13800138000", "Aa123456!")
            as AccountRepositoryResult.Failure

        assertEquals(AccountFailureKind.InvalidResponse, result.kind)
        assertEquals(1, transport.callCount)
    }

    @Test
    fun phoneAuthenticationWithoutPhoneIsInvalidResponse() = runBlocking {
        val transport = RecordingTransport(
            AccountHttpResponse(
                200,
                """{"ok":true,"token":"token-1","deletionPending":false}"""
            )
        )

        val result = repository(transport).login("13800138000", "Aa123456!")
            as AccountRepositoryResult.Failure

        assertEquals(AccountFailureKind.InvalidResponse, result.kind)
        assertEquals(1, transport.callCount)
    }

    @Test
    fun cancellationPropagatesInsteadOfBecomingNetworkFailure() {
        var cancellationPropagated = false

        try {
            runBlocking {
                repository(
                    object : AccountHttpTransport {
                        override suspend fun post(
                            url: String,
                            form: Map<String, String>,
                            bearerToken: String?
                        ): AccountHttpResponse = throw CancellationException("cancelled")
                    }
                ).requestSmsCode("13800138000")
            }
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue(cancellationPropagated)
    }

    private fun repository(transport: AccountHttpTransport): HttpAccountRepository =
        HttpAccountRepository(
            backendUrl = "https://example.test",
            installationId = { "install-id" },
            transport = transport
        )

    private fun okResponse(): AccountHttpResponse = AccountHttpResponse(200, """{"ok":true}""")

    private fun errorResponse(status: Int, code: String, message: String): AccountHttpResponse =
        AccountHttpResponse(
            status,
            """{"ok":false,"error":"$code","message":"$message"}"""
        )

    private class RecordingTransport : AccountHttpTransport {
        private val response: AccountHttpResponse?
        private val failure: IOException?
        var callCount: Int = 0
            private set
        var lastUrl: String? = null
            private set
        var lastForm: Map<String, String>? = null
            private set
        var lastBearerToken: String? = null
            private set

        constructor(response: AccountHttpResponse) {
            this.response = response
            this.failure = null
        }

        constructor(failure: IOException) {
            this.response = null
            this.failure = failure
        }

        override suspend fun post(
            url: String,
            form: Map<String, String>,
            bearerToken: String?
        ): AccountHttpResponse {
            callCount += 1
            lastUrl = url
            lastForm = form
            lastBearerToken = bearerToken
            failure?.let { throw it }
            return requireNotNull(response)
        }
    }
}
