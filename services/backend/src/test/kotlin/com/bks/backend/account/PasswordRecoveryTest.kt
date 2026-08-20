package com.bks.backend.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordRecoveryTest {

    @Test
    fun rejectsPasswordRecoveryForUsernameIdentifier() {
        val service = AccountService()
        service.registerIdentifier("unameuser", null, "OldPassword123!")

        val res = service.recoverPasswordByIdentifier("unameuser", "123456", "NewPassword123!")
        assertEquals(AccountResult.Failure(AccountError.INVALID_REQUEST), res)
    }

    @Test
    fun recoversPasswordForEmailAndRevokesExistingSessions() {
        val clock = MutableClock(1000)
        val store = InMemoryAccountStore()
        val service = AccountService(
            store = store,
            emailCodeGenerator = { "654321" },
            clock = clock
        )

        service.issueVerificationCode("user@example.com", "device-1", "127.0.0.1", "REGISTER")
        val regRes = service.registerIdentifier("user@example.com", "654321", "OldPassword123!")
        assertTrue(regRes is AccountResult.Success)
        val oldToken = (regRes as AccountResult.Success).value.token
        val secondOldToken = (service.loginIdentifier(
            "user@example.com",
            "OldPassword123!",
            "device-2"
        ) as AccountResult.Success).value.token

        // Verify session valid
        assertTrue(service.verifyToken(oldToken) is AccountResult.Success)

        // Recover password via email code
        clock.advanceBy(60_005)
        val issueRes = service.issueVerificationCode("user@example.com", "device-1", "127.0.0.1", "RECOVERY")
        assertTrue(issueRes is AccountResult.Success)
        val recoverRes = service.recoverPasswordByIdentifier("user@example.com", "654321", "NewPassword123!")
        assertTrue(recoverRes is AccountResult.Success)
        val recoveredToken = (recoverRes as AccountResult.Success).value.token

        // Verify every old session is revoked and the replacement session is valid.
        assertEquals(AccountResult.Failure(AccountError.TOKEN_INVALID), service.verifyToken(oldToken))
        assertEquals(AccountResult.Failure(AccountError.TOKEN_INVALID), service.verifyToken(secondOldToken))
        assertTrue(service.verifyToken(recoveredToken) is AccountResult.Success)

        // Old password failed
        assertEquals(AccountResult.Failure(AccountError.LOGIN_FAILED), service.loginIdentifier("user@example.com", "OldPassword123!"))

        // New password login succeed
        val newLogin = service.loginIdentifier("user@example.com", "NewPassword123!")
        assertTrue(newLogin is AccountResult.Success)
    }
}
