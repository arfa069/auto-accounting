package com.bks.backend.account

import com.bks.api.AccountApiJsonContracts
import com.bks.backend.module
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountIdentifierRoutesTest {
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

        val issueRes = client.submitForm(
            url = "/account/verification-code",
            formParameters = Parameters.build {
                append("identifier", "test@example.com")
                append("deviceId", "dev-1")
                append("purpose", "REGISTER")
            }
        )
        assertEquals(HttpStatusCode.OK, issueRes.status)

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
        assertTrue(regSession.token != null)

        val loginRes = client.submitForm(
            url = "/account/login",
            formParameters = Parameters.build {
                append("identifier", "test@example.com")
                append("password", "Pass1234!")
                append("deviceId", "dev-1")
            }
        )
        assertEquals(HttpStatusCode.OK, loginRes.status)

        clock.advanceBy(60_001)
        val prepLinkRes = client.submitForm(
            url = "/account/identifier/link/prepare",
            formParameters = Parameters.build {
                append("identifier", "13800138000")
                append("deviceId", "dev-1")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-unified") }
        assertEquals(HttpStatusCode.OK, prepLinkRes.status)

        val prepContract = AccountApiJsonContracts.parseIdentifierLinkPrepareResponse(prepLinkRes.bodyAsText())
        val ticketIssued = prepContract as com.bks.api.IdentifierLinkPrepareResponseContract.LinkTicketIssued
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
