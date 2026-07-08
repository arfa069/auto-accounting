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
    fun registerLoginAndRecoveryReturnStableJsonContracts() = testApplication {
        application {
            module(
                accountService = AccountService(
                    smsCodeGenerator = { "123456" },
                    tokenGenerator = { "token-1" }
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
        assertEquals("""{"ok":false,"error":"LOGIN_FAILED","message":"手机号或密码不正确"}""", failedLogin.bodyAsText())

        val recovered = client.submitForm(
            url = "/account/recover",
            formParameters = Parameters.build {
                append("phone", "13800138000")
                append("code", "123456")
                append("password", "Bb123456!")
            }
        )
        assertEquals(HttpStatusCode.OK, recovered.status)
        assertTrue(recovered.bodyAsText().contains(""""token":"token-1""""))
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

        val configWrite = client.submitForm(
            url = "/account/cloud-config",
            formParameters = Parameters.build {
                append("phone", "13800138000")
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
