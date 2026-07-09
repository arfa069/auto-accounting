package com.autoaccounting.backend.config

import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.MutableClock
import com.autoaccounting.backend.module
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConfigRoutesTest {
    @Test
    fun readReturnsDefaultConfigForNewUser() = testApplication {
        val accountService = AccountService(
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "token-1" },
            clock = MutableClock(0)
        )
        application {
            module(
                accountService = accountService,
                cloudConfigService = CloudConfigService(accountService = accountService)
            )
        }

        accountService.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        accountService.register("13800138000", "123456", "Aa123456!")

        val response = client.submitForm(
            url = "/account/cloud-config/read",
            formParameters = Parameters.build {
                append("token", "token-1")
            }
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains(""""ok":true"""))
        assertTrue(body.contains(""""aiConsentGranted":false"""))
        assertTrue(body.contains(""""enhancedContextGranted":false"""))
    }

    @Test
    fun writeAndReadRoundTripsConfig() = testApplication {
        val accountService = AccountService(
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "token-1" },
            clock = MutableClock(0)
        )
        application {
            module(
                accountService = accountService,
                cloudConfigService = CloudConfigService(accountService = accountService)
            )
        }

        accountService.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        accountService.register("13800138000", "123456", "Aa123456!")

        val writeResponse = client.submitForm(
            url = "/account/cloud-config/write",
            formParameters = Parameters.build {
                append("token", "token-1")
                append("aiConsentGranted", "true")
                append("enhancedContextGranted", "true")
                append("featureFlags", """{"beta":true}""")
            }
        )
        assertEquals(HttpStatusCode.OK, writeResponse.status)
        assertEquals("""{"ok":true}""", writeResponse.bodyAsText())

        val readResponse = client.submitForm(
            url = "/account/cloud-config/read",
            formParameters = Parameters.build {
                append("token", "token-1")
            }
        )
        assertEquals(HttpStatusCode.OK, readResponse.status)
        val body = readResponse.bodyAsText()
        assertTrue(body.contains(""""aiConsentGranted":true"""))
        assertTrue(body.contains(""""enhancedContextGranted":true"""))
        assertTrue(body.contains(""""beta":true"""))
    }

    @Test
    fun unauthenticatedRequestsFailWithTokenInvalid() = testApplication {
        val accountService = AccountService(
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "token-1" },
            clock = MutableClock(0)
        )
        application {
            module(
                accountService = accountService,
                cloudConfigService = CloudConfigService(accountService = accountService)
            )
        }

        val readResponse = client.submitForm(
            url = "/account/cloud-config/read",
            formParameters = Parameters.build {
                append("token", "bad-token")
            }
        )
        assertEquals(HttpStatusCode.Unauthorized, readResponse.status)
        assertTrue(readResponse.bodyAsText().contains("TOKEN_INVALID"))

        val writeResponse = client.submitForm(
            url = "/account/cloud-config/write",
            formParameters = Parameters.build {}
        )
        assertEquals(HttpStatusCode.Unauthorized, writeResponse.status)
        assertTrue(writeResponse.bodyAsText().contains("TOKEN_INVALID"))
    }

    @Test
    fun deletionPendingAccountCannotWriteConfig() = testApplication {
        val accountService = AccountService(
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "token-1" },
            clock = MutableClock(0)
        )
        application {
            module(
                accountService = accountService,
                cloudConfigService = CloudConfigService(accountService = accountService)
            )
        }

        accountService.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        accountService.register("13800138000", "123456", "Aa123456!")
        accountService.requestAccountDeletion("13800138000")

        val response = client.submitForm(
            url = "/account/cloud-config/write",
            formParameters = Parameters.build {
                append("token", "token-1")
                append("aiConsentGranted", "true")
            }
        )

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("ACCOUNT_DELETION_PENDING"))
    }
}
