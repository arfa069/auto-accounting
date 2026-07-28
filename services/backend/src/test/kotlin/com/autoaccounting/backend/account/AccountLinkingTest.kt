package com.autoaccounting.backend.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountLinkingTest {

    private val testEmailProvider = object : EmailProvider {
        override fun sendCode(email: String, code: String, purpose: String): EmailProviderResult = EmailProviderResult.Sent
    }

    @Test
    fun rejectsPreparingLinkForUsernameIdentifier() {
        val service = AccountService()
        val reg = service.registerIdentifier("user_one", null, "Password123!")
        val token = (reg as AccountResult.Success).value.token

        val linkRes = service.prepareIdentifierLink(token, "another_username")
        assertEquals(AccountResult.Failure(AccountError.INVALID_REQUEST), linkRes)
    }

    @Test
    fun preparesAndConfirmsEmailLinkingSuccessfully() {
        val clock = MutableClock(1000)
        val store = InMemoryAccountStore()
        val service = AccountService(
            store = store,
            emailProvider = testEmailProvider,
            emailCodeGenerator = { "654321" },
            clock = clock
        )

        // Account 1 (Username only)
        val reg1 = service.registerIdentifier("user_primary", null, "Password123!")
        val token1 = (reg1 as AccountResult.Success).value.token

        // Prepare link test@example.com
        clock.advanceBy(60_001)
        val prepRes = service.prepareIdentifierLink(token1, "test@example.com", "device-1")
        assertTrue(prepRes is AccountResult.Success)
        val linkTicket = ((prepRes as AccountResult.Success).value as com.autoaccounting.api.IdentifierLinkPrepareResponseContract.LinkTicketIssued).linkTicket

        // Confirm link with email code
        val confirmRes = service.confirmIdentifierLink(token1, linkTicket, "654321", "device-1")
        assertTrue(confirmRes is AccountResult.Success)

        // Login using newly linked email
        val emailLogin = service.loginIdentifier("test@example.com", "Password123!")
        assertTrue(emailLogin is AccountResult.Success)
        assertEquals((reg1 as AccountResult.Success).value.accountId, (emailLogin as AccountResult.Success).value.accountId)
    }

    @Test
    fun rejectsConflictWhenIdentifierBelongsToAnotherAccount() {
        val store = InMemoryAccountStore()
        val service = AccountService(store = store, emailProvider = testEmailProvider, emailCodeGenerator = { "654321" })

        // Account 1 (test1@example.com)
        service.issueVerificationCode("test1@example.com", "dev1", "127.0.0.1", "REGISTER")
        service.registerIdentifier("test1@example.com", "654321", "Password123!")

        // Account 2 (user_two)
        val reg2 = service.registerIdentifier("user_two", null, "Password123!")
        val token2 = (reg2 as AccountResult.Success).value.token

        // Account 2 tries to link test1@example.com
        val prepRes = service.prepareIdentifierLink(token2, "test1@example.com")
        assertEquals(AccountResult.Failure(AccountError.IDENTIFIER_CONFLICT), prepRes)
    }

    @Test
    fun preparingExactIdentifierAlreadyOnCurrentAccountIsIdempotent() {
        val service = AccountService(smsCodeGenerator = { "123456" })
        service.issueVerificationCode("13800138000", "device-1", "127.0.0.1", "REGISTER")
        val registered = service.registerIdentifier(
            "13800138000",
            "123456",
            "Password123!"
        ) as AccountResult.Success

        assertEquals(
            AccountResult.Success(com.autoaccounting.api.IdentifierLinkPrepareResponseContract.AlreadyLinked),
            service.prepareIdentifierLink(registered.value.token, "13800138000")
        )
    }

    @Test
    fun replacesExistingEmailAfterVerifyingNewAddress() {
        val clock = MutableClock(1_000)
        val service = AccountService(
            emailProvider = testEmailProvider,
            emailCodeGenerator = { "654321" },
            clock = clock
        )
        val registered = service.registerIdentifier(
            "profile_owner",
            null,
            "Password123!"
        ) as AccountResult.Success

        val firstPreparation = service.prepareIdentifierLink(
            registered.value.token,
            "old@example.com"
        ) as AccountResult.Success
        val firstTicket = (
            firstPreparation.value as com.autoaccounting.api.IdentifierLinkPrepareResponseContract.LinkTicketIssued
        ).linkTicket
        val firstLinked = service.confirmIdentifierLink(
            registered.value.token,
            firstTicket,
            "654321"
        ) as AccountResult.Success

        clock.advanceBy(60_001)
        assertEquals(
            AccountResult.Failure(AccountError.IDENTIFIER_ALREADY_LINKED),
            service.prepareIdentifierLink(firstLinked.value.token, "new@example.com")
        )
        val replacementResult = service.prepareIdentifierLink(
            bearerToken = firstLinked.value.token,
            identifier = "new@example.com",
            replaceExisting = true
        )
        assertTrue(replacementResult.toString(), replacementResult is AccountResult.Success)
        val replacementPreparation = replacementResult as AccountResult.Success
        val replacementTicket = (
            replacementPreparation.value as com.autoaccounting.api.IdentifierLinkPrepareResponseContract.LinkTicketIssued
        ).linkTicket
        val replaced = service.confirmIdentifierLink(
            firstLinked.value.token,
            replacementTicket,
            "654321"
        ) as AccountResult.Success

        assertTrue(replaced.value.identifiers.any { it.value == "new@example.com" })
        assertTrue(replaced.value.identifiers.none { it.value == "old@example.com" })
        assertTrue(service.loginIdentifier("new@example.com", "Password123!") is AccountResult.Success)
        assertEquals(
            AccountResult.Failure(AccountError.LOGIN_FAILED),
            service.loginIdentifier("old@example.com", "Password123!")
        )
    }

    @Test
    fun pureWechatAccountRequiresPasswordWhenLinkingFirstIdentifier() {
        val service = AccountService(
            smsCodeGenerator = { "123456" },
            wechatOAuthClient = FakeWechatOAuthClient(configured = true)
        )
        val exchange = service.exchangeWechatCode("good_code") as AccountResult.Success
        val registration = exchange.value.result as com.autoaccounting.api.WechatAuthResultContract.RegistrationRequired
        val wechatSession = service.registerWithWechat(registration.wechatTicket) as AccountResult.Success

        val prepared = service.prepareIdentifierLink(
            bearerToken = wechatSession.value.token,
            identifier = "13800138000",
            deviceId = "device-1"
        ) as AccountResult.Success
        val ticket = (prepared.value as com.autoaccounting.api.IdentifierLinkPrepareResponseContract.LinkTicketIssued)
            .linkTicket

        assertEquals(
            AccountResult.Failure(AccountError.INVALID_REQUEST),
            service.confirmIdentifierLink(wechatSession.value.token, ticket, "123456")
        )
        assertEquals(
            AccountResult.Failure(AccountError.INVALID_REQUEST),
            service.confirmIdentifierLink(
                bearerToken = wechatSession.value.token,
                linkTicket = ticket,
                code = "123456",
                password = "weak"
            )
        )

        val linked = service.confirmIdentifierLink(
            bearerToken = wechatSession.value.token,
            linkTicket = ticket,
            code = "123456",
            password = "Password123!"
        )
        assertTrue(linked is AccountResult.Success)
        assertTrue(service.loginIdentifier("13800138000", "Password123!") is AccountResult.Success)
    }

    @Test
    fun identifierLinkTicketExpiresAndCannotBeReplayed() {
        val clock = MutableClock(0)
        val service = AccountService(
            smsCodeGenerator = { "123456" },
            clock = clock
        )
        val registered = service.registerIdentifier("user_primary", null, "Password123!") as AccountResult.Success

        val expiredPreparation = service.prepareIdentifierLink(
            registered.value.token,
            "13800138000",
            "device-1"
        ) as AccountResult.Success
        val expiredTicket = (
            expiredPreparation.value as com.autoaccounting.api.IdentifierLinkPrepareResponseContract.LinkTicketIssued
        ).linkTicket
        clock.advanceBy(com.autoaccounting.api.TICKET_VALIDITY_MILLIS + 1)

        assertEquals(
            AccountResult.Failure(AccountError.TICKET_EXPIRED),
            service.confirmIdentifierLink(registered.value.token, expiredTicket, "123456", "device-1")
        )

        val validPreparation = service.prepareIdentifierLink(
            registered.value.token,
            "13800138000",
            "device-1"
        ) as AccountResult.Success
        val validTicket = (
            validPreparation.value as com.autoaccounting.api.IdentifierLinkPrepareResponseContract.LinkTicketIssued
        ).linkTicket
        val linked = service.confirmIdentifierLink(
            registered.value.token,
            validTicket,
            "123456",
            "device-1"
        ) as AccountResult.Success

        assertEquals(
            AccountResult.Failure(AccountError.TICKET_ALREADY_USED),
            service.confirmIdentifierLink(linked.value.token, validTicket, "123456", "device-1")
        )
    }

}
