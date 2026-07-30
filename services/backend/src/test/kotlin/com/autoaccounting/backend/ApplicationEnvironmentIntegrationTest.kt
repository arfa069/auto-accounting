package com.autoaccounting.backend

import com.autoaccounting.api.ApiJsonContracts
import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.JdbcAccountStore
import com.autoaccounting.backend.account.MutableClock
import com.autoaccounting.backend.ai.JdbcAiCategorizationLogStore
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

class ApplicationEnvironmentIntegrationTest {
    @Test
    fun moduleDefaultsUseEnvironmentBackedAiAndCloudServices() {
        val databaseUrl = h2DatabaseUrl()
        val env = mapOf(
            "AUTO_ACCOUNTING_DATABASE_URL" to databaseUrl,
            "AUTO_ACCOUNTING_AI_PROTOCOL" to "unknown-protocol"
        )
        val clock = MutableClock(0)

        testApplication {
            val accountService = accountService(databaseUrl, clock)
            application {
                module(env = env, accountService = accountService)
            }

            accountService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
            accountService.registerIdentifier("13800138000", "123456", "Aa123456!")

            val writeConfigResponse = client.submitForm(
                url = "/account/cloud-config/write",
                formParameters = Parameters.build {
                    append("aiConsentGranted", "true")
                    append("enhancedContextGranted", "true")
                    append("featureFlags", ApiJsonContracts.encodeFeatureFlags(mapOf("beta" to true)))
                }
            ) { header(HttpHeaders.Authorization, "Bearer token-1") }

            val aiResponse = client.submitForm(
                url = "/ai/categorize",
                formParameters = Parameters.build {
                    append("merchantTitle", "午餐")
                    append("sourceLabel", "微信")
                    append("transactionKind", "支出")
                    append("amountRangeLabel", "0-50")
                    append(
                        "categoryCandidates",
                        ApiJsonContracts.encodeAiCategoryCandidates(listOf("餐饮", "交通"))
                    )
                }
            ) { header(HttpHeaders.Authorization, "Bearer token-1") }

            assertEquals(HttpStatusCode.OK, writeConfigResponse.status)
            assertEquals(HttpStatusCode.ServiceUnavailable, aiResponse.status)
            val aiError = ApiJsonContracts.parseAiCategorizationError(aiResponse.bodyAsText())
            assertEquals("PROVIDER_CONFIGURATION_INVALID", aiError.error)
        }

        testApplication {
            val restartedAccountService = accountService(databaseUrl, clock)
            application {
                module(env = env, accountService = restartedAccountService)
            }

            val readConfigResponse = client.submitForm(
                url = "/account/cloud-config/read",
                formParameters = Parameters.Empty
            ) { header(HttpHeaders.Authorization, "Bearer token-1") }

            assertEquals(HttpStatusCode.OK, readConfigResponse.status)
            val configContract = ApiJsonContracts.parseCloudConfigResponse(readConfigResponse.bodyAsText())
            assertTrue(configContract.aiConsentGranted)
            assertTrue(configContract.enhancedContextGranted)
            assertEquals(mapOf("beta" to true), configContract.featureFlags)
        }

        assertEquals(0, JdbcAiCategorizationLogStore(databaseUrl).allLogs().size)
    }

    private fun accountService(databaseUrl: String, clock: MutableClock): AccountService {
        return AccountService(
            store = JdbcAccountStore(databaseUrl),
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "token-1" },
            verificationCodeHasher = com.autoaccounting.backend.account.VerificationCodeHasher.forTests(),
            clock = clock
        )
    }

    private fun h2DatabaseUrl(): String {
        return "jdbc:h2:mem:${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    }
}
