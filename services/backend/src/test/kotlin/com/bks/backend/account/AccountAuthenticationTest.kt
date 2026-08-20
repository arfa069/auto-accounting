package com.bks.backend.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountAuthenticationTest {

    @Test
    fun usernameRegistrationWithoutCodeAndCaseInsensitiveLogin() {
        val clock = MutableClock(1000)
        val store = InMemoryAccountStore()
        val service = AccountService(store = store, clock = clock)

        // 1. Register with username "User_123"
        val regRes = service.registerIdentifier("User_123", code = null, password = "Password123!")
        assertTrue("Username registration should succeed without code", regRes is AccountResult.Success)
        val token = (regRes as AccountResult.Success).value

        // 2. Login with different case "user_123"
        val loginRes = service.loginIdentifier("user_123", "Password123!")
        assertTrue("Case insensitive login should succeed", loginRes is AccountResult.Success)
        assertEquals(token.accountId, (loginRes as AccountResult.Success).value.accountId)

        // 3. Duplicate registration with lower case should fail
        val dupRes = service.registerIdentifier("user_123", code = null, password = "Password123!")
        assertEquals(AccountResult.Failure(AccountError.IDENTIFIER_ALREADY_REGISTERED), dupRes)
    }

    @Test
    fun emailAndPhoneRegistrationAndLogin() {
        val clock = MutableClock(1000)
        val store = InMemoryAccountStore()
        val service = AccountService(
            store = store,
            emailCodeGenerator = { "654321" },
            smsCodeGenerator = { "123456" },
            clock = clock
        )

        // Email registration
        service.issueVerificationCode("test@example.com", "device-email", "127.0.0.1", "REGISTER")
        val emailReg = service.registerIdentifier("Test@Example.com", "654321", "Password123!")
        assertTrue(emailReg is AccountResult.Success)

        val emailLogin = service.loginIdentifier("test@example.com", "Password123!")
        assertTrue(emailLogin is AccountResult.Success)

        // Phone registration
        clock.advanceBy(60_000)
        service.issueVerificationCode("13800138000", "device-phone", "127.0.0.1", "REGISTER")
        val phoneReg = service.registerIdentifier("13800138000", "123456", "Password123!")
        assertTrue(phoneReg is AccountResult.Success)

        val phoneLogin = service.loginIdentifier("13800138000", "Password123!")
        assertTrue(phoneLogin is AccountResult.Success)
    }

    @Test
    fun enforcesLoginFailureLockout() {
        val clock = MutableClock(1000)
        val store = InMemoryAccountStore()
        val service = AccountService(store = store, clock = clock)

        service.registerIdentifier("lockuser", null, "Password123!")
        clock.advanceBy(1)

        // 4 failed attempts
        repeat(4) {
            val res = service.loginIdentifier("lockuser", "WrongPass123!")
            assertEquals(AccountResult.Failure(AccountError.LOGIN_FAILED), res)
        }
        val afterFourthFailure = store.findPasswordCredentialByAccountId(1L)!!
        assertEquals(4, afterFourthFailure.failedLoginCount)
        assertEquals(1001, afterFourthFailure.updatedAtMillis)

        // 5th failed attempt triggers lock
        val fifthRes = service.loginIdentifier("lockuser", "WrongPass123!")
        assertEquals(AccountResult.Failure(AccountError.ACCOUNT_LOCKED), fifthRes)

        // Subsequent attempt within 15 minutes is locked even with correct password
        val lockedRes = service.loginIdentifier("lockuser", "Password123!")
        assertEquals(AccountResult.Failure(AccountError.ACCOUNT_LOCKED), lockedRes)

        // Advance 15 minutes
        clock.advanceBy(15 * 60 * 1000L + 1)
        val unlockedRes = service.loginIdentifier("lockuser", "Password123!")
        assertTrue(unlockedRes is AccountResult.Success)
        val afterSuccessfulLogin = store.findPasswordCredentialByAccountId(1L)!!
        assertEquals(0, afterSuccessfulLogin.failedLoginCount)
        assertEquals(0, afterSuccessfulLogin.lockedUntilMillis)
        assertEquals(clock.millis(), afterSuccessfulLogin.updatedAtMillis)
    }

    @Test
    fun loginFailuresAcrossBoundAliasesShareOneLockoutCounter() {
        val clock = MutableClock(1000)
        val service = AccountService(
            emailProvider = NoopEmailProvider,
            emailCodeGenerator = { "654321" },
            clock = clock
        )
        val registered = service.registerIdentifier("lock_user", null, "Password123!") as AccountResult.Success
        val prepared = service.prepareIdentifierLink(
            registered.value.token,
            "lock@example.com",
            "device-1"
        ) as AccountResult.Success
        val ticket = (prepared.value as com.bks.api.IdentifierLinkPrepareResponseContract.LinkTicketIssued)
            .linkTicket
        assertTrue(
            service.confirmIdentifierLink(registered.value.token, ticket, "654321") is AccountResult.Success
        )

        repeat(4) { index ->
            val identifier = if (index % 2 == 0) "lock_user" else "lock@example.com"
            assertEquals(
                AccountResult.Failure(AccountError.LOGIN_FAILED),
                service.loginIdentifier(identifier, "WrongPass123!")
            )
        }
        assertEquals(
            AccountResult.Failure(AccountError.ACCOUNT_LOCKED),
            service.loginIdentifier("lock@example.com", "WrongPass123!")
        )
        assertEquals(
            AccountResult.Failure(AccountError.ACCOUNT_LOCKED),
            service.loginIdentifier("lock_user", "Password123!")
        )
    }
}
