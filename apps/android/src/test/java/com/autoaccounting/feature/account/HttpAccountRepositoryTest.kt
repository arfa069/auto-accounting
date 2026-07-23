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

        val wrongPassword = repository(
            RecordingTransport(errorResponse(401, "LOGIN_FAILED", "手机号或密码不正确"))
        ).unlinkWechatWithPassword("token", "wrong-password") as AccountRepositoryResult.Failure
        assertEquals(AccountFailureKind.Service, wrongPassword.kind)
        assertEquals("LOGIN_FAILED", wrongPassword.code)
        assertEquals("手机号或密码不正确", wrongPassword.message)

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
    fun wechatPhoneLinkMergeAndUnlinkUseExpectedFormsAndParseNullablePhoneSessions() = runBlocking {
        val transport = QueueTransport(
            listOf(
                AccountHttpResponse(
                    200,
                    """{"ok":true,"status":"REGISTRATION_REQUIRED","wechatTicket":"wx-ticket","nickname":"微信用户","avatarUrl":"https://example.com/a.jpg","ticketExpiresAtMillis":300000}"""
                ),
                AccountHttpResponse(200, wechatSessionJson()),
                AccountHttpResponse(200, phoneWechatSessionJson()),
                AccountHttpResponse(200, phoneWechatSessionJson()),
                okResponse(),
                AccountHttpResponse(
                    200,
                    """{"ok":true,"status":"PHONE_TICKET_ISSUED","phoneTicket":"phone-ticket","ticketExpiresAtMillis":300000}"""
                ),
                AccountHttpResponse(200, phoneWechatSessionJson()),
                AccountHttpResponse(
                    200,
                    """{"ok":true,"mergeTicket":"merge-ticket","ticketExpiresAtMillis":300000,"currentPhone":null,"currentWechatLinked":true,"currentNickname":"微信用户","sourcePhone":"13800138000","sourceWechatLinked":false,"sourceNickname":null}"""
                ),
                AccountHttpResponse(200, phoneWechatSessionJson()),
                AccountHttpResponse(200, phoneOnlySessionJson()),
                AccountHttpResponse(200, phoneOnlySessionJson())
            )
        )
        val repository = repository(transport)

        val exchange = repository.exchangeWechatCode("one-time-code") as AccountRepositoryResult.Success
        assertTrue(exchange.value is AccountWechatAuthResult.RegistrationRequired)
        assertRequest(transport.requests.last(), "/account/wechat/exchange", "code" to "one-time-code")

        val registered = repository.registerWithWechat("wx-ticket") as AccountRepositoryResult.Success
        assertNull(registered.value.phone)
        assertTrue(registered.value.wechatLinked)
        assertEquals("微信用户", registered.value.nickname)
        assertRequest(transport.requests.last(), "/account/wechat/register", "wechatTicket" to "wx-ticket")

        repository.linkWechatWithPassword("wx-ticket", "13800138000", "Aa123456!")
        assertRequest(transport.requests.last(), "/account/wechat/link/password", "password" to "Aa123456!")

        repository.linkWechatWithSms("wx-ticket", "13800138000", "123456")
        assertRequest(transport.requests.last(), "/account/wechat/link/sms", "code" to "123456")

        repository.requestSmsCode(
            phone = "13800138000",
            purpose = AccountSmsPurpose.WechatUnlink,
            bearerToken = "current-token"
        )
        assertRequest(transport.requests.last(), "/account/sms", "purpose" to "WECHAT_UNLINK", "current-token")

        repository.preparePhoneLink("current-token", "13800138000", "123456")
        assertRequest(transport.requests.last(), "/account/phone/link/prepare", "code" to "123456", "current-token")

        repository.completePhoneLink("current-token", "phone-ticket", "Aa123456!")
        assertRequest(transport.requests.last(), "/account/phone/link/complete", "phoneTicket" to "phone-ticket", "current-token")

        val preview = repository.prepareMergeWithPhonePassword(
            "current-token",
            "13800138000",
            "Aa123456!"
        ) as AccountRepositoryResult.Success
        assertEquals("merge-ticket", preview.value.mergeTicket)
        assertRequest(transport.requests.last(), "/account/merge/prepare/phone-password", "phone" to "13800138000", "current-token")

        repository.confirmMerge("current-token", "merge-ticket", "合并账号")
        assertRequest(transport.requests.last(), "/account/merge/confirm", "confirmText" to "合并账号", "current-token")

        val passwordUnlink = repository.unlinkWechatWithPassword(
            "current-token",
            "Aa123456!"
        ) as AccountRepositoryResult.Success
        assertTrue(!passwordUnlink.value.wechatLinked)
        assertRequest(transport.requests.last(), "/account/wechat/unlink/password", "password" to "Aa123456!", "current-token")

        repository.unlinkWechatWithSms("current-token", "123456")
        assertRequest(transport.requests.last(), "/account/wechat/unlink/sms", "code" to "123456", "current-token")
    }

    @Test
    fun exchangeMapsSignedInAndMergeRequiredStates() = runBlocking {
        val transport = QueueTransport(
            listOf(
                AccountHttpResponse(200, phoneWechatExchangeJson()),
                AccountHttpResponse(
                    200,
                    """{"ok":true,"status":"MERGE_REQUIRED","mergeTicket":"merge-ticket","sourceNickname":"来源微信","sourcePhone":null,"ticketExpiresAtMillis":300000}"""
                )
            )
        )
        val repository = repository(transport)

        val signedIn = repository.exchangeWechatCode("code", "current-token") as AccountRepositoryResult.Success
        val signedCredentials = (signedIn.value as AccountWechatAuthResult.SignedIn).credentials
        assertEquals("13800138000", signedCredentials.phone)
        assertTrue(signedCredentials.wechatLinked)
        assertEquals("current-token", transport.requests.first().bearerToken)

        val merge = repository.exchangeWechatCode("code", "current-token") as AccountRepositoryResult.Success
        assertEquals("merge-ticket", (merge.value as AccountWechatAuthResult.MergeRequired).mergeTicket)
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

    private fun wechatSessionJson(): String =
        """{"ok":true,"phone":null,"token":"wechat-token","wechatLinked":true,"nickname":"微信用户","avatarUrl":"https://example.com/a.jpg","deletionPending":false}"""

    private fun phoneWechatSessionJson(): String =
        """{"ok":true,"phone":"13800138000","token":"new-token","wechatLinked":true,"nickname":"微信用户","avatarUrl":"https://example.com/a.jpg","deletionPending":false}"""

    private fun phoneOnlySessionJson(): String =
        """{"ok":true,"phone":"13800138000","token":"new-token","wechatLinked":false,"nickname":null,"avatarUrl":null,"deletionPending":false}"""

    private fun phoneWechatExchangeJson(): String =
        """{"ok":true,"status":"SIGNED_IN","phone":"13800138000","token":"new-token","wechatLinked":true,"nickname":"微信用户","avatarUrl":"https://example.com/a.jpg","deletionPending":false}"""

    private fun assertRequest(
        request: RecordedRequest,
        path: String,
        formEntry: Pair<String, String>,
        bearerToken: String? = null
    ) {
        assertEquals(path, request.url.removePrefix("https://example.test"))
        assertEquals(formEntry.second, request.form[formEntry.first])
        assertEquals(bearerToken, request.bearerToken)
        assertEquals("install-id", request.form["deviceId"] ?: "install-id")
    }

    private data class RecordedRequest(
        val url: String,
        val form: Map<String, String>,
        val bearerToken: String?
    )

    private class QueueTransport(
        responses: List<AccountHttpResponse>
    ) : AccountHttpTransport {
        private val remaining = ArrayDeque(responses)
        val requests = mutableListOf<RecordedRequest>()

        override suspend fun post(
            url: String,
            form: Map<String, String>,
            bearerToken: String?
        ): AccountHttpResponse {
            requests += RecordedRequest(url, form, bearerToken)
            return remaining.removeFirst()
        }
    }

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
