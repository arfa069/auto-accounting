package com.bks.backend.account

import com.bks.api.WechatAuthResultContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatRegisterAndLinkTest {

    @Test
    fun testRegisterWithWechatSuccess() {
        val store = InMemoryAccountStore()
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(store = store, wechatOAuthClient = fakeClient)

        val exchangeRes = service.exchangeWechatCode("good_code") as AccountResult.Success
        val regReq = exchangeRes.value.result as WechatAuthResultContract.RegistrationRequired

        val regResult = service.registerWithWechat(
            wechatTicket = regReq.wechatTicket,
            deviceId = "dev_123",
            ipAddress = "127.0.0.1"
        )
        assertTrue(regResult is AccountResult.Success)

        val token = (regResult as AccountResult.Success).value
        assertNull(token.phone)
        assertTrue(token.wechatLinked)
        assertEquals("微信小张", token.nickname)
        assertEquals("https://example.com/avatar.jpg", token.avatarUrl)

        val verified = service.verifyToken(token.token)
        assertTrue(verified is AccountResult.Success)
        val verifiedToken = (verified as AccountResult.Success).value
        assertTrue(verifiedToken.wechatLinked)
        assertEquals("微信小张", verifiedToken.nickname)
        assertEquals("https://example.com/avatar.jpg", verifiedToken.avatarUrl)

        // Repeat login with same WeChat code returns SignedIn
        val repeatExchange = service.exchangeWechatCode("good_code") as AccountResult.Success
        assertTrue(repeatExchange.value.result is WechatAuthResultContract.SignedIn)
        val signedIn = repeatExchange.value.result as WechatAuthResultContract.SignedIn
        assertNull(signedIn.session.primaryIdentifier)
        assertTrue(signedIn.session.wechatLinked)
        assertEquals("微信小张", signedIn.session.nickname)
    }

    @Test
    fun testLinkWechatWithPasswordSuccess() {
        val store = InMemoryAccountStore()
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            wechatOAuthClient = fakeClient
        )

        service.issueVerificationCode("13800138000", "dev_1", "127.0.0.1")
        val phoneReg = service.registerIdentifier("13800138000", "123456", "Pass1234!", "dev_1", "127.0.0.1")
        assertTrue(phoneReg is AccountResult.Success)

        val exchangeRes = service.exchangeWechatCode("good_code") as AccountResult.Success
        val regReq = exchangeRes.value.result as WechatAuthResultContract.RegistrationRequired

        val linkResult = service.linkWechatWithPassword(
            wechatTicket = regReq.wechatTicket,
            identifier = "13800138000",
            password = "Pass1234!",
            deviceId = "dev_1"
        )
        assertTrue(linkResult is AccountResult.Success)
        val token = (linkResult as AccountResult.Success).value
        assertEquals("13800138000", token.phone)
        assertTrue(token.wechatLinked)
        assertEquals("微信小张", token.nickname)

        val account = store.findAccountByIdentifier("PHONE", "13800138000")!!
        val identity = store.findWechatIdentityByAccountId(account.accountId)
        assertNotNull(identity)
        assertEquals("fake_openid", identity?.openid)
    }

    @Test
    fun testLinkWechatWithPasswordFailures() {
        val store = InMemoryAccountStore()
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            wechatOAuthClient = fakeClient
        )

        service.issueVerificationCode("13800138000", "dev_1", "127.0.0.1")
        service.registerIdentifier("13800138000", "123456", "Pass1234!", "dev_1", "127.0.0.1")

        val exchangeRes = service.exchangeWechatCode("good_code") as AccountResult.Success
        val regReq = exchangeRes.value.result as WechatAuthResultContract.RegistrationRequired

        // Wrong password
        val wrongPwResult = service.linkWechatWithPassword(
            wechatTicket = regReq.wechatTicket,
            identifier = "13800138000",
            password = "WrongPassword1!",
            deviceId = "dev_1"
        )
        assertTrue(wrongPwResult is AccountResult.Failure)
        assertEquals(AccountError.LOGIN_FAILED, (wrongPwResult as AccountResult.Failure).error)

        // Non-existent phone
        val noPhoneResult = service.linkWechatWithPassword(
            wechatTicket = regReq.wechatTicket,
            identifier = "13900000000",
            password = "Pass1234!",
            deviceId = "dev_1"
        )
        assertTrue(noPhoneResult is AccountResult.Failure)
        assertEquals(AccountError.LOGIN_FAILED, (noPhoneResult as AccountResult.Failure).error)

        val accountId = store.findAccountByIdentifier("PHONE", "13800138000")!!.accountId
        val failedCountBeforeInvalidTicket = store.findPasswordCredentialByAccountId(accountId)!!.failedLoginCount
        val credentialBeforeInvalidTicket = store.findPasswordCredentialByAccountId(accountId)!!
        val invalidTicketResult = service.linkWechatWithPassword(
            wechatTicket = "invalid_ticket",
            identifier = "13800138000",
            password = "WrongPassword1!",
            deviceId = "dev_1"
        )
        assertEquals(AccountError.TICKET_EXPIRED, (invalidTicketResult as AccountResult.Failure).error)
        assertEquals(failedCountBeforeInvalidTicket, store.findPasswordCredentialByAccountId(accountId)!!.failedLoginCount)
        assertEquals(credentialBeforeInvalidTicket, store.findPasswordCredentialByAccountId(accountId))
    }

    @Test
    fun testLinkWechatWithPasswordUsesSharedLockoutState() {
        val store = InMemoryAccountStore()
        val clock = MutableClock(1000)
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            clock = clock,
            wechatOAuthClient = fakeClient
        )

        service.issueVerificationCode("13800138000", "dev_1", "127.0.0.1")
        service.registerIdentifier("13800138000", "123456", "Pass1234!", "dev_1", "127.0.0.1")
        val exchangeRes = service.exchangeWechatCode("good_code") as AccountResult.Success
        val ticket = (exchangeRes.value.result as WechatAuthResultContract.RegistrationRequired).wechatTicket
        val accountId = store.findAccountByIdentifier("PHONE", "13800138000")!!.accountId

        clock.advanceBy(1)
        repeat(4) {
            assertEquals(
                AccountResult.Failure(AccountError.LOGIN_FAILED),
                service.linkWechatWithPassword(ticket, "13800138000", "WrongPassword1!", "dev_1")
            )
        }
        assertEquals(4, store.findPasswordCredentialByAccountId(accountId)!!.failedLoginCount)
        assertEquals(
            AccountResult.Failure(AccountError.ACCOUNT_LOCKED),
            service.linkWechatWithPassword(ticket, "13800138000", "WrongPassword1!", "dev_1")
        )
        assertEquals(
            AccountResult.Failure(AccountError.ACCOUNT_LOCKED),
            service.linkWechatWithPassword(ticket, "13800138000", "Pass1234!", "dev_1")
        )

        clock.advanceBy(LOGIN_LOCK_MILLIS + 1)
        val retryExchange = service.exchangeWechatCode("good_code") as AccountResult.Success
        val retryTicket = (retryExchange.value.result as WechatAuthResultContract.RegistrationRequired).wechatTicket
        assertTrue(
            service.linkWechatWithPassword(retryTicket, "13800138000", "Pass1234!", "dev_1") is AccountResult.Success
        )
        val credentialAfterSuccess = store.findPasswordCredentialByAccountId(accountId)!!
        assertEquals(0, credentialAfterSuccess.failedLoginCount)
        assertEquals(0, credentialAfterSuccess.lockedUntilMillis)
        assertEquals(clock.millis(), credentialAfterSuccess.updatedAtMillis)
    }

    @Test
    fun testLinkWechatWithSmsSuccess() {
        val store = InMemoryAccountStore()
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "654321" },
            wechatOAuthClient = fakeClient
        )

        service.issueVerificationCode("13800138000", "dev_1", "127.0.0.1")
        service.registerIdentifier("13800138000", "654321", "Pass1234!", "dev_1", "127.0.0.1")

        val exchangeRes = service.exchangeWechatCode("good_code") as AccountResult.Success
        val regReq = exchangeRes.value.result as WechatAuthResultContract.RegistrationRequired

        service.advanceTimeBy(60_001L)
        service.issueVerificationCode(
            identifier = "13800138000",
            deviceId = "dev_1",
            ipAddress = "127.0.0.1",
            purpose = "WECHAT_LINK",
            contextKey = regReq.wechatTicket
        )
        val linkResult = service.linkWechatWithCode(

            wechatTicket = regReq.wechatTicket,
            identifier = "13800138000",
            code = "654321",
            deviceId = "dev_1"
        )
        assertTrue(linkResult is AccountResult.Success)
        val token = (linkResult as AccountResult.Success).value
        assertEquals("13800138000", token.phone)
        assertTrue(token.wechatLinked)
        assertEquals(
            null,
            store.findVerificationCode("PHONE", "13800138000", "WECHAT_LINK")
        )

        // SMS code was consumed
        val secondTry = service.linkWechatWithCode(
            wechatTicket = regReq.wechatTicket,
            identifier = "13800138000",
            code = "654321",
            deviceId = "dev_1"
        )
        assertTrue(secondTry is AccountResult.Failure)
    }

    @Test
    fun testLinkWechatWithSmsFailures() {
        val store = InMemoryAccountStore()
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "654321" },
            wechatOAuthClient = fakeClient
        )

        service.issueVerificationCode("13800138000", "dev_1", "127.0.0.1")
        service.registerIdentifier("13800138000", "654321", "Pass1234!", "dev_1", "127.0.0.1")

        val exchangeRes = service.exchangeWechatCode("good_code") as AccountResult.Success
        val regReq = exchangeRes.value.result as WechatAuthResultContract.RegistrationRequired

        // Account existence is not disclosed before proving phone control.
        val unregResult = service.linkWechatWithCode(
            wechatTicket = regReq.wechatTicket,
            identifier = "13900000000",
            code = "654321"
        )
        assertTrue(unregResult is AccountResult.Failure)
        assertEquals(AccountError.VERIFICATION_CODE_WRONG, (unregResult as AccountResult.Failure).error)

        // A default-purpose code cannot authorize WeChat linking.
        val defaultCodeResult = service.linkWechatWithCode(
            wechatTicket = regReq.wechatTicket,
            identifier = "13800138000",
            code = "654321"
        )
        assertEquals(AccountError.VERIFICATION_CODE_WRONG, (defaultCodeResult as AccountResult.Failure).error)

        service.advanceTimeBy(60_001L)
        service.issueVerificationCode(
            identifier = "13800138000",
            deviceId = "dev_1",
            ipAddress = "127.0.0.1",
            purpose = "WECHAT_LINK",
            contextKey = regReq.wechatTicket
        )
        val secondExchange = service.exchangeWechatCode("good_code") as AccountResult.Success
        val secondTicket = (secondExchange.value.result as WechatAuthResultContract.RegistrationRequired).wechatTicket
        val wrongTicketResult = service.linkWechatWithCode(
            wechatTicket = secondTicket,
            identifier = "13800138000",
            code = "654321"
        )
        assertEquals(AccountError.VERIFICATION_CODE_WRONG, (wrongTicketResult as AccountResult.Failure).error)
        val wrongCodeResult = service.linkWechatWithCode(
            wechatTicket = regReq.wechatTicket,
            identifier = "13800138000",
            code = "000000"
        )
        assertTrue(wrongCodeResult is AccountResult.Failure)
        assertEquals(AccountError.VERIFICATION_CODE_WRONG, (wrongCodeResult as AccountResult.Failure).error)
    }

    @Test
    fun testTicketExpiredAndAlreadyUsed() {
        val store = InMemoryAccountStore()
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(store = store, wechatOAuthClient = fakeClient)

        val exchangeRes = service.exchangeWechatCode("good_code") as AccountResult.Success
        val regReq = exchangeRes.value.result as WechatAuthResultContract.RegistrationRequired

        // Expired ticket
        service.advanceTimeBy(5 * 60 * 1000L + 1L)
        val expiredResult = service.registerWithWechat(regReq.wechatTicket)
        assertTrue(expiredResult is AccountResult.Failure)
        assertEquals(AccountError.TICKET_EXPIRED, (expiredResult as AccountResult.Failure).error)

        // Fresh exchange and single-use
        val freshExchange = service.exchangeWechatCode("good_code") as AccountResult.Success
        val freshReq = freshExchange.value.result as WechatAuthResultContract.RegistrationRequired

        val reg1 = service.registerWithWechat(freshReq.wechatTicket)
        assertTrue(reg1 is AccountResult.Success)

        // Reuse ticket
        val reg2 = service.registerWithWechat(freshReq.wechatTicket)
        assertTrue(reg2 is AccountResult.Failure)
        assertEquals(AccountError.TICKET_ALREADY_USED, (reg2 as AccountResult.Failure).error)
    }

    @Test
    fun testConflictWhenAccountAlreadyHasWechat() {
        val store = InMemoryAccountStore()
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            wechatOAuthClient = fakeClient
        )

        service.issueVerificationCode("13800138000", "dev_1", "127.0.0.1")
        service.registerIdentifier("13800138000", "123456", "Pass1234!", "dev_1", "127.0.0.1")

        // First WeChat identity link
        val ex1 = service.exchangeWechatCode("good_code") as AccountResult.Success
        val req1 = ex1.value.result as WechatAuthResultContract.RegistrationRequired
        service.linkWechatWithPassword(req1.wechatTicket, "13800138000", "Pass1234!")

        // Second WeChat identity attempt
        val fakeClient2 = FakeWechatOAuthClient(
            configured = true,
            exchangeResult = WechatOAuthResult.Success(
                WechatTokenResponse(accessToken = "token2", openid = "openid2", unionid = "unionid2")
            )
        )
        val service2 = AccountService(store = store, wechatOAuthClient = fakeClient2)
        val ex2 = service2.exchangeWechatCode("good_code") as AccountResult.Success
        val req2 = ex2.value.result as WechatAuthResultContract.RegistrationRequired

        val linkConflict = service.linkWechatWithPassword(req2.wechatTicket, "13800138000", "Pass1234!")
        assertTrue(linkConflict is AccountResult.Failure)
        assertEquals(AccountError.WECHAT_ALREADY_LINKED, (linkConflict as AccountResult.Failure).error)
    }
}
