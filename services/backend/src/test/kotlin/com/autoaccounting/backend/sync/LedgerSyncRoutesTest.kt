package com.autoaccounting.backend.sync

import com.autoaccounting.api.AccountApiJsonContracts
import com.autoaccounting.api.LedgerSyncInitializeRequestContract
import com.autoaccounting.api.LedgerSyncJsonContracts
import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.MutableClock
import com.autoaccounting.backend.module
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LedgerSyncRoutesTest {
    @Test
    fun initializeRequiresVerifiedSession() = testApplication {
        val accountService = registeredAccountService()
        application { module(accountService = accountService) }

        val response = client.post("/account/ledger-sync/initialize") {
            contentType(ContentType.Application.Json)
            setBody(LedgerSyncJsonContracts.encodeInitializeRequest(LedgerSyncInitializeRequestContract("device-a")))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("TOKEN_INVALID", AccountApiJsonContracts.parseErrorResponse(response.bodyAsText()).error)
    }

    @Test
    fun oversizedRequestUsesStableErrorWithoutEchoingBodyOrToken() = testApplication {
        val accountService = registeredAccountService()
        application { module(accountService = accountService) }
        val secretMarker = "private-ledger-marker"
        val oversized = "{\"deviceId\":\"$secretMarker${"x".repeat(1024 * 1024)}\"}"

        val response = client.post("/account/ledger-sync/initialize") {
            header(HttpHeaders.Authorization, "Bearer session-secret")
            contentType(ContentType.Application.Json)
            setBody(oversized)
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        val body = response.bodyAsText()
        assertEquals("SYNC_PAYLOAD_TOO_LARGE", AccountApiJsonContracts.parseErrorResponse(body).error)
        assertFalse(body.contains(secretMarker))
        assertFalse(body.contains("session-secret"))
    }

    @Test
    fun deletionCoolingOffAllowsReadButRejectsWrite() = testApplication {
        val accountService = registeredAccountService()
        val syncService = LedgerSyncService(accountService = accountService)
        accountService.requestAccountDeletion("session-secret")
        application { module(accountService = accountService, ledgerSyncService = syncService) }

        val initialize = client.post("/account/ledger-sync/initialize") {
            header(HttpHeaders.Authorization, "Bearer session-secret")
            contentType(ContentType.Application.Json)
            setBody(LedgerSyncJsonContracts.encodeInitializeRequest(LedgerSyncInitializeRequestContract("device-a")))
        }
        val push = client.post("/account/ledger-sync/push") {
            header(HttpHeaders.Authorization, "Bearer session-secret")
            contentType(ContentType.Application.Json)
            setBody("""{"deviceId":"device-a","mutations":[]}""")
        }

        assertEquals(HttpStatusCode.OK, initialize.status)
        assertEquals(HttpStatusCode.Conflict, push.status)
        assertEquals("ACCOUNT_DELETION_PENDING", AccountApiJsonContracts.parseErrorResponse(push.bodyAsText()).error)
    }

    private fun registeredAccountService(): AccountService {
        val service = AccountService(
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "session-secret" },
            clock = MutableClock(0)
        )
        service.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        service.registerIdentifier("13800138000", "123456", "Aa123456!")
        return service
    }
}
