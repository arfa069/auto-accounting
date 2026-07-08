package com.autoaccounting.backend.account

import com.autoaccounting.backend.AccountDeletionJob
import com.autoaccounting.backend.ai.AiCategorizationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountServiceTest {
    @Test
    fun registrationRequiresIssuedSmsCodeAndStoresPasswordHash() {
        val service = accountService()

        assertEquals(
            AccountError.VERIFICATION_CODE_WRONG,
            service.register("13800138000", "000000", "Aa123456!").error
        )

        val issued = service.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        assertEquals(AccountResult.Success(Unit), issued)

        val registered = service.register("13800138000", "123456", "Aa123456!")

        assertTrue(registered is AccountResult.Success)
        assertEquals(
            AccountError.PHONE_ALREADY_REGISTERED,
            service.register("13800138000", "123456", "Aa123456!").error
        )
    }

    @Test
    fun smsIssueIsRateLimitedByPhoneDeviceAndIp() {
        val service = accountService()

        assertEquals(AccountResult.Success(Unit), service.issueSmsCode("13800138000", "device-a", "127.0.0.1"))

        assertEquals(
            AccountError.SMS_TOO_FREQUENT,
            service.issueSmsCode("13800138000", "device-b", "127.0.0.2").error
        )
        assertEquals(
            AccountError.SMS_TOO_FREQUENT,
            service.issueSmsCode("13900139000", "device-a", "127.0.0.2").error
        )
        assertEquals(
            AccountError.SMS_TOO_FREQUENT,
            service.issueSmsCode("13900139000", "device-b", "127.0.0.1").error
        )
    }

    @Test
    fun loginLocksAfterFiveConsecutivePasswordFailures() {
        val service = accountService()
        service.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        service.register("13800138000", "123456", "Aa123456!")

        repeat(4) {
            assertEquals(AccountError.LOGIN_FAILED, service.login("13800138000", "wrong").error)
        }

        assertEquals(AccountError.ACCOUNT_LOCKED, service.login("13800138000", "wrong").error)
        assertEquals(AccountError.ACCOUNT_LOCKED, service.login("13800138000", "Aa123456!").error)
    }

    @Test
    fun recoveryResetsPasswordAfterSmsVerification() {
        val service = accountService()
        service.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        service.register("13800138000", "123456", "Aa123456!")

        service.advanceTimeBy(61_000)
        service.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        val recovered = service.recoverPassword("13800138000", "123456", "Bb123456!")

        assertTrue(recovered is AccountResult.Success)
        assertEquals(AccountError.LOGIN_FAILED, service.login("13800138000", "Aa123456!").error)
        assertTrue(service.login("13800138000", "Bb123456!") is AccountResult.Success)
    }

    @Test
    fun accountDeletionHasCoolingOffCancelAndFinalDeleteStateMachine() {
        val service = accountService(startMillis = 0)
        service.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        service.register("13800138000", "123456", "Aa123456!")

        val requested = service.requestAccountDeletion("13800138000")

        assertEquals(
            AccountDeletionStatus(
                phone = "13800138000",
                requestedAtMillis = 0,
                finalDeletionAtMillis = 604_800_000
            ),
            (requested as AccountResult.Success<AccountDeletionStatus>).value
        )
        assertTrue(service.login("13800138000", "Aa123456!") is AccountResult.Success)
        assertEquals(
            AccountError.ACCOUNT_DELETION_PENDING,
            service.writeCloudConfiguration("13800138000").error
        )

        assertTrue(service.cancelAccountDeletion("13800138000") is AccountResult.Success<*>)
        assertEquals(AccountResult.Success(Unit), service.writeCloudConfiguration("13800138000"))

        service.requestAccountDeletion("13800138000")
        service.advanceTimeBy(604_799_999)
        assertTrue(service.deleteDueAccounts().isEmpty())

        service.advanceTimeBy(1)
        assertEquals(listOf("13800138000"), service.deleteDueAccounts())
        assertEquals(AccountError.PHONE_NOT_REGISTERED, service.requestAccountDeletion("13800138000").error)
    }

    @Test
    fun finalDeletionJobPurgesAiLogsForDeletedAccount() {
        val accountService = accountService(startMillis = 0)
        val aiService = AiCategorizationService()
        accountService.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        accountService.register("13800138000", "123456", "Aa123456!")
        aiService.suggest(
            accountPhone = "13800138000",
            merchantTitle = "午餐",
            sourceLabel = "微信",
            transactionKind = "支出",
            amountMinor = 3590,
            categoryCandidates = listOf("餐饮"),
            note = null,
            rawEvidenceText = null,
            enhancedContext = false
        )

        accountService.requestAccountDeletion("13800138000")
        accountService.advanceTimeBy(604_800_000)
        val deletedPhones = AccountDeletionJob(accountService, aiService).runDueDeletion()

        assertEquals(listOf("13800138000"), deletedPhones)
        assertTrue(aiService.logs.isEmpty())
    }

    private fun accountService(startMillis: Long? = null): AccountService = AccountService(
        smsCodeGenerator = { "123456" },
        tokenGenerator = { "token-1" },
        clock = startMillis?.let { MutableClock(it) } ?: MutableClock()
    )
}
