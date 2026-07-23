package com.autoaccounting.backend.account

import com.autoaccounting.api.AccountApiJsonContracts
import com.autoaccounting.api.WechatAuthResultContract
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatUnlinkTest {
    private fun createClock() = MutableClock(1774262400000L)

    private fun createService(
        store: AccountStore,
        clock: MutableClock,
        tokenGenerator: () -> String = { "token-${System.nanoTime()}" }
    ): AccountService {
        return AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            tokenGenerator = tokenGenerator,
            clock = clock,
            wechatOAuthClient = FakeWechatOAuthClient(
                userInfoResult = WechatOAuthResult.Success(
                    WechatUserInfoResponse(
                        openid = "unlink-openid",
                        nickname = "待解绑用户",
                        avatarUrl = "https://example.com/unlink.jpg",
                        unionid = "unlink-unionid"
                    )
                )
            )
        )
    }

    private fun registerAndLink(
        service: AccountService,
        clock: MutableClock,
        phone: String = PHONE
    ): Pair<AccountToken, AccountToken> {
        assertTrue(service.issueSmsCode(phone, "device-old", "127.0.0.1") is AccountResult.Success)
        val registered = service.register(phone, "123456", PASSWORD, "device-old") as AccountResult.Success
        clock.advanceBy(60_001)
        val exchange = service.exchangeWechatCode(
            code = "wechat-code",
            bearerToken = registered.value.token,
            deviceId = "device-old"
        ) as AccountResult.Success
        val linked = (exchange.value.result as WechatAuthResultContract.SignedIn).session
        return registered.value to AccountToken(
            accountId = registered.value.accountId,
            phone = linked.phone,
            token = requireNotNull(linked.token),
            wechatLinked = linked.wechatLinked,
            nickname = linked.nickname,
            avatarUrl = linked.avatarUrl
        )
    }

    @Test
    fun passwordUnlinkRemovesWechatAndRotatesEverySession() {
        val store = InMemoryAccountStore()
        val clock = createClock()
        val service = createService(store, clock)
        val (oldPhoneSession, linkedSession) = registerAndLink(service, clock)

        val result = service.unlinkWechatWithPassword(
            bearerToken = linkedSession.token,
            password = PASSWORD,
            deviceId = "device-new"
        )

        assertTrue(result is AccountResult.Success)
        val unlinked = (result as AccountResult.Success).value
        assertNotEquals(linkedSession.token, unlinked.token)
        assertEquals(PHONE, unlinked.phone)
        assertFalse(unlinked.wechatLinked)
        assertTrue(service.verifyToken(oldPhoneSession.token) is AccountResult.Failure)
        assertTrue(service.verifyToken(linkedSession.token) is AccountResult.Failure)
        assertTrue(service.verifyToken(unlinked.token) is AccountResult.Success)
        assertEquals(null, store.findWechatIdentityByAccountId(unlinked.accountId))

        val phoneLogin = service.login(PHONE, PASSWORD, "device-phone")
        assertTrue(phoneLogin is AccountResult.Success)
        val wechatExchange = service.exchangeWechatCode("wechat-code", null, "device-wechat") as AccountResult.Success
        assertTrue(wechatExchange.value.result is WechatAuthResultContract.RegistrationRequired)
    }

    @Test
    fun smsUnlinkRequiresBoundPurposeAndConsumesCode() {
        val store = InMemoryAccountStore()
        val clock = createClock()
        val service = createService(store, clock)
        val (_, linkedSession) = registerAndLink(service, clock)

        val issue = service.issueSmsCode(
            phone = PHONE,
            deviceId = "device-new",
            ipAddress = "127.0.0.1",
            purpose = "WECHAT_UNLINK",
            bearerToken = linkedSession.token
        )
        assertTrue(issue is AccountResult.Success)

        val result = service.unlinkWechatWithSms(
            bearerToken = linkedSession.token,
            code = "123456",
            deviceId = "device-new"
        )
        assertTrue(result is AccountResult.Success)
        assertFalse((result as AccountResult.Success).value.wechatLinked)
        assertEquals(null, store.findSmsCode(PHONE))
    }

    @Test
    fun unlinkRejectsLastMethodWrongCredentialAndDeletionCoolingOff() {
        val store = InMemoryAccountStore()
        val clock = createClock()
        val service = createService(store, clock)

        val exchange = service.exchangeWechatCode("wechat-code", null, "wechat-only") as AccountResult.Success
        val ticket = (exchange.value.result as WechatAuthResultContract.RegistrationRequired).wechatTicket
        val wechatOnly = (service.registerWithWechat(ticket, "wechat-only") as AccountResult.Success).value
        assertEquals(
            AccountError.LAST_LOGIN_METHOD_CANNOT_UNLINK,
            (service.unlinkWechatWithPassword(wechatOnly.token, PASSWORD) as AccountResult.Failure).error
        )

        val secondStore = InMemoryAccountStore()
        val linkedService = createService(secondStore, clock)
        val (_, linkedSession) = registerAndLink(linkedService, clock, "13900139000")
        assertEquals(
            AccountError.LOGIN_FAILED,
            (linkedService.unlinkWechatWithPassword(linkedSession.token, "Wrong123456!") as AccountResult.Failure).error
        )
        assertNotNull(secondStore.findWechatIdentityByAccountId(linkedSession.accountId))
        assertTrue(linkedService.requestAccountDeletion(linkedSession.token) is AccountResult.Success)
        assertEquals(
            AccountError.ACCOUNT_DELETION_PENDING,
            (linkedService.unlinkWechatWithPassword(linkedSession.token, PASSWORD) as AccountResult.Failure).error
        )
        assertNotNull(secondStore.findWechatIdentityByAccountId(linkedSession.accountId))
    }

    @Test
    fun jdbcFailureWhileIssuingReplacementSessionRollsBackUnlink() {
        val databaseUrl = "jdbc:h2:mem:wechat_unlink_${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        val store = JdbcAccountStore(databaseUrl)
        val clock = createClock()
        val setupService = createService(store, clock)
        val (_, linkedSession) = registerAndLink(setupService, clock)
        val failingService = createService(store, clock) { error("simulated token persistence failure") }

        val failure = runCatching {
            failingService.unlinkWechatWithPassword(linkedSession.token, PASSWORD, "device-new")
        }

        assertTrue(failure.isFailure)
        assertNotNull(store.findWechatIdentityByAccountId(linkedSession.accountId))
        assertTrue(setupService.verifyToken(linkedSession.token) is AccountResult.Success)
    }

    @Test
    fun passwordUnlinkRouteReturnsReplacementSession() = testApplication {
        val store = InMemoryAccountStore()
        val clock = createClock()
        val service = createService(store, clock)
        val (_, linkedSession) = registerAndLink(service, clock)
        application { module(accountService = service) }

        val response = client.submitForm(
            url = "/account/wechat/unlink/password",
            formParameters = Parameters.build {
                append("password", PASSWORD)
                append("deviceId", "route-device")
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer ${linkedSession.token}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val session = AccountApiJsonContracts.parseSessionResponse(response.bodyAsText())
        assertEquals(PHONE, session.phone)
        assertFalse(session.wechatLinked)
        assertNotNull(session.token)
    }

    private companion object {
        const val PHONE = "13800138000"
        const val PASSWORD = "Pass123456!"
    }
}
