package com.bks.backend.account
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationCodeServiceTest {

    private class RecordingSmsProvider : SmsProvider {
        val sentCodes = mutableListOf<Pair<String, String>>()
        var failureError: AccountError? = null

        override fun sendCode(phone: String, code: String): SmsProviderResult {
            failureError?.let { return SmsProviderResult.Failed(it) }
            sentCodes += phone to code
            return SmsProviderResult.Sent
        }
    }

    private class RecordingEmailProvider : EmailProvider {
        val sentCodes = mutableListOf<Triple<String, String, String>>()
        var failureError: AccountError? = null

        override fun sendCode(email: String, code: String, purpose: String): EmailProviderResult {
            failureError?.let { return EmailProviderResult.Failed(it) }
            sentCodes += Triple(email, code, purpose)
            return EmailProviderResult.Sent
        }
    }

    @Test
    fun routesPhoneToSmsProviderAndEmailToEmailProvider() {
        val smsProvider = RecordingSmsProvider()
        val emailProvider = RecordingEmailProvider()
        val clock = MutableClock(1000)
        val store = InMemoryAccountStore()

        val service = AccountService(
            store = store,
            smsProvider = smsProvider,
            smsCodeGenerator = { "111111" },
            emailProvider = emailProvider,
            emailCodeGenerator = { "222222" },
            clock = clock
        )

        val phoneRes = service.issueVerificationCode("13800138000", "device-a", "127.0.0.1", "REGISTER")
        assertTrue(phoneRes is AccountResult.Success)
        assertEquals(listOf("13800138000" to "111111"), smsProvider.sentCodes)
        assertEquals(emptyList<Triple<String, String, String>>(), emailProvider.sentCodes)

        clock.advanceBy(60_001)

        val emailRes = service.issueVerificationCode("user@example.com", "device-b", "127.0.0.1", "REGISTER")
        assertTrue(emailRes is AccountResult.Success)
        assertEquals(listOf(Triple("user@example.com", "222222", "REGISTER")), emailProvider.sentCodes)
    }

    @Test
    fun rejectsVerificationCodeForUsername() {
        val service = AccountService()
        val res = service.issueVerificationCode("valid_user", "device-a", "127.0.0.1")
        assertEquals(AccountResult.Failure(AccountError.INVALID_REQUEST), res)
    }

    @Test
    fun returnsUnconfiguredErrorWhenEmailProviderNotConfigured() {
        val service = AccountService(
            emailProvider = MissingEmailProvider
        )
        val res = service.issueVerificationCode("user@example.com", "device-a", "127.0.0.1")
        assertEquals(AccountResult.Failure(AccountError.EMAIL_PROVIDER_UNCONFIGURED), res)
    }

    @Test
    fun enforcesEmailSendingCooldownRateLimit() {
        val clock = MutableClock(1000)
        val emailProvider = RecordingEmailProvider()
        val service = AccountService(
            emailProvider = emailProvider,
            clock = clock
        )

        val first = service.issueVerificationCode("user@example.com", "device-a", "127.0.0.1")
        assertTrue(first is AccountResult.Success)

        val second = service.issueVerificationCode("user@example.com", "device-a", "127.0.0.1")
        assertEquals(AccountResult.Failure(AccountError.CODE_SEND_TOO_FREQUENT), second)

        clock.advanceBy(60_001)

        val third = service.issueVerificationCode("user@example.com", "device-a", "127.0.0.1")
        assertTrue(third is AccountResult.Success)
    }

    @Test
    fun verifiesEmailCodeSuccessfullyAndFailsOnIncorrectOrExpiredCode() {
        val clock = MutableClock(1000)
        val store = InMemoryAccountStore()
        val service = AccountService(
            store = store,
            emailCodeGenerator = { "654321" },
            clock = clock
        )

        service.issueVerificationCode("user@example.com", "device-a", "127.0.0.1", "REGISTER")

        val wrongCode = service.verifyVerificationCode("EMAIL", "user@example.com", "000000", "REGISTER")
        assertEquals(AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG), wrongCode)

        val correctCode = service.verifyVerificationCode("EMAIL", "user@example.com", "654321", "REGISTER")
        assertTrue(correctCode is AccountResult.Success<*>)

        clock.advanceBy(300_001) // Expire

        val expired = service.verifyVerificationCode("EMAIL", "user@example.com", "654321", "REGISTER")
        assertEquals(AccountResult.Failure(AccountError.VERIFICATION_CODE_EXPIRED), expired)
    }

    @Test
    fun codeCannotCrossIdentifierTypeTargetOrPurpose() {
        val service = AccountService(
            emailProvider = RecordingEmailProvider(),
            emailCodeGenerator = { "654321" }
        )
        assertTrue(
            service.issueVerificationCode(
                "user@example.com",
                "device-a",
                "127.0.0.1",
                "REGISTER"
            ) is AccountResult.Success
        )

        assertEquals(
            AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG),
            service.verifyVerificationCode("EMAIL", "other@example.com", "654321", "REGISTER")
        )
        assertEquals(
            AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG),
            service.verifyVerificationCode("PHONE", "user@example.com", "654321", "REGISTER")
        )
        assertEquals(
            AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG),
            service.verifyVerificationCode("EMAIL", "user@example.com", "654321", "RECOVERY")
        )
        assertTrue(
            service.verifyVerificationCode("EMAIL", "user@example.com", "654321", "REGISTER")
                is AccountResult.Success
        )
    }

    @Test
    fun thirdWrongAttemptPermanentlyInvalidatesCode() {
        val service = AccountService(
            emailProvider = RecordingEmailProvider(),
            emailCodeGenerator = { "654321" }
        )
        service.issueVerificationCode("user@example.com", "device-a", "127.0.0.1", "REGISTER")

        repeat(3) {
            assertEquals(
                AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG),
                service.verifyVerificationCode("EMAIL", "user@example.com", "000000", "REGISTER")
            )
        }
        assertEquals(
            AccountResult.Failure(AccountError.VERIFICATION_CODE_WRONG),
            service.verifyVerificationCode("EMAIL", "user@example.com", "654321", "REGISTER")
        )
    }

    @Test
    fun malformedGeneratedCodesAreNeverSent() {
        val sms = RecordingSmsProvider()
        val email = RecordingEmailProvider()
        val service = AccountService(
            smsProvider = sms,
            smsCodeGenerator = { "12345" },
            emailProvider = email,
            emailCodeGenerator = { "abcdef" }
        )

        assertEquals(
            AccountResult.Failure(AccountError.SMS_SEND_FAILED),
            service.issueVerificationCode("13800138000", "device-a", "127.0.0.1", "REGISTER")
        )
        assertEquals(
            AccountResult.Failure(AccountError.EMAIL_SEND_FAILED),
            service.issueVerificationCode("user@example.com", "device-b", "127.0.0.1", "REGISTER")
        )
        assertTrue(sms.sentCodes.isEmpty())
        assertTrue(email.sentCodes.isEmpty())
    }
}
