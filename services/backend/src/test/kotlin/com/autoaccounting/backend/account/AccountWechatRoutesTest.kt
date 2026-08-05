package com.autoaccounting.backend.account

import com.autoaccounting.api.AccountApiJsonContracts
import com.autoaccounting.api.WechatAuthResultContract
import com.autoaccounting.backend.module
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountWechatRoutesTest {
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

        val exResponse = client.submitForm(
            url = "/account/wechat/exchange",
            formParameters = Parameters.build { append("code", "good_code") }
        )
        assertEquals(exResponse.bodyAsText(), HttpStatusCode.OK, exResponse.status)
        val exContract = AccountApiJsonContracts.parseWechatExchangeResponse(exResponse.bodyAsText())
        val regReq = exContract.result as WechatAuthResultContract.RegistrationRequired

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

        val exResponse = client.submitForm(
            url = "/account/wechat/exchange",
            formParameters = Parameters.build { append("code", "good_code") }
        )
        assertEquals(exResponse.bodyAsText(), HttpStatusCode.OK, exResponse.status)
        val exContract = AccountApiJsonContracts.parseWechatExchangeResponse(exResponse.bodyAsText())
        val regReq = exContract.result as WechatAuthResultContract.RegistrationRequired

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
}
