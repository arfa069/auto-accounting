package com.autoaccounting.backend.account

import com.autoaccounting.api.AccountApiJsonContracts
import com.autoaccounting.api.PhoneLinkPrepareResponseContract
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLinkTest {

    companion object {
        private const val VALID_PHONE = "13800138000"
        private const val ANOTHER_PHONE = "13900139000"
        private const val STRONG_PASSWORD = "Password123!"
        private const val WEAK_PASSWORD = "weak"

        private fun createTestEnv(store: AccountStore = InMemoryAccountStore()): TestEnv {
            var codeCounter = 100000
            val fakeOAuthClient = FakeWechatOAuthClient(configured = true)
            val service = AccountService(
                store = store,
                smsCodeGenerator = { (codeCounter++).toString() },
                verificationCodeHasher = VerificationCodeHasher.forTests(),
                wechatOAuthClient = fakeOAuthClient
            )
            return TestEnv(service, fakeOAuthClient)
        }

        private fun h2DatabaseUrl(): String {
            return "jdbc:h2:mem:phonelink_${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }

        private data class TestEnv(
            val service: AccountService,
            val oauthClient: FakeWechatOAuthClient
        )
    }

    private fun createWechatAccount(service: AccountService, codeSuffix: String = "1"): AccountToken {
        val exchangeResult = service.exchangeWechatCode("good_code_$codeSuffix")
        assertTrue(exchangeResult is AccountResult.Success)
        val regRequired = (exchangeResult as AccountResult.Success).value.result as com.autoaccounting.api.WechatAuthResultContract.RegistrationRequired
        val regResult = service.registerWithWechat(regRequired.wechatTicket, deviceId = "dev1")
        assertTrue(regResult is AccountResult.Success)
        return (regResult as AccountResult.Success).value
    }

    @Test
    fun `pure wechat account can link unregistered phone successfully`() {
        val env = createTestEnv()
        val service = env.service
        val wechatToken = createWechatAccount(service, "openid_link_success")

        val issueSmsResult = service.issueSmsCode(VALID_PHONE, deviceId = "dev1", ipAddress = "127.0.0.1", purpose = "PHONE_LINK")
        assertTrue(issueSmsResult is AccountResult.Success)

        val prepareResult = service.preparePhoneLink(wechatToken.token, VALID_PHONE, "100000")
        assertTrue(prepareResult is AccountResult.Success)
        val issuedContract = (prepareResult as AccountResult.Success).value as PhoneLinkPrepareResponseContract.PhoneTicketIssued
        assertTrue(issuedContract.phoneTicket.isNotBlank())

        val completeResult = service.completePhoneLink(
            bearerToken = wechatToken.token,
            phoneTicket = issuedContract.phoneTicket,
            password = STRONG_PASSWORD,
            deviceId = "dev1"
        )
        assertTrue(completeResult is AccountResult.Success)
        val newSessionToken = (completeResult as AccountResult.Success).value

        assertEquals(VALID_PHONE, newSessionToken.phone)
        assertTrue(newSessionToken.wechatLinked)
        assertTrue(newSessionToken.token.isNotBlank())

        val oldVerify = service.verifyToken(wechatToken.token)
        assertEquals(AccountError.TOKEN_INVALID, oldVerify.error)

        val newVerify = service.verifyToken(newSessionToken.token)
        assertTrue(newVerify is AccountResult.Success)
        assertEquals(VALID_PHONE, (newVerify as AccountResult.Success).value.phone)
        assertTrue((newVerify as AccountResult.Success).value.wechatLinked)
    }

    @Test
    fun `prepare phone link fails when sms verification code is wrong without leaking phone registration status`() {
        val env = createTestEnv()
        val service = env.service
        val wechatToken = createWechatAccount(service, "openid_leak_test")

        service.issueSmsCode(ANOTHER_PHONE, deviceId = "dev2", ipAddress = "127.0.0.1")
        service.register(ANOTHER_PHONE, "100000", STRONG_PASSWORD, deviceId = "dev2")

        service.advanceTimeBy(60_001L)
        val issueResult = service.issueSmsCode(ANOTHER_PHONE, deviceId = "dev1", ipAddress = "127.0.0.1", purpose = "PHONE_LINK")
        assertTrue(issueResult is AccountResult.Success)

        val prepareResultWrongCode = service.preparePhoneLink(wechatToken.token, ANOTHER_PHONE, "999999")
        assertEquals(AccountError.VERIFICATION_CODE_WRONG, prepareResultWrongCode.error)
    }

    @Test
    fun `prepare phone link returns merge required when target phone belongs to another account`() {
        val env = createTestEnv()
        val service = env.service
        val wechatToken = createWechatAccount(service, "openid_merge_req")

        service.issueSmsCode(ANOTHER_PHONE, deviceId = "dev2", ipAddress = "127.0.0.1")
        val existingUserToken = (service.register(ANOTHER_PHONE, "100000", STRONG_PASSWORD, deviceId = "dev2") as AccountResult.Success).value
        assertNotNull(existingUserToken)

        service.advanceTimeBy(60_001L)
        val issueResult = service.issueSmsCode(ANOTHER_PHONE, deviceId = "dev1", ipAddress = "127.0.0.1", purpose = "PHONE_LINK")
        assertTrue(issueResult is AccountResult.Success)

        val prepareResult = service.preparePhoneLink(wechatToken.token, ANOTHER_PHONE, "100001")
        assertTrue(prepareResult is AccountResult.Success)
        val mergeContract = (prepareResult as AccountResult.Success).value as PhoneLinkPrepareResponseContract.MergeRequired
        assertEquals(ANOTHER_PHONE, mergeContract.sourcePhone)
        assertTrue(mergeContract.mergeTicket.isNotBlank())
    }

    @Test
    fun `complete phone link rejects weak password`() {
        val env = createTestEnv()
        val service = env.service
        val wechatToken = createWechatAccount(service, "openid_weak_pwd")

        service.issueSmsCode(VALID_PHONE, deviceId = "dev1", ipAddress = "127.0.0.1", purpose = "PHONE_LINK")
        val prepareResult = service.preparePhoneLink(wechatToken.token, VALID_PHONE, "100000")
        val ticket = ((prepareResult as AccountResult.Success).value as PhoneLinkPrepareResponseContract.PhoneTicketIssued).phoneTicket

        val completeResult = service.completePhoneLink(wechatToken.token, ticket, WEAK_PASSWORD)
        assertEquals(AccountError.INVALID_REQUEST, completeResult.error)
    }

    @Test
    fun `complete phone link rejects already used ticket`() {
        val env = createTestEnv()
        val service = env.service
        val wechatToken = createWechatAccount(service, "openid_reuse_ticket")

        service.issueSmsCode(VALID_PHONE, deviceId = "dev1", ipAddress = "127.0.0.1", purpose = "PHONE_LINK")
        val prepareResult = service.preparePhoneLink(wechatToken.token, VALID_PHONE, "100000")
        val ticket = ((prepareResult as AccountResult.Success).value as PhoneLinkPrepareResponseContract.PhoneTicketIssued).phoneTicket

        val completeResult1 = service.completePhoneLink(wechatToken.token, ticket, STRONG_PASSWORD)
        assertTrue(completeResult1 is AccountResult.Success)

        val completeResult2 = service.completePhoneLink((completeResult1 as AccountResult.Success).value.token, ticket, STRONG_PASSWORD)
        assertEquals(AccountError.TICKET_ALREADY_USED, completeResult2.error)
    }

    @Test
    fun `account with phone already linked cannot prepare phone link again`() {
        val env = createTestEnv()
        val service = env.service

        service.issueSmsCode(VALID_PHONE, deviceId = "dev1", ipAddress = "127.0.0.1")
        val phoneUserToken = (service.register(VALID_PHONE, "100000", STRONG_PASSWORD, deviceId = "dev1") as AccountResult.Success).value

        service.advanceTimeBy(60_001L)
        service.issueSmsCode(ANOTHER_PHONE, deviceId = "dev1", ipAddress = "127.0.0.1", purpose = "PHONE_LINK")
        val prepareResult = service.preparePhoneLink(phoneUserToken.token, ANOTHER_PHONE, "100001")
        assertEquals(AccountError.PHONE_ALREADY_LINKED, prepareResult.error)
    }

    @Test
    fun `concurrent registration makes complete phone link fail with phone already registered`() {
        val env = createTestEnv()
        val service = env.service
        val wechatToken = createWechatAccount(service, "openid_concurrent")

        service.issueSmsCode(VALID_PHONE, deviceId = "dev1", ipAddress = "127.0.0.1", purpose = "PHONE_LINK")
        val prepareResult = service.preparePhoneLink(wechatToken.token, VALID_PHONE, "100000")
        val ticket = ((prepareResult as AccountResult.Success).value as PhoneLinkPrepareResponseContract.PhoneTicketIssued).phoneTicket

        service.advanceTimeBy(60_001L)
        service.issueSmsCode(VALID_PHONE, deviceId = "dev2", ipAddress = "127.0.0.1")
        val regRes = service.register(VALID_PHONE, "100001", STRONG_PASSWORD, deviceId = "dev2")
        assertTrue(regRes is AccountResult.Success)

        val completeResult = service.completePhoneLink(wechatToken.token, ticket, STRONG_PASSWORD)
        assertEquals(AccountError.PHONE_ALREADY_REGISTERED, completeResult.error)
    }

    @Test
    fun `phone link test on JDBC H2 persistence`() {
        val dbUrl = h2DatabaseUrl()
        val store = JdbcAccountStore(dbUrl)
        val env = createTestEnv(store)
        val service = env.service

        val wechatToken = createWechatAccount(service, "openid_jdbc_link")

        service.issueSmsCode(VALID_PHONE, deviceId = "dev1", ipAddress = "127.0.0.1", purpose = "PHONE_LINK")
        val prepareResult = service.preparePhoneLink(wechatToken.token, VALID_PHONE, "100000")
        assertTrue(prepareResult is AccountResult.Success)
        val ticket = ((prepareResult as AccountResult.Success).value as PhoneLinkPrepareResponseContract.PhoneTicketIssued).phoneTicket

        val completeResult = service.completePhoneLink(wechatToken.token, ticket, STRONG_PASSWORD, deviceId = "dev1")
        assertTrue(completeResult is AccountResult.Success)
        val newToken = (completeResult as AccountResult.Success).value

        assertEquals(VALID_PHONE, newToken.phone)
        assertTrue(newToken.wechatLinked)

        val verifyRes = service.verifyToken(newToken.token)
        assertTrue(verifyRes is AccountResult.Success)
        assertEquals(VALID_PHONE, (verifyRes as AccountResult.Success).value.phone)
    }

    @Test
    fun `ktor http end to end phone link prepare and complete`() {
        val env = createTestEnv()
        val service = env.service
        val wechatToken = createWechatAccount(service, "openid_ktor_http")

        service.issueSmsCode(VALID_PHONE, deviceId = "dev1", ipAddress = "127.0.0.1", purpose = "PHONE_LINK")

        testApplication {
            routing {
                accountRoutes(service)
            }

            val prepareResponse = client.submitForm(
                url = "/account/phone/link/prepare",
                formParameters = io.ktor.http.Parameters.build {
                    append("phone", VALID_PHONE)
                    append("code", "100000")
                }
            ) {
                header(HttpHeaders.Authorization, "Bearer ${wechatToken.token}")
            }
            assertEquals(HttpStatusCode.OK, prepareResponse.status)
            val prepareBody = prepareResponse.bodyAsText()
            val prepareContract = AccountApiJsonContracts.parsePhoneLinkPrepareResponse(prepareBody)
            assertTrue(prepareContract is PhoneLinkPrepareResponseContract.PhoneTicketIssued)
            val ticket = (prepareContract as PhoneLinkPrepareResponseContract.PhoneTicketIssued).phoneTicket

            val completeResponse = client.submitForm(
                url = "/account/phone/link/complete",
                formParameters = io.ktor.http.Parameters.build {
                    append("phoneTicket", ticket)
                    append("password", STRONG_PASSWORD)
                    append("deviceId", "dev1")
                }
            ) {
                header(HttpHeaders.Authorization, "Bearer ${wechatToken.token}")
            }
            assertEquals(HttpStatusCode.OK, completeResponse.status)
            val completeBody = completeResponse.bodyAsText()
            val sessionContract = AccountApiJsonContracts.parseSessionResponse(completeBody)

            assertEquals(VALID_PHONE, sessionContract.phone)
            assertTrue(sessionContract.wechatLinked)
            assertNotNull(sessionContract.token)
        }
    }
}
