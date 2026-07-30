package com.autoaccounting.backend.account

import com.autoaccounting.api.AccountApiJsonContracts
import com.autoaccounting.api.AccountDeletionStatusContract
import com.autoaccounting.api.WechatAuthResultContract
import com.autoaccounting.backend.module
import com.autoaccounting.backend.ai.AiCategorizationService
import com.autoaccounting.backend.config.CloudConfigService
import com.autoaccounting.backend.sync.LedgerSyncService
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


class AccountRoutesTest {
    @Test
    fun nicknameUpdatePersistsStableAccountIdAndDeletionState() = testApplication {
        val store = InMemoryAccountStore()
        val clock = MutableClock(1_000)
        application {
            module(
                accountService = AccountService(
                    store = store,
                    smsCodeGenerator = { "123456" },
                    tokenGenerator = { "token-1" },
                    clock = clock
                )
            )
        }

        client.submitForm(
            url = "/account/verification-code",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("deviceId", "device-a")
                append("purpose", "REGISTER")
            }
        )
        val registered = client.submitForm(
            url = "/account/register",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("code", "123456")
                append("password", "Aa123456!")
                append("deviceId", "device-a")
            }
        )
        val accountId = requireNotNull(
            AccountApiJsonContracts.parseSessionResponse(registered.bodyAsText()).accountId
        )
        client.submitForm(
            url = "/account/delete/request",
            formParameters = Parameters.Empty
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }

        val updated = client.submitForm(
            url = "/account/profile/nickname",
            formParameters = Parameters.build {
                append("nickname", " 新昵称 ")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }

        assertEquals(HttpStatusCode.OK, updated.status)
        val updatedContract = AccountApiJsonContracts.parseSessionResponse(updated.bodyAsText())
        assertEquals(accountId, updatedContract.accountId)
        assertEquals("新昵称", updatedContract.nickname)
        assertTrue(updatedContract.deletionStatus.pending)
        assertNull(updatedContract.token)

        val verified = client.submitForm(
            url = "/account/token/verify",
            formParameters = Parameters.Empty
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }
        val verifiedContract = AccountApiJsonContracts.parseSessionResponse(verified.bodyAsText())
        assertEquals(accountId, verifiedContract.accountId)
        assertEquals("新昵称", verifiedContract.nickname)
        assertTrue(verifiedContract.deletionStatus.pending)

        val avatar = client.submitForm(
            url = "/account/profile/avatar",
            formParameters = Parameters.build {
                append("avatarDataUrl", "data:image/jpeg;base64,/9j/")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }
        assertEquals(HttpStatusCode.OK, avatar.status)
        assertEquals(
            "data:image/jpeg;base64,/9j/",
            AccountApiJsonContracts.parseSessionResponse(avatar.bodyAsText()).avatarUrl
        )

        val avatarVerified = client.submitForm(
            url = "/account/token/verify",
            formParameters = Parameters.Empty
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }
        assertEquals(
            "data:image/jpeg;base64,/9j/",
            AccountApiJsonContracts.parseSessionResponse(avatarVerified.bodyAsText()).avatarUrl
        )
    }

    @Test
    fun smsRateLimitUsesObservedRemoteIpAndIgnoresUntrustedIpOverrides() = testApplication {
        application {
            module(
                accountService = AccountService(
                    smsCodeGenerator = { "123456" },
                    tokenGenerator = { "token-1" },
                    clock = MutableClock(0)
                )
            )
        }

        repeat(5) { index ->
            val response = client.submitForm(
                url = "/account/verification-code",
                formParameters = Parameters.build {
                    append("identifier", "1380013800$index")
                    append("deviceId", "device-$index")
                    append("ipAddress", "203.0.113.$index")
                    append("purpose", "REGISTER")
                }
            ) {
                header("X-Forwarded-For", "203.0.113.${index + 20}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        val limited = client.submitForm(
            url = "/account/verification-code",
            formParameters = Parameters.build {
                append("identifier", "13900139000")
                append("deviceId", "device-final")
                append("ipAddress", "198.51.100.10")
                append("purpose", "REGISTER")
            }
        ) {
            header("X-Forwarded-For", "198.51.100.20")
        }
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertTrue(limited.bodyAsText().contains("SMS_TOO_FREQUENT"))
    }

    @Test
    fun trustedProxyHeadersUseOriginalRemoteIpForRateLimit() = testApplication {
        val accountService = AccountService(
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "token-1" },
            clock = MutableClock(0)
        )
        application {
            module(
                env = mapOf(
                    "AUTO_ACCOUNTING_HOST" to "127.0.0.1",
                    "AUTO_ACCOUNTING_TRUST_PROXY_HEADERS" to "true"
                ),
                accountService = accountService,
                aiCategorizationService = AiCategorizationService(),
                cloudConfigService = CloudConfigService(accountService = accountService),
                ledgerSyncService = LedgerSyncService(accountService = accountService)
            )
        }

        repeat(6) { index ->
            val response = client.submitForm(
                url = "/account/verification-code",
                formParameters = Parameters.build {
                    append("identifier", "1380013800$index")
                    append("deviceId", "device-$index")
                    append("purpose", "REGISTER")
                }
            ) {
                header("X-Forwarded-For", "192.168.1.${index + 20}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun registerLoginRecoveryAndTokenVerifyReturnStableJsonContracts() = testApplication {
        val clock = MutableClock()
        application {
            module(
                accountService = AccountService(
                    smsCodeGenerator = { "123456" },
                    tokenGenerator = { "token-1" },
                    clock = clock
                )
            )
        }

        val sms = client.submitForm(
            url = "/account/verification-code",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("deviceId", "device-a")
                append("purpose", "REGISTER")
            }
        )
        assertEquals(HttpStatusCode.OK, sms.status)
        assertEquals("""{"ok":true}""", sms.bodyAsText())

        val registered = client.submitForm(
            url = "/account/register",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("code", "123456")
                append("password", "Aa123456!")
                append("deviceId", "device-a")
            }
        )
        assertEquals(HttpStatusCode.OK, registered.status)
        assertTrue(registered.bodyAsText().contains(""""token":"token-1""""))

        val failedLogin = client.submitForm(
            url = "/account/login",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("password", "bad")
            }
        )
        assertEquals(HttpStatusCode.Unauthorized, failedLogin.status)
        assertTrue(failedLogin.bodyAsText().contains(""""error":"LOGIN_FAILED""""))

        clock.advanceBy(61_000)
        client.submitForm(
            url = "/account/verification-code",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("deviceId", "device-a")
                append("ipAddress", "127.0.0.2")
                append("purpose", "RECOVERY")
            }
        )

        val recovered = client.submitForm(
            url = "/account/recover",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("code", "123456")
                append("password", "Bb123456!")
                append("deviceId", "device-a")
            }
        )
        assertEquals(HttpStatusCode.OK, recovered.status)
        assertTrue(recovered.bodyAsText().contains(""""token":"token-1""""))

        val verified = client.submitForm(
            url = "/account/token/verify",
            formParameters = Parameters.Empty
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }
        assertEquals(HttpStatusCode.OK, verified.status)
        val verifiedContract = AccountApiJsonContracts.parseSessionResponse(verified.bodyAsText())
        assertEquals("13800138000", verifiedContract.primaryIdentifier?.value)
        assertEquals(null, verifiedContract.token)

        val signedOut = client.submitForm(
            url = "/account/logout",
            formParameters = Parameters.Empty
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }
        assertEquals(HttpStatusCode.OK, signedOut.status)

        val verifiedAfterLogout = client.submitForm(
            url = "/account/token/verify",
            formParameters = Parameters.Empty
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }
        assertEquals(HttpStatusCode.Unauthorized, verifiedAfterLogout.status)
    }

    @Test
    fun accountDeletionRoutesExposePendingAndCancelContracts() = testApplication {
        application {
            module(
                accountService = AccountService(
                    smsCodeGenerator = { "123456" },
                    tokenGenerator = { "token-1" },
                    clock = MutableClock(0)
                )
            )
        }

        client.submitForm(
            url = "/account/verification-code",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("deviceId", "device-a")
                append("purpose", "REGISTER")
            }
        )
        client.submitForm(
            url = "/account/register",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("code", "123456")
                append("password", "Aa123456!")
                append("deviceId", "device-a")
            }
        )

        val requested = client.submitForm(
            url = "/account/delete/request",
            formParameters = Parameters.build {
                append("phone", "13900139000")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }
        assertEquals(HttpStatusCode.OK, requested.status)
        assertEquals(
            AccountDeletionStatusContract(
                pending = true,
                requestedAtMillis = 0,
                finalDeletionAtMillis = 604_800_000
            ),
            AccountApiJsonContracts.parseDeletionStatusResponse(requested.bodyAsText())
        )

        val unauthenticatedConfigWrite = client.submitForm(
            url = "/account/cloud-config/write",
            formParameters = Parameters.build {
                append("phone", "13800138000")
            }
        )
        assertEquals(HttpStatusCode.Unauthorized, unauthenticatedConfigWrite.status)
        assertTrue(unauthenticatedConfigWrite.bodyAsText().contains("TOKEN_INVALID"))

        val configWrite = client.submitForm(
            url = "/account/cloud-config/write",
            formParameters = Parameters.build {
                append("aiConsentGranted", "true")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }
        assertEquals(HttpStatusCode.Conflict, configWrite.status)
        assertTrue(configWrite.bodyAsText().contains("ACCOUNT_DELETION_PENDING"))

        val canceled = client.submitForm(
            url = "/account/delete/cancel",
            formParameters = Parameters.build {
                append("phone", "13900139000")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }
        assertEquals(HttpStatusCode.OK, canceled.status)
        assertEquals("""{"ok":true}""", canceled.bodyAsText())

        val status = client.submitForm(
            url = "/account/delete/status",
            formParameters = Parameters.Empty
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }
        assertEquals(AccountDeletionStatusContract(), AccountApiJsonContracts.parseDeletionStatusResponse(status.bodyAsText()))
    }

    @Test
    fun wechatExchangeUnconfiguredReturnsServiceUnavailable() = testApplication {
        application {
            module(
                accountService = AccountService(
                    wechatOAuthClient = DefaultWechatOAuthClient(appId = "", appSecret = "")
                )
            )
        }

        val response = client.submitForm(
            url = "/account/wechat/exchange",
            formParameters = Parameters.build {
                append("code", "some_code")
            }
        )
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("WECHAT_NOT_CONFIGURED"))
    }

    @Test
    fun testWechatRegisterAndLinkRoutes() = testApplication {
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(
            smsCodeGenerator = { "123456" },
            wechatOAuthClient = fakeClient
        )
        application {
            module(accountService = service)
        }

        // 1. WeChat code exchange -> REGISTRATION_REQUIRED
        val exResponse = client.submitForm(
            url = "/account/wechat/exchange",
            formParameters = Parameters.build { append("code", "good_code") }
        )
        assertEquals(exResponse.bodyAsText(), HttpStatusCode.OK, exResponse.status)
        val exContract = AccountApiJsonContracts.parseWechatExchangeResponse(exResponse.bodyAsText())
        val regReq = exContract.result as WechatAuthResultContract.RegistrationRequired

        // 2. Register via WeChat route
        val regResponse = client.submitForm(
            url = "/account/wechat/register",
            formParameters = Parameters.build {
                append("wechatTicket", regReq.wechatTicket)
                append("deviceId", "dev_route")
            }
        )
        assertEquals(regResponse.bodyAsText(), HttpStatusCode.OK, regResponse.status)
        val session1 = AccountApiJsonContracts.parseSessionResponse(regResponse.bodyAsText())
        assertNull(session1.primaryIdentifier)
        assertTrue(session1.wechatLinked)
        assertEquals("微信小张", session1.nickname)

        val pureWechatToken = requireNotNull(session1.token)
        val prepared = client.submitForm(
            url = "/account/identifier/link/prepare",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("deviceId", "dev_route")
            }
        ) { header(HttpHeaders.Authorization, "Bearer $pureWechatToken") }
        val linkTicket = (
            AccountApiJsonContracts.parseIdentifierLinkPrepareResponse(prepared.bodyAsText())
                as com.autoaccounting.api.IdentifierLinkPrepareResponseContract.LinkTicketIssued
        ).linkTicket

        val missingPassword = client.submitForm(
            url = "/account/identifier/link/complete",
            formParameters = Parameters.build {
                append("linkTicket", linkTicket)
                append("code", "123456")
                append("deviceId", "dev_route")
            }
        ) { header(HttpHeaders.Authorization, "Bearer $pureWechatToken") }
        assertEquals(HttpStatusCode.BadRequest, missingPassword.status)
        assertTrue(missingPassword.bodyAsText().contains("INVALID_REQUEST"))

        val linked = client.submitForm(
            url = "/account/identifier/link/complete",
            formParameters = Parameters.build {
                append("linkTicket", linkTicket)
                append("code", "123456")
                append("password", "Pass1234!")
                append("deviceId", "dev_route")
            }
        ) { header(HttpHeaders.Authorization, "Bearer $pureWechatToken") }
        assertEquals(linked.bodyAsText(), HttpStatusCode.OK, linked.status)
        assertEquals(
            "13800138000",
            AccountApiJsonContracts.parseSessionResponse(linked.bodyAsText()).primaryIdentifier?.value
        )
    }

    @Test
    fun testWechatLinkRoutesViaHttp() = testApplication {
        val fakeClient = FakeWechatOAuthClient(
            configured = true,
            exchangeResult = WechatOAuthResult.Success(
                WechatTokenResponse(accessToken = "token_new", openid = "openid_new", unionid = "unionid_new")
            )
        )
        val service = AccountService(
            smsCodeGenerator = { "123456" },
            wechatOAuthClient = fakeClient
        )
        application {
            module(accountService = service)
        }

        // 1. Register phone account
        client.submitForm(
            url = "/account/verification-code",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("purpose", "REGISTER")
            }
        )
        client.submitForm(
            url = "/account/register",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("code", "123456")
                append("password", "Pass1234!")
            }
        )

        // 2. Exchange WeChat code
        val exResponse = client.submitForm(
            url = "/account/wechat/exchange",
            formParameters = Parameters.build { append("code", "good_code") }
        )
        assertEquals(exResponse.bodyAsText(), HttpStatusCode.OK, exResponse.status)
        val exContract = AccountApiJsonContracts.parseWechatExchangeResponse(exResponse.bodyAsText())
        val regReq = exContract.result as WechatAuthResultContract.RegistrationRequired

        // 3. Link via password HTTP endpoint
        val linkResponse = client.submitForm(
            url = "/account/wechat/link/password",
            formParameters = Parameters.build {
                append("wechatTicket", regReq.wechatTicket)
                append("identifier", "13800138000")
                append("password", "Pass1234!")
            }
        )
        assertEquals(linkResponse.bodyAsText(), HttpStatusCode.OK, linkResponse.status)
        val sessionContract = AccountApiJsonContracts.parseSessionResponse(linkResponse.bodyAsText())
        assertEquals("13800138000", sessionContract.primaryIdentifier?.value)
        assertTrue(sessionContract.wechatLinked)
    }

    @Test
    fun testWechatSmsLinkRouteRequiresTicketBoundCode() = testApplication {
        val service = AccountService(
            smsCodeGenerator = { "654321" },
            wechatOAuthClient = FakeWechatOAuthClient(configured = true)
        )
        application {
            module(accountService = service)
        }
        client.submitForm(
            url = "/account/verification-code",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("purpose", "REGISTER")
            }
        )
        client.submitForm(
            url = "/account/register",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("code", "654321")
                append("password", "Pass1234!")
            }
        )
        val exchangeResponse = client.submitForm(
            url = "/account/wechat/exchange",
            formParameters = Parameters.build { append("code", "good_code") }
        )
        val registrationRequired = AccountApiJsonContracts.parseWechatExchangeResponse(exchangeResponse.bodyAsText())
            .result as WechatAuthResultContract.RegistrationRequired
        service.advanceTimeBy(60_001L)

        val smsResponse = client.submitForm(
            url = "/account/verification-code",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("purpose", "WECHAT_LINK")
                append("contextKey", registrationRequired.wechatTicket)
            }
        )
        assertEquals(smsResponse.bodyAsText(), HttpStatusCode.OK, smsResponse.status)
        val linkResponse = client.submitForm(
            url = "/account/wechat/link/code",
            formParameters = Parameters.build {
                append("wechatTicket", registrationRequired.wechatTicket)
                append("identifier", "13800138000")
                append("code", "654321")
            }
        )

        assertEquals(linkResponse.bodyAsText(), HttpStatusCode.OK, linkResponse.status)
        assertTrue(AccountApiJsonContracts.parseSessionResponse(linkResponse.bodyAsText()).wechatLinked)
    }

    @Test
    @Suppress("LongMethod")
    fun testUnifiedIdentifierRoutes() = testApplication {
        val clock = MutableClock()
        val store = InMemoryAccountStore()
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            emailCodeGenerator = { "123456" },
            emailProvider = object : EmailProvider {
                override fun sendCode(email: String, code: String, purpose: String) = EmailProviderResult.Sent
            },
            tokenGenerator = { "token-unified" },
            clock = clock
        )
        application {
            module(accountService = service)
        }

        // 1. Issue a registration code for email.
        val issueRes = client.submitForm(
            url = "/account/verification-code",
            formParameters = Parameters.build {
                append("identifier", "test@example.com")
                append("deviceId", "dev-1")
                append("purpose", "REGISTER")
            }
        )
        assertEquals(HttpStatusCode.OK, issueRes.status)

        // 2. Register and log in through the final unified routes.
        val regRes = client.submitForm(
            url = "/account/register",
            formParameters = Parameters.build {
                append("identifier", "test@example.com")
                append("code", "123456")
                append("password", "Pass1234!")
                append("deviceId", "dev-1")
            }
        )
        assertEquals(HttpStatusCode.OK, regRes.status)
        val regSession = AccountApiJsonContracts.parseSessionResponse(regRes.bodyAsText())
        assertNotNull(regSession.token)

        val loginRes = client.submitForm(
            url = "/account/login",
            formParameters = Parameters.build {
                append("identifier", "test@example.com")
                append("password", "Pass1234!")
                append("deviceId", "dev-1")
            }
        )
        assertEquals(HttpStatusCode.OK, loginRes.status)

        // 3. Prepare issues the identifier-link code and returns a one-time ticket.
        clock.advanceBy(60_001)
        val prepLinkRes = client.submitForm(
            url = "/account/identifier/link/prepare",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("deviceId", "dev-1")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-unified") }
        assertEquals(HttpStatusCode.OK, prepLinkRes.status)

        // 4. Complete requires both the current session and the delivered code.
        val prepContract = AccountApiJsonContracts.parseIdentifierLinkPrepareResponse(prepLinkRes.bodyAsText())
        val ticketIssued = prepContract as com.autoaccounting.api.IdentifierLinkPrepareResponseContract.LinkTicketIssued
        val completeRes = client.submitForm(
            url = "/account/identifier/link/complete",
            formParameters = Parameters.build {
                append("linkTicket", ticketIssued.linkTicket)
                append("code", "123456")
                append("deviceId", "dev-1")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-unified") }
        assertEquals(HttpStatusCode.OK, completeRes.status)

        val linkedSession = AccountApiJsonContracts.parseSessionResponse(completeRes.bodyAsText())
        assertTrue(linkedSession.identifiers.any { it.value == "13800138000" })

        // 5. Verification-code unlink requires the explicitly selected bound identifier.
        val accountId = store.findAccountByIdentifier("EMAIL", "test@example.com")!!.accountId
        store.upsertWechatIdentity(
            StoredWechatIdentity(
                accountId = accountId,
                appId = "wx-test",
                openid = "openid-test",
                createdAtMillis = clock.millis(),
                updatedAtMillis = clock.millis()
            )
        )
        clock.advanceBy(60_001)
        val unlinkCode = client.submitForm(
            url = "/account/verification-code",
            formParameters = Parameters.build {
                append("identifier", "test@example.com")
                append("deviceId", "dev-1")
                append("purpose", "WECHAT_UNLINK")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-unified") }
        assertEquals(HttpStatusCode.OK, unlinkCode.status)

        val missingUnlinkIdentifier = client.submitForm(
            url = "/account/wechat/unlink/code",
            formParameters = Parameters.build {
                append("code", "123456")
                append("deviceId", "dev-1")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-unified") }
        assertEquals(HttpStatusCode.BadRequest, missingUnlinkIdentifier.status)
        assertTrue(missingUnlinkIdentifier.bodyAsText().contains("INVALID_REQUEST"))

        val unlinkResponse = client.submitForm(
            url = "/account/wechat/unlink/code",
            formParameters = Parameters.build {
                append("identifier", "test@example.com")
                append("code", "123456")
                append("deviceId", "dev-1")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-unified") }
        assertEquals(HttpStatusCode.OK, unlinkResponse.status)

        // 6. Removed endpoints stay absent and legacy request fields are rejected.
        val removedRoute = client.submitForm(
            url = "/account/sms",
            formParameters = Parameters.build {
                append("phone", "13900139000")
            }
        )
        assertEquals(HttpStatusCode.NotFound, removedRoute.status)

        val legacyField = client.submitForm(
            url = "/account/login",
            formParameters = Parameters.build {
                append("phone", "test@example.com")
                append("password", "Pass1234!")
            }
        )
        assertEquals(HttpStatusCode.Unauthorized, legacyField.status)
        assertTrue(legacyField.bodyAsText().contains("LOGIN_FAILED"))
    }
}
