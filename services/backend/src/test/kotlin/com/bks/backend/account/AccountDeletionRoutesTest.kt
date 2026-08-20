package com.bks.backend.account

import com.bks.api.AccountApiJsonContracts
import com.bks.api.AccountDeletionStatusContract
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

class AccountDeletionRoutesTest {
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
}
