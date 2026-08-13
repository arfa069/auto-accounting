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
                phoneOnlySessionJson().replace("new-token", "token-1")
            )
        )
        val repository = repository(transport)

        val result = repository.register("13800138000", "123456", "Aa123456!")

        val credentials = (result as AccountRepositoryResult.Success).value
        assertEquals("13800138000", credentials.phone)
        assertEquals("d061c044-86c0-4673-8b07-3bd605ced1bc", credentials.accountUuid)
        assertEquals("token-1", credentials.token)
        assertNull(credentials.rawPhone)
        assertEquals("/account/register", transport.lastUrl?.removePrefix("https://example.test"))
        assertEquals("install-id", transport.lastForm?.get("deviceId"))
        assertNull(transport.lastBearerToken)
    }

    @Test
    fun verifyUsesBearerPreservesTokenAndReadsServerDeletionDeadline() = runBlocking {
        val transport = RecordingTransport(
            AccountHttpResponse(
                200,
                phoneOnlySessionJson().replace("\"token\":\"new-token\",", "").replace(
                    "\"deletionPending\":false",
                    "\"deletionPending\":true,\"requestedAtMillis\":1000,\"finalDeletionAtMillis\":604801000"
                )
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
    fun nicknameUpdateUsesRepositoryAndKeepsStableSessionFields() = runBlocking {
        val response = phoneWechatSessionJson()
            .replace("\"token\":\"new-token\",", "")
            .replace("\"nickname\":\"微信用户\"", "\"nickname\":\"新昵称\"")
            .replace(
                "\"deletionPending\":false",
                "\"deletionPending\":true,\"requestedAtMillis\":1000,\"finalDeletionAtMillis\":604801000"
            )
        val transport = RecordingTransport(AccountHttpResponse(200, response))
        val credentials = AccountCredentials(
            accountId = 42,
            token = "token-1",
            deletionState = AccountDeletionUiState(1_000, 604_801_000),
            wechatLinked = true,
            nickname = "旧昵称"
        )

        val result = repository(transport).updateNickname(credentials, "新昵称")
            as AccountRepositoryResult.Success

        assertEquals("/account/profile/nickname", transport.lastUrl?.removePrefix("https://example.test"))
        assertEquals(mapOf("nickname" to "新昵称"), transport.lastForm)
        assertEquals("token-1", transport.lastBearerToken)
        assertEquals(42L, result.value.accountId)
        assertEquals("token-1", result.value.token)
        assertEquals("新昵称", result.value.nickname)
        assertTrue(result.value.deletionState.isPending)
    }

    @Test
    fun avatarUpdateUsesProfileEndpointAndPreservesDeletionState() = runBlocking {
        val response = phoneOnlySessionJson()
            .replace("\"token\":\"new-token\",", "")
            .replace("\"avatarUrl\":null", "\"avatarUrl\":\"data:image/jpeg;base64,/9j/\"")
            .replace(
                "\"deletionPending\":false",
                "\"deletionPending\":true,\"requestedAtMillis\":1000,\"finalDeletionAtMillis\":604801000"
            )
        val transport = RecordingTransport(AccountHttpResponse(200, response))
        val credentials = AccountCredentials(
            accountId = 42,
            token = "token-1",
            deletionState = AccountDeletionUiState(1_000, 604_801_000)
        )

        val result = repository(transport).updateAvatar(
            credentials,
            "data:image/jpeg;base64,/9j/"
        ) as AccountRepositoryResult.Success

        assertEquals("/account/profile/avatar", transport.lastUrl?.removePrefix("https://example.test"))
        assertEquals("data:image/jpeg;base64,/9j/", transport.lastForm?.get("avatarDataUrl"))
        assertEquals("token-1", transport.lastBearerToken)
        assertEquals("data:image/jpeg;base64,/9j/", result.value.avatarUrl)
        assertTrue(result.value.deletionState.isPending)
    }

    @Test
    fun configurationNetworkInvalidSessionRateLimitAndBusinessFailuresStayDistinct() = runBlocking {
        val missing = HttpAccountRepository("", { "install-id" }, RecordingTransport(okResponse()))
            .requestVerificationCode("13800138000", AccountVerificationPurpose.Register) as AccountRepositoryResult.Failure
        assertEquals(AccountFailureKind.ConfigurationMissing, missing.kind)

        val publicHttp = HttpAccountRepository(
            "http://example.test",
            { "install-id" },
            RecordingTransport(okResponse()),
            allowHttp = true
        ).requestVerificationCode(
            "13800138000",
            AccountVerificationPurpose.Register
        ) as AccountRepositoryResult.Failure
        assertEquals(AccountFailureKind.ConfigurationMissing, publicHttp.kind)

        val privateTransport = RecordingTransport(okResponse())
        HttpAccountRepository(
            "http://127.0.0.1:8080",
            { "install-id" },
            privateTransport,
            allowHttp = true
        ).requestVerificationCode("13800138000", AccountVerificationPurpose.Register)
        assertEquals("http://127.0.0.1:8080/account/verification-code", privateTransport.lastUrl)

        val networkTransport = RecordingTransport(IOException("offline"))
        val network = repository(networkTransport).requestVerificationCode(
            "13800138000",
            AccountVerificationPurpose.Register
        )
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
        ).requestVerificationCode("13800138000", AccountVerificationPurpose.Register) as AccountRepositoryResult.Failure
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
    fun usernameAuthenticationWithoutPhoneIsAccepted() = runBlocking {
        val transport = RecordingTransport(
            AccountHttpResponse(
                200,
                """{"ok":true,"primaryIdentifier":{"type":"USERNAME","value":"user_one","verified":true},"identifiers":[{"type":"USERNAME","value":"user_one","verified":true}],"token":"token-1","deletionPending":false}"""
            )
        )

        val result = repository(transport).login("user_one", "Aa123456!")
            as AccountRepositoryResult.Success

        assertEquals("user_one", result.value.username)
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
                    """{"ok":true,"status":"LINK_TICKET_ISSUED","linkTicket":"link-ticket","ticketExpiresAtMillis":300000}"""
                ),
                AccountHttpResponse(
                    200,
                    """{"ok":true,"status":"LINK_TICKET_ISSUED","linkTicket":"replacement-ticket","ticketExpiresAtMillis":300000}"""
                ),
                AccountHttpResponse(200, phoneWechatSessionJson()),
                AccountHttpResponse(200, phoneWechatSessionJson()),
                AccountHttpResponse(
                    200,
                    """{"ok":true,"mergeTicket":"merge-ticket","ticketExpiresAtMillis":300000,"currentIdentifiers":[],"currentWechatLinked":true,"currentNickname":"微信用户","sourceIdentifiers":[{"type":"PHONE","value":"13800138000","verified":true}],"sourceWechatLinked":false,"sourceNickname":null}"""
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

        repository.linkWechatWithCode("wx-ticket", "13800138000", "123456")
        assertRequest(transport.requests.last(), "/account/wechat/link/code", "code" to "123456")

        repository.requestVerificationCode(
            identifier = "13800138000",
            purpose = AccountVerificationPurpose.WechatUnlink,
            bearerToken = "current-token"
        )
        assertRequest(transport.requests.last(), "/account/verification-code", "purpose" to "WECHAT_UNLINK", "current-token")

        repository.prepareIdentifierLink("current-token", "13800138000")
        assertRequest(transport.requests.last(), "/account/identifier/link/prepare", "identifier" to "13800138000", "current-token")
        assertTrue("replaceExisting" !in transport.requests.last().form)

        repository.prepareIdentifierLink("current-token", "13900139000", replaceExisting = true)
        assertRequest(transport.requests.last(), "/account/identifier/link/prepare", "replaceExisting" to "true", "current-token")

        repository.completeIdentifierLink("current-token", "link-ticket", "123456")
        assertRequest(transport.requests.last(), "/account/identifier/link/complete", "linkTicket" to "link-ticket", "current-token")
        assertTrue("password" !in transport.requests.last().form)

        repository.completeIdentifierLink(
            "current-token",
            "link-ticket",
            "123456",
            "Aa123456!"
        )
        assertRequest(
            transport.requests.last(),
            "/account/identifier/link/complete",
            "password" to "Aa123456!",
            "current-token"
        )

        val preview = repository.prepareMergeWithIdentifierPassword(
            "current-token",
            "13800138000",
            "Aa123456!"
        ) as AccountRepositoryResult.Success
        assertEquals("merge-ticket", preview.value.mergeTicket)
        assertRequest(transport.requests.last(), "/account/merge/prepare/identifier-password", "identifier" to "13800138000", "current-token")

        repository.confirmMerge("current-token", "merge-ticket", "合并账号")
        assertRequest(transport.requests.last(), "/account/merge/confirm", "confirmText" to "合并账号", "current-token")

        val passwordUnlink = repository.unlinkWechatWithPassword(
            "current-token",
            "Aa123456!"
        ) as AccountRepositoryResult.Success
        assertTrue(!passwordUnlink.value.wechatLinked)
        assertRequest(transport.requests.last(), "/account/wechat/unlink/password", "password" to "Aa123456!", "current-token")

        repository.unlinkWechatWithCode("current-token", "13800138000", "123456")
        assertRequest(transport.requests.last(), "/account/wechat/unlink/code", "code" to "123456", "current-token")
        assertEquals("13800138000", transport.requests.last().form["identifier"])
    }

    @Test
    fun exchangeMapsSignedInAndMergeRequiredStates() = runBlocking {
        val transport = QueueTransport(
            listOf(
                AccountHttpResponse(200, phoneWechatExchangeJson()),
                AccountHttpResponse(
                    200,
                    """{"ok":true,"status":"MERGE_REQUIRED","mergeTicket":"merge-ticket","sourceNickname":"来源微信","sourceIdentifiers":[],"ticketExpiresAtMillis":300000}"""
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
                ).requestVerificationCode("13800138000", AccountVerificationPurpose.Register)
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
        """{"ok":true,"accountId":42,"token":"wechat-token","wechatLinked":true,"nickname":"微信用户","avatarUrl":"https://example.com/a.jpg","deletionPending":false}"""

    private fun phoneWechatSessionJson(): String =
        """{"ok":true,"accountId":42,"primaryIdentifier":{"type":"PHONE","value":"13800138000","verified":true},"identifiers":[{"type":"PHONE","value":"13800138000","verified":true}],"token":"new-token","wechatLinked":true,"nickname":"微信用户","avatarUrl":"https://example.com/a.jpg","deletionPending":false}"""

    private fun phoneOnlySessionJson(): String =
        """{"ok":true,"accountId":42,"accountUuid":"d061c044-86c0-4673-8b07-3bd605ced1bc","primaryIdentifier":{"type":"PHONE","value":"13800138000","verified":true},"identifiers":[{"type":"PHONE","value":"13800138000","verified":true}],"token":"new-token","wechatLinked":false,"nickname":null,"avatarUrl":null,"deletionPending":false}"""

    private fun phoneWechatExchangeJson(): String =
        """{"ok":true,"status":"SIGNED_IN","accountId":42,"primaryIdentifier":{"type":"PHONE","value":"13800138000","verified":true},"identifiers":[{"type":"PHONE","value":"13800138000","verified":true}],"token":"new-token","wechatLinked":true,"nickname":"微信用户","avatarUrl":"https://example.com/a.jpg","deletionPending":false}"""

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
