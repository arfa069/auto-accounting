package com.autoaccounting.backend.account

import com.autoaccounting.backend.ai.AiCategorizationService
import com.autoaccounting.backend.config.CloudConfigService
import com.autoaccounting.backend.module
import com.autoaccounting.backend.sync.LedgerSyncService
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRateLimitRoutesTest {
    @Test
    fun smsRateLimitUsesObservedRemoteIpAndIgnoresUntrustedIpOverrides() = testApplication {
        application {
            module(
                accountService = AccountService(
                    smsCodeGenerator = { "123456" },
                    tokenGenerator = { "token-1" },
                    clock = MutableClock(0)
                )
            )
        }

        repeat(5) { index ->
            val response = client.submitForm(
                url = "/account/verification-code",
                formParameters = Parameters.build {
                    append("identifier", "1380013800$index")
                    append("deviceId", "device-$index")
                    append("ipAddress", "203.0.113.$index")
                    append("purpose", "REGISTER")
                }
            ) {
                header("X-Forwarded-For", "203.0.113.${index + 20}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        val limited = client.submitForm(
            url = "/account/verification-code",
            formParameters = Parameters.build {
                append("identifier", "13900139000")
                append("deviceId", "device-final")
                append("ipAddress", "198.51.100.10")
                append("purpose", "REGISTER")
            }
        ) {
            header("X-Forwarded-For", "198.51.100.20")
        }
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertTrue(limited.bodyAsText().contains("SMS_TOO_FREQUENT"))
    }

    @Test
    fun trustedProxyHeadersUseOriginalRemoteIpForRateLimit() = testApplication {
        val accountService = AccountService(
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "token-1" },
            clock = MutableClock(0)
        )
        application {
            module(
                env = mapOf(
                    "AUTO_ACCOUNTING_HOST" to "127.0.0.1",
                    "AUTO_ACCOUNTING_TRUST_PROXY_HEADERS" to "true"
                ),
                accountService = accountService,
                aiCategorizationService = AiCategorizationService(),
                cloudConfigService = CloudConfigService(accountService = accountService),
                ledgerSyncService = LedgerSyncService(accountService = accountService)
            )
        }

        repeat(6) { index ->
            val response = client.submitForm(
                url = "/account/verification-code",
                formParameters = Parameters.build {
                    append("identifier", "1380013800$index")
                    append("deviceId", "device-$index")
                    append("purpose", "REGISTER")
                }
            ) {
                header("X-Forwarded-For", "192.168.1.${index + 20}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }
}
