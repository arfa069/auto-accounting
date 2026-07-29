package com.autoaccounting.backend.ai

import com.autoaccounting.api.ApiJsonContracts
import com.autoaccounting.backend.account.AccountResult
import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.AccountToken
import com.autoaccounting.backend.account.MutableClock
import com.autoaccounting.backend.config.CloudConfigService
import com.autoaccounting.backend.config.InMemoryCloudConfigStore
import com.autoaccounting.backend.config.StoredCloudConfig
import com.autoaccounting.backend.module
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCategorizationRoutesTest {
    @Test
    fun categorizeUsesRangeContractAndReturnsSharedResponse() = testApplication {
        val harness = harness(aiConsent = true)
        application {
            module(
                accountService = harness.accountService,
                aiCategorizationService = harness.aiService,
                cloudConfigService = harness.cloudConfigService
            )
        }

        val response = client.submitForm(
            url = "/ai/categorize",
            formParameters = validForm()
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }

        assertEquals(HttpStatusCode.OK, response.status)
        val contract = ApiJsonContracts.parseAiCategorizationResponse(response.bodyAsText())
        assertEquals("餐饮", contract.category)
        assertEquals("0-50", harness.provider.lastPayload?.amountRangeLabel)
        assertEquals(listOf("餐饮", "交通"), harness.provider.lastPayload?.categoryCandidates)
        assertFalse(harness.provider.lastPayload.toString().contains("3590"))
        assertEquals(1, harness.aiService.logs.size)
    }

    @Test
    fun unauthenticatedCategorizeFailsBeforeProvider() = testApplication {
        val harness = harness(aiConsent = true)
        application {
            module(
                accountService = harness.accountService,
                aiCategorizationService = harness.aiService,
                cloudConfigService = harness.cloudConfigService
            )
        }

        val response = client.submitForm("/ai/categorize", validForm())

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("TOKEN_INVALID"))
        assertEquals(0, harness.provider.calls)
        assertTrue(harness.aiService.logs.isEmpty())
    }

    @Test
    fun accountWithoutStoredConsentCannotCallProvider() = testApplication {
        val harness = harness(aiConsent = false)
        application {
            module(
                accountService = harness.accountService,
                aiCategorizationService = harness.aiService,
                cloudConfigService = harness.cloudConfigService
            )
        }

        val response = client.submitForm("/ai/categorize", validForm()) {
            header(HttpHeaders.Authorization, "Bearer token-1")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("AI_CONSENT_REQUIRED"))
        assertEquals(0, harness.provider.calls)
        assertTrue(harness.aiService.logs.isEmpty())
    }

    @Test
    fun deletionPendingAccountCannotCallProviderOrWriteLog() = testApplication {
        val harness = harness(aiConsent = true)
        harness.accountService.requestAccountDeletion("token-1")
        application {
            module(
                accountService = harness.accountService,
                aiCategorizationService = harness.aiService,
                cloudConfigService = harness.cloudConfigService
            )
        }

        val response = client.submitForm("/ai/categorize", validForm()) {
            header(HttpHeaders.Authorization, "Bearer token-1")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("ACCOUNT_DELETION_PENDING"))
        assertEquals(0, harness.provider.calls)
        assertTrue(harness.aiService.logs.isEmpty())
    }

    @Test
    fun enhancedContextRequiresSeparateStoredAuthorizationAndIsNotLogged() = testApplication {
        val harness = harness(aiConsent = true, enhancedContext = false)
        application {
            module(
                accountService = harness.accountService,
                aiCategorizationService = harness.aiService,
                cloudConfigService = harness.cloudConfigService
            )
        }
        val enhancedForm = validForm(
            mapOf(
                "enhancedContext" to "true",
                "note" to "同事聚餐",
                "rawEvidenceText" to "付款通知"
            )
        )

        val denied = client.submitForm("/ai/categorize", enhancedForm) {
            header(HttpHeaders.Authorization, "Bearer token-1")
        }

        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertTrue(denied.bodyAsText().contains("ENHANCED_CONTEXT_NOT_AUTHORIZED"))
        assertEquals(0, harness.provider.calls)

        harness.cloudConfigService.writeConfig(
            StoredCloudConfig(
                accountId = harness.accountId,
                aiConsentGranted = true,
                enhancedContextGranted = true,
                updatedAtMillis = 2
            )
        )
        val allowed = client.submitForm("/ai/categorize", enhancedForm) {
            header(HttpHeaders.Authorization, "Bearer token-1")
        }

        assertEquals(HttpStatusCode.OK, allowed.status)
        assertEquals("同事聚餐", harness.provider.lastPayload?.note)
        assertEquals("付款通知", harness.provider.lastPayload?.rawEvidenceText)
        val stored = harness.aiService.logs.single().toString()
        assertFalse(stored.contains("同事聚餐"))
        assertFalse(stored.contains("付款通知"))
    }

    @Test
    fun providerFailureReturnsStableErrorAndDoesNotWriteLog() = testApplication {
        val harness = harness(
            aiConsent = true,
            provider = RecordingProvider(failure = AiProviderException.RateLimited)
        )
        application {
            module(
                accountService = harness.accountService,
                aiCategorizationService = harness.aiService,
                cloudConfigService = harness.cloudConfigService
            )
        }

        val response = client.submitForm("/ai/categorize", validForm()) {
            header(HttpHeaders.Authorization, "Bearer token-1")
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("PROVIDER_RATE_LIMITED"))
        assertFalse(body.contains("upstream"))
        assertTrue(harness.aiService.logs.isEmpty())
    }

    @Test
    fun submittedIdentityFieldsCannotChangeLogOwner() = testApplication {
        val harness = harness(aiConsent = true)
        application {
            module(
                accountService = harness.accountService,
                aiCategorizationService = harness.aiService,
                cloudConfigService = harness.cloudConfigService
            )
        }
        val form = validForm(
            mapOf(
                "token" to "attacker-token",
                "accountPhone" to "13900139000"
            )
        )

        val response = client.submitForm("/ai/categorize", form) {
            header(HttpHeaders.Authorization, "Bearer token-1")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(harness.accountId, harness.aiService.logs.single().accountId)
    }

    @Test
    fun malformedCandidateJsonReturnsBadRequestWithoutProvider() = testApplication {
        val harness = harness(aiConsent = true)
        application {
            module(
                accountService = harness.accountService,
                aiCategorizationService = harness.aiService,
                cloudConfigService = harness.cloudConfigService
            )
        }
        val form = validForm(mapOf("categoryCandidates" to "[invalid"))

        val response = client.submitForm("/ai/categorize", form) {
            header(HttpHeaders.Authorization, "Bearer token-1")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, harness.provider.calls)
    }

    private fun validForm(overrides: Map<String, String> = emptyMap()): Parameters {
        val values = linkedMapOf(
            "merchantTitle" to "午餐",
            "sourceLabel" to "微信",
            "transactionKind" to "支出",
            "amountRangeLabel" to "0-50",
            "categoryCandidates" to ApiJsonContracts.encodeAiCategoryCandidates(
                listOf("餐饮", "交通")
            ),
            "enhancedContext" to "false"
        ).apply { putAll(overrides) }
        return Parameters.build {
            values.forEach { (name, value) -> append(name, value) }
        }
    }

    private fun harness(
        aiConsent: Boolean,
        enhancedContext: Boolean = false,
        provider: RecordingProvider = RecordingProvider()
    ): Harness {
        val accountService = AccountService(
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "token-1" },
            clock = MutableClock(0)
        )
        accountService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        val registration = accountService.registerIdentifier(
            "13800138000",
            "123456",
            "Aa123456!"
        ) as AccountResult.Success<AccountToken>
        val cloudConfigService = CloudConfigService(
            store = InMemoryCloudConfigStore(),
            accountService = accountService
        )
        cloudConfigService.writeConfig(
            StoredCloudConfig(
                accountId = registration.value.accountId,
                aiConsentGranted = aiConsent,
                enhancedContextGranted = enhancedContext,
                updatedAtMillis = 1
            )
        )
        return Harness(
            accountId = registration.value.accountId,
            accountService = accountService,
            cloudConfigService = cloudConfigService,
            aiService = AiCategorizationService(provider),
            provider = provider
        )
    }

    private data class Harness(
        val accountId: Long,
        val accountService: AccountService,
        val cloudConfigService: CloudConfigService,
        val aiService: AiCategorizationService,
        val provider: RecordingProvider
    )

    private class RecordingProvider(
        private val failure: AiProviderException? = null
    ) : AiProvider {
        var calls = 0
        var lastPayload: AiCategorizationPayload? = null

        override suspend fun suggest(payload: AiCategorizationPayload): AiCategorizationSuggestion {
            calls += 1
            lastPayload = payload
            failure?.let { throw it }
            return AiCategorizationSuggestion("餐饮", "高", "测试建议")
        }
    }
}
