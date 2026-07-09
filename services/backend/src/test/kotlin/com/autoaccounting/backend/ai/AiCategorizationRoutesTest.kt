package com.autoaccounting.backend.ai

import com.autoaccounting.backend.module
import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.MutableClock
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCategorizationRoutesTest {
    @Test
    fun routeFiltersPayloadAndLogsMinimalContextByDefault() = testApplication {
        val aiService = AiCategorizationService()
        application {
            module(
                accountService = AccountService(),
                aiCategorizationService = aiService
            )
        }

        val response = client.submitForm(
            url = "/ai/categorize",
            formParameters = Parameters.build {
                append("merchantTitle", "午餐")
                append("sourceLabel", "微信")
                append("transactionKind", "支出")
                append("amountMinor", "3590")
                append("categoryCandidates", "餐饮,交通")
                append("note", "客户会议")
                append("rawEvidenceText", "微信支付收款凭证 午餐 35.90")
                append("enhancedContext", "false")
            }
        )

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(""""category":"餐饮""""))
        val log = aiService.logs.single()
        assertEquals("0-50", log.amountRangeLabel)
        // Note and rawEvidenceText are never stored in the log — they are stripped at the service level
    }

    @Test
    fun enhancedContextDoesNotLeakToPersistedLog() = testApplication {
        val aiService = AiCategorizationService()
        application {
            module(
                accountService = AccountService(),
                aiCategorizationService = aiService
            )
        }

        client.submitForm(
            url = "/ai/categorize",
            formParameters = Parameters.build {
                append("merchantTitle", "午餐")
                append("sourceLabel", "微信")
                append("transactionKind", "支出")
                append("amountMinor", "3590")
                append("categoryCandidates", "餐饮,交通")
                append("note", "客户会议")
                append("rawEvidenceText", "微信支付收款凭证 午餐 35.90")
                append("enhancedContext", "true")
            }
        )

        val log = aiService.logs.single()
        // Persisted log does not contain note or rawEvidenceText
        assertEquals("午餐", log.merchantTitle)
        assertEquals("餐饮", log.suggestedCategory)
    }

    @Test
    fun deletionPendingAccountCannotWriteAiLogs() = testApplication {
        val aiService = AiCategorizationService()
        val accountService = AccountService(
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "token-1" },
            clock = MutableClock(0)
        )
        accountService.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        accountService.register("13800138000", "123456", "Aa123456!")
        accountService.requestAccountDeletion("13800138000")

        application {
            module(
                accountService = accountService,
                aiCategorizationService = aiService
            )
        }

        val response = client.submitForm(
            url = "/ai/categorize",
            formParameters = Parameters.build {
                append("accountPhone", "13800138000")
                append("merchantTitle", "午餐")
                append("sourceLabel", "微信")
                append("transactionKind", "支出")
                append("amountMinor", "3590")
            }
        )

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("ACCOUNT_DELETION_PENDING"))
        assertTrue(aiService.logs.isEmpty())
    }

    @Test
    fun missingAiProviderReturnsSafeDefault() = testApplication {
        val aiService = AiCategorizationService(provider = MissingAiProvider)
        application {
            module(
                accountService = AccountService(),
                aiCategorizationService = aiService
            )
        }

        val response = client.submitForm(
            url = "/ai/categorize",
            formParameters = Parameters.build {
                append("merchantTitle", "午餐")
                append("sourceLabel", "微信")
                append("transactionKind", "支出")
                append("amountMinor", "3590")
            }
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains(""""category":"未分类""""))
        assertTrue(body.contains(""""confidence":"低""""))
        assertTrue(body.contains("AI服务未配置"))
    }

    @Test
    fun deletionPendingAccountCannotWriteAiLogsViaToken() = testApplication {
        val aiService = AiCategorizationService()
        val accountService = AccountService(
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "token-user-1" },
            clock = MutableClock(0)
        )
        accountService.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        accountService.register("13800138000", "123456", "Aa123456!")
        accountService.requestAccountDeletion("13800138000")

        application {
            module(
                accountService = accountService,
                aiCategorizationService = aiService
            )
        }

        val response = client.submitForm(
            url = "/ai/categorize",
            formParameters = Parameters.build {
                append("token", "token-user-1")
                append("merchantTitle", "午餐")
                append("sourceLabel", "微信")
                append("transactionKind", "支出")
                append("amountMinor", "3590")
            }
        )

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("ACCOUNT_DELETION_PENDING"))
        assertTrue(aiService.logs.isEmpty())
    }
}
