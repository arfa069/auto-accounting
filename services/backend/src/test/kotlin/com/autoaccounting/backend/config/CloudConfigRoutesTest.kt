package com.autoaccounting.backend.config

import com.autoaccounting.api.ApiJsonContracts
import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.MutableClock
import com.autoaccounting.backend.module
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
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

        accountService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        accountService.registerIdentifier("13800138000", "123456", "Aa123456!")

        val response = client.submitForm(
            url = "/account/cloud-config/read",
            formParameters = Parameters.Empty
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }

        assertEquals(HttpStatusCode.OK, response.status)
        val contract = ApiJsonContracts.parseCloudConfigResponse(response.bodyAsText())
        assertTrue(contract.ok)
        assertTrue(!contract.aiConsentGranted)
        assertTrue(!contract.enhancedContextGranted)
        assertEquals(emptyMap<String, Boolean>(), contract.featureFlags)
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

        accountService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        accountService.registerIdentifier("13800138000", "123456", "Aa123456!")

        val writeResponse = client.submitForm(
            url = "/account/cloud-config/write",
            formParameters = Parameters.build {
                append("aiConsentGranted", "true")
                append("enhancedContextGranted", "true")
                append("featureFlags", ApiJsonContracts.encodeFeatureFlags(mapOf("beta" to true)))
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }
        assertEquals(HttpStatusCode.OK, writeResponse.status)
        val written = ApiJsonContracts.parseCloudConfigResponse(writeResponse.bodyAsText())
        assertTrue(written.aiConsentGranted)
        assertTrue(written.enhancedContextGranted)
        assertEquals(mapOf("beta" to true), written.featureFlags)

        val readResponse = client.submitForm(
            url = "/account/cloud-config/read",
            formParameters = Parameters.Empty
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }
        assertEquals(HttpStatusCode.OK, readResponse.status)
        val contract = ApiJsonContracts.parseCloudConfigResponse(readResponse.bodyAsText())
        assertTrue(contract.aiConsentGranted)
        assertTrue(contract.enhancedContextGranted)
        assertEquals(mapOf("beta" to true), contract.featureFlags)
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

        accountService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        accountService.registerIdentifier("13800138000", "123456", "Aa123456!")
        accountService.requestAccountDeletion("token-1")

        val response = client.submitForm(
            url = "/account/cloud-config/write",
            formParameters = Parameters.build {
                append("aiConsentGranted", "true")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("ACCOUNT_DELETION_PENDING"))
    }

    @Test
    fun partialWritePreservesExistingFields() = testApplication {
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

        accountService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        accountService.registerIdentifier("13800138000", "123456", "Aa123456!")

        client.submitForm(
            url = "/account/cloud-config/write",
            formParameters = Parameters.build {
                append("aiConsentGranted", "true")
                append("enhancedContextGranted", "true")
                append("featureFlags", ApiJsonContracts.encodeFeatureFlags(mapOf("beta" to true)))
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }

        val partialWrite = client.submitForm(
            url = "/account/cloud-config/write",
            formParameters = Parameters.build {
                append("aiConsentGranted", "false")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }

        assertEquals(HttpStatusCode.OK, partialWrite.status)

        val readResponse = client.submitForm(
            url = "/account/cloud-config/read",
            formParameters = Parameters.Empty
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }
        val contract = ApiJsonContracts.parseCloudConfigResponse(readResponse.bodyAsText())
        assertTrue(!contract.aiConsentGranted)
        assertTrue(!contract.enhancedContextGranted)
        assertEquals(mapOf("beta" to true), contract.featureFlags)
    }

    @Test
    fun invalidFeatureFlagsJsonReturnsBadRequest() = testApplication {
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

        accountService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        accountService.registerIdentifier("13800138000", "123456", "Aa123456!")

        val response = client.submitForm(
            url = "/account/cloud-config/write",
            formParameters = Parameters.build {
                append("featureFlags", """{"beta":"yes"}""")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("INVALID_REQUEST"))
    }
}
