package com.autoaccounting.backend.ai

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

class AiCategorizationRoutesTest {
    @Test
    fun categorizeReturnsSuggestionContract() = testApplication {
        val aiService = AiCategorizationService()
        val accountService = registeredAccountService()
        application {
            module(accountService = accountService, aiCategorizationService = aiService)
        }

        val response = client.submitForm(
            url = "/ai/categorize",
            formParameters = Parameters.build {
                append("merchantTitle", "午餐")
                append("sourceLabel", "微信")
                append("transactionKind", "支出")
                append("amountMinor", "3590")
                append("categoryCandidates", "餐饮, 购物")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }

        assertEquals(HttpStatusCode.OK, response.status)
        val contract = ApiJsonContracts.parseAiCategorizationResponse(response.bodyAsText())
        assertTrue(contract.ok)
        assertEquals("餐饮", contract.category)
        assertTrue(contract.confidence.isNotBlank())
        assertTrue(contract.explanation.isNotBlank())
    }

    @Test
    fun unauthenticatedCategorizeFailsWithTokenInvalid() = testApplication {
        val aiService = AiCategorizationService()
        val accountService = registeredAccountService()
        application {
            module(accountService = accountService, aiCategorizationService = aiService)
        }

        val response = client.submitForm(
            url = "/ai/categorize",
            formParameters = Parameters.build {
                append("merchantTitle", "午餐")
            }
        )
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("TOKEN_INVALID"))
    }

    @Test
    fun deletionPendingAccountCannotCategorize() = testApplication {
        val aiService = AiCategorizationService()
        val accountService = registeredAccountService()
        accountService.requestAccountDeletion("token-1")
        application {
            module(accountService = accountService, aiCategorizationService = aiService)
        }

        val response = client.submitForm(
            url = "/ai/categorize",
            formParameters = Parameters.build {
                append("merchantTitle", "午餐")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("ACCOUNT_DELETION_PENDING"))
    }

    @Test
    fun enhancedContextPassesNoteAndEvidenceToAiProvider() = testApplication {
        var passedNote: String? = null
        var passedEvidence: String? = null
        val customProvider = object : AiProvider {
            override fun suggest(payload: AiCategorizationPayload): AiCategorizationSuggestion {
                passedNote = payload.note
                passedEvidence = payload.rawEvidenceText
                return AiCategorizationSuggestion("餐饮", "高", "test")
            }
        }
        val aiService = AiCategorizationService(provider = customProvider)
        val accountService = registeredAccountService()
        application {
            module(accountService = accountService, aiCategorizationService = aiService)
        }

        client.submitForm(
            url = "/ai/categorize",
            formParameters = Parameters.build {
                append("merchantTitle", "午餐")
                append("note", "同事AA")
                append("rawEvidenceText", "微信支付通知")
                append("enhancedContext", "true")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }

        assertEquals("同事AA", passedNote)
        assertEquals("微信支付通知", passedEvidence)

        client.submitForm(
            url = "/ai/categorize",
            formParameters = Parameters.build {
                append("merchantTitle", "午餐")
                append("note", "同事AA")
                append("rawEvidenceText", "微信支付通知")
                append("enhancedContext", "false")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }

        assertEquals(null, passedNote)
        assertEquals(null, passedEvidence)
    }

    @Test
    fun categorizeLogsUseAuthenticatedAccountIdAndIgnoreSubmittedPhone() = testApplication {
        val aiService = AiCategorizationService()
        val accountService = registeredAccountService()
        application {
            module(accountService = accountService, aiCategorizationService = aiService)
        }

        val response = client.submitForm(
            url = "/ai/categorize",
            formParameters = Parameters.build {
                append("token", "attacker-token")
                append("accountPhone", "13900139000")
                append("merchantTitle", "merchant")
                append("sourceLabel", "source")
                append("transactionKind", "expense")
                append("amountMinor", "100")
            }
        ) { header(HttpHeaders.Authorization, "Bearer token-1") }

        assertEquals(HttpStatusCode.OK, response.status)
        val logAccountId = aiService.logs.single().accountId
        assertTrue(logAccountId != null && logAccountId > 0L)
    }

    private fun registeredAccountService(token: String = "token-1"): AccountService {
        val service = AccountService(
            smsCodeGenerator = { "123456" },
            tokenGenerator = { token },
            clock = MutableClock(0)
        )
        service.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        service.register("13800138000", "123456", "Aa123456!")
        return service
    }
}
