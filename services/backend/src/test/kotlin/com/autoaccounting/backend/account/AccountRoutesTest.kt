package com.autoaccounting.backend.account

import com.autoaccounting.backend.module
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRoutesTest {
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
            formParameters = Parameters.build {
                append("token", "token-1")
            }
        )
        assertEquals(HttpStatusCode.OK, verified.status)
        assertEquals("""{"ok":true,"phone":"13800138000","token":"token-1"}""", verified.bodyAsText())
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
                append("phone", "13800138000")
            }
        )
        assertEquals(HttpStatusCode.OK, requested.status)
        assertEquals(
            """{"ok":true,"phone":"13800138000","requestedAtMillis":0,"finalDeletionAtMillis":604800000}""",
            requested.bodyAsText()
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
                append("token", "token-1")
                append("aiConsentGranted", "true")
            }
        )
        assertEquals(HttpStatusCode.Conflict, configWrite.status)
        assertTrue(configWrite.bodyAsText().contains("ACCOUNT_DELETION_PENDING"))

        val canceled = client.submitForm(
            url = "/account/delete/cancel",
            formParameters = Parameters.build {
                append("phone", "13800138000")
            }
        )
        assertEquals(HttpStatusCode.OK, canceled.status)
        assertEquals("""{"ok":true}""", canceled.bodyAsText())
    }
}
