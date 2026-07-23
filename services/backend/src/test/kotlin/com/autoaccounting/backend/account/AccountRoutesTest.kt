package com.autoaccounting.backend.account

import com.autoaccounting.api.AccountApiJsonContracts
import com.autoaccounting.api.AccountDeletionStatusContract
import com.autoaccounting.api.WechatAuthResultContract
import com.autoaccounting.backend.module
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


class AccountRoutesTest {
    @Test
    fun smsRateLimitUsesObservedRemoteIpAndIgnoresSubmittedIp() = testApplication {
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
                url = "/account/sms",
                formParameters = Parameters.build {
                    append("phone", "1380013800$index")
                    append("deviceId", "device-$index")
                    append("ipAddress", "203.0.113.$index")
                }
            )
            assertEquals(HttpStatusCode.OK, response.status)
        }

        val limited = client.submitForm(
            url = "/account/sms",
            formParameters = Parameters.build {
                append("phone", "13900139000")
                append("deviceId", "device-final")
                append("ipAddress", "198.51.100.10")
            }
        )
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertTrue(limited.bodyAsText().contains("SMS_TOO_FREQUENT"))
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
            url = "/account/sms",
            formParameters = Parameters.build {
                append("phone", "13800138000")
                append("deviceId", "device-a")
            }
        )
        assertEquals(HttpStatusCode.OK, sms.status)
        assertEquals("""{"ok":true}""", sms.bodyAsText())

        val registered = client.submitForm(
            url = "/account/register",
            formParameters = Parameters.build {
                append("phone", "13800138000")
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
                append("phone", "13800138000")
                append("password", "bad")
            }
        )
        assertEquals(HttpStatusCode.Unauthorized, failedLogin.status)
        assertTrue(failedLogin.bodyAsText().contains(""""error":"LOGIN_FAILED""""))

        clock.advanceBy(61_000)
        client.submitForm(
            url = "/account/sms",
            formParameters = Parameters.build {
                append("phone", "13800138000")
                append("deviceId", "device-a")
                append("ipAddress", "127.0.0.2")
            }
        )

        val recovered = client.submitForm(
            url = "/account/recover",
            formParameters = Parameters.build {
                append("phone", "13800138000")
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
        assertEquals("13800138000", verifiedContract.phone)
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
            url = "/account/sms",
            formParameters = Parameters.build {
                append("phone", "13800138000")
                append("deviceId", "device-a")
            }
        )
        client.submitForm(
            url = "/account/register",
            formParameters = Parameters.build {
                append("phone", "13800138000")
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
        assertNull(session1.phone)
        assertTrue(session1.wechatLinked)
        assertEquals("微信小张", session1.nickname)
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
            url = "/account/sms",
            formParameters = Parameters.build { append("phone", "13800138000") }
        )
        client.submitForm(
            url = "/account/register",
            formParameters = Parameters.build {
                append("phone", "13800138000")
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
                append("phone", "13800138000")
                append("password", "Pass1234!")
            }
        )
        assertEquals(linkResponse.bodyAsText(), HttpStatusCode.OK, linkResponse.status)
        val sessionContract = AccountApiJsonContracts.parseSessionResponse(linkResponse.bodyAsText())
        assertEquals("13800138000", sessionContract.phone)
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
            url = "/account/sms",
            formParameters = Parameters.build { append("phone", "13800138000") }
        )
        client.submitForm(
            url = "/account/register",
            formParameters = Parameters.build {
                append("phone", "13800138000")
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
            url = "/account/sms",
            formParameters = Parameters.build {
                append("phone", "13800138000")
                append("purpose", "WECHAT_LINK")
                append("contextKey", registrationRequired.wechatTicket)
            }
        )
        assertEquals(smsResponse.bodyAsText(), HttpStatusCode.OK, smsResponse.status)
        val linkResponse = client.submitForm(
            url = "/account/wechat/link/sms",
            formParameters = Parameters.build {
                append("wechatTicket", registrationRequired.wechatTicket)
                append("phone", "13800138000")
                append("code", "654321")
            }
        )

        assertEquals(linkResponse.bodyAsText(), HttpStatusCode.OK, linkResponse.status)
        assertTrue(AccountApiJsonContracts.parseSessionResponse(linkResponse.bodyAsText()).wechatLinked)
    }

}
