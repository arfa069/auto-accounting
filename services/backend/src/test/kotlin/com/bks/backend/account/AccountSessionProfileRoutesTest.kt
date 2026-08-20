package com.bks.backend.account

import com.bks.api.AccountApiJsonContracts
import com.bks.backend.module
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSessionProfileRoutesTest {
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
    fun oversizedFormIsRejectedBeforeAccountProcessing() = testApplication {
        application { module(accountService = AccountService()) }

        val response = client.post("/account/login") {
            header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
            setBody("identifier=${"x".repeat(384 * 1024)}")
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun oversizedFormWithoutContentLengthIsRejected() = testApplication {
        application { module(accountService = AccountService()) }

        val response = client.post("/account/login") {
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = io.ktor.http.ContentType.Application.FormUrlEncoded
                override val contentLength: Long? = null

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    channel.writeFully("identifier=${"x".repeat(384 * 1024)}".toByteArray())
                    channel.flush()
                }
            })
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
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
}
