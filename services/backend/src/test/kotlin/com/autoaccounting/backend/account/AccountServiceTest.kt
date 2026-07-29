package com.autoaccounting.backend.account

import com.autoaccounting.api.AiCategorizationRequestContract
import com.autoaccounting.api.LedgerSyncEntityTypeContract
import com.autoaccounting.api.LedgerSyncMutationContract
import com.autoaccounting.api.LedgerSyncPayloadContract
import com.autoaccounting.backend.AccountDeletionJob
import com.autoaccounting.backend.ai.AiCategorizationService
import com.autoaccounting.backend.ai.InMemoryAiCategorizationLogStore
import com.autoaccounting.backend.ai.RuleBasedAiProvider
import com.autoaccounting.backend.config.CloudConfigService
import com.autoaccounting.backend.config.CloudConfigStore
import com.autoaccounting.backend.config.InMemoryCloudConfigStore
import com.autoaccounting.backend.config.StoredCloudConfig
import com.autoaccounting.backend.sync.InMemoryLedgerSyncStore
import com.autoaccounting.backend.sync.LedgerSyncService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountServiceTest {
    @Test
    fun registrationRequiresIssuedSmsCodeAndStoresPasswordHash() {
        val service = accountService()

        assertEquals(
            AccountError.VERIFICATION_CODE_WRONG,
            service.registerIdentifier("13800138000", "000000", "Aa123456!").error
        )

        val issued = service.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        assertEquals(AccountResult.Success(Unit), issued)

        val registered = service.registerIdentifier("13800138000", "123456", "Aa123456!")

        assertTrue(registered is AccountResult.Success)
        assertEquals(
            AccountError.PHONE_ALREADY_REGISTERED,
            service.registerIdentifier("13800138000", "123456", "Aa123456!").error
        )
    }

    @Test
    fun serverRejectsMalformedPhoneCodePasswordAndDeviceId() {
        val service = accountService()

        assertEquals(
            AccountError.INVALID_REQUEST,
            service.issueVerificationCode("123", "device-a", "127.0.0.1").error
        )
        service.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        assertEquals(
            AccountError.INVALID_REQUEST,
            service.registerIdentifier("13800138000", "12345", "Aa123456!").error
        )
        assertEquals(
            AccountError.INVALID_REQUEST,
            service.registerIdentifier("13800138000", "123456", "weak-password").error
        )
        assertEquals(
            AccountError.LOGIN_FAILED,
            service.loginIdentifier("13800138000", "Aa123456!", "invalid device").error
        )
    }

    @Test
    fun smsIssueIsRateLimitedByPhoneDeviceAndIp() {
        val service = accountService()

        assertEquals(AccountResult.Success(Unit), service.issueVerificationCode("13800138000", "device-a", "127.0.0.1"))

        assertEquals(
            AccountError.SMS_TOO_FREQUENT,
            service.issueVerificationCode("13800138000", "device-b", "127.0.0.2").error
        )

        repeat(4) { index ->
            assertEquals(
                AccountResult.Success(Unit),
                service.issueVerificationCode("1390013900$index", "device-a", "127.0.0.${index + 2}")
            )
        }
        assertEquals(
            AccountError.SMS_TOO_FREQUENT,
            service.issueVerificationCode("13700137000", "device-a", "127.0.0.9").error
        )

        val ipLimitedService = accountService()
        repeat(5) { index ->
            assertEquals(
                AccountResult.Success(Unit),
                ipLimitedService.issueVerificationCode("1360013600$index", "device-$index", "127.0.0.1")
            )
        }
        assertEquals(
            AccountError.SMS_TOO_FREQUENT,
            ipLimitedService.issueVerificationCode("13500135000", "device-z", "127.0.0.1").error
        )
    }

    @Test
    fun smsCodeIsInvalidatedAfterThreeWrongAttempts() {
        val service = accountService()
        service.issueVerificationCode("13800138000", "device-a", "127.0.0.1")

        repeat(3) {
            assertEquals(
                AccountError.VERIFICATION_CODE_WRONG,
                service.registerIdentifier("13800138000", "000000", "Aa123456!").error
            )
        }

        assertEquals(
            AccountError.VERIFICATION_CODE_WRONG,
            service.registerIdentifier("13800138000", "123456", "Aa123456!").error
        )
    }

    @Test
    fun verificationCodeHashUsesConstantTimeCompatibleComparison() {
        val hasher = VerificationCodeHasher.forTests()
        val hash = hasher.hash("13800138000", "123456")

        assertTrue(hasher.matches("13800138000", "123456", hash))
        assertTrue(!hasher.matches("13800138000", "654321", hash))
        assertTrue(!hasher.matches("13800138000", "123456", "not-base64"))
    }

    @Test
    fun loginLocksAfterFiveConsecutivePasswordFailures() {
        val service = accountService()
        service.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        service.registerIdentifier("13800138000", "123456", "Aa123456!")

        repeat(4) {
            assertEquals(AccountError.LOGIN_FAILED, service.loginIdentifier("13800138000", "wrong").error)
        }

        assertEquals(AccountError.ACCOUNT_LOCKED, service.loginIdentifier("13800138000", "wrong").error)
        assertEquals(AccountError.ACCOUNT_LOCKED, service.loginIdentifier("13800138000", "Aa123456!").error)
    }

    @Test
    fun registrationCreatesPersistedTokenAndRegisteredDevice() {
        val service = accountService()
        service.issueVerificationCode("13800138000", "device-a", "127.0.0.1")

        val registered = service.registerIdentifier(
            "13800138000",
            "123456",
            "Aa123456!",
            "device-a",
            "127.0.0.1"
        )
            as AccountResult.Success<AccountToken>

        val verified = service.verifyToken(registered.value.token) as AccountResult.Success<AccountToken>
        assertEquals("13800138000", verified.value.phone)
        assertEquals("token-1", verified.value.token)
        assertTrue(verified.value.accountId > 0L)
        assertEquals(
            listOf("device-a"),
            service.registeredDevices(registered.value.accountId).map { it.deviceId }
        )
    }

    @Test
    fun missingSmsProviderFailsWithoutPersistingCodeOrRateLimit() {
        val service = AccountService(
            smsProvider = MissingSmsProvider,
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "token-1" },
            clock = MutableClock(0)
        )

        assertEquals(
            AccountError.SMS_PROVIDER_UNCONFIGURED,
            service.issueVerificationCode("13800138000", "device-a", "127.0.0.1").error
        )
        assertEquals(
            AccountError.VERIFICATION_CODE_WRONG,
            service.registerIdentifier("13800138000", "123456", "Aa123456!").error
        )
    }

    @Test
    fun recoveryResetsPasswordAfterSmsVerification() {
        var tokenIndex = 0
        val service = accountService(tokenGenerator = { "token-${++tokenIndex}" })
        service.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        val originalToken = (service.registerIdentifier("13800138000", "123456", "Aa123456!")
            as AccountResult.Success<AccountToken>).value.token

        service.advanceTimeBy(61_000)
        service.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        val recovered = service.recoverPasswordByIdentifier("13800138000", "123456", "Bb123456!")

        assertTrue(recovered is AccountResult.Success)
        assertEquals(AccountError.TOKEN_INVALID, service.verifyToken(originalToken).error)
        assertEquals(AccountError.LOGIN_FAILED, service.loginIdentifier("13800138000", "Aa123456!").error)
        assertTrue(service.loginIdentifier("13800138000", "Bb123456!") is AccountResult.Success)
    }

    @Test
    fun signOutRevokesOnlyCurrentSession() {
        var tokenIndex = 0
        val service = accountService(tokenGenerator = { "token-${++tokenIndex}" })
        service.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        val firstToken = (service.registerIdentifier("13800138000", "123456", "Aa123456!")
            as AccountResult.Success<AccountToken>).value.token
        val secondToken = (service.loginIdentifier("13800138000", "Aa123456!", "device-b")
            as AccountResult.Success<AccountToken>).value.token

        assertEquals(AccountResult.Success(Unit), service.signOut(firstToken))
        assertEquals(AccountError.TOKEN_INVALID, service.verifyToken(firstToken).error)
        assertTrue(service.verifyToken(secondToken) is AccountResult.Success)
    }

    @Test
    fun accountDeletionHasCoolingOffCancelAndFinalDeleteStateMachine() {
        val service = accountService(startMillis = 0)
        service.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        val registration = service.registerIdentifier(
            "13800138000",
            "123456",
            "Aa123456!",
            "device-a",
            "127.0.0.1"
        ) as AccountResult.Success<AccountToken>
        val token = registration.value.token
        val accountId = registration.value.accountId

        val requested = service.requestAccountDeletion(token) as AccountResult.Success<AccountDeletionStatus>

        assertEquals("13800138000", requested.value.phone)
        assertEquals(0L, requested.value.requestedAtMillis)
        assertEquals(604_800_000L, requested.value.finalDeletionAtMillis)
        assertTrue(service.loginIdentifier("13800138000", "Aa123456!") is AccountResult.Success)
        assertEquals(
            AccountError.ACCOUNT_DELETION_PENDING,
            service.writeCloudConfiguration(accountId).error
        )

        assertTrue(service.cancelAccountDeletion(token) is AccountResult.Success<*>)
        assertEquals(AccountResult.Success(Unit), service.writeCloudConfiguration(accountId))

        service.requestAccountDeletion(token)
        service.advanceTimeBy(604_799_999)
        assertTrue(service.accountsDueForDeletion().isEmpty())

        service.advanceTimeBy(1)
        val dueAccountId = service.accountsDueForDeletion().single()
        assertTrue(service.finalizeAccountDeletion(dueAccountId))
        assertTrue(service.accountsDueForDeletion().isEmpty())
        assertEquals(AccountError.TOKEN_INVALID, service.requestAccountDeletion(token).error)
    }

    @Test
    fun finalDeletionJobPurgesAiLogsAndCloudConfigForDeletedAccount() {
        val accountService = accountService(startMillis = 0)
        val aiService = AiCategorizationService(provider = RuleBasedAiProvider)
        val cloudConfigService = CloudConfigService(
            store = InMemoryCloudConfigStore(),
            accountService = accountService
        )
        accountService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        val tokenResult = (accountService.registerIdentifier("13800138000", "123456", "Aa123456!")
            as AccountResult.Success<AccountToken>).value
        val accountId = tokenResult.accountId

        runBlocking {
            aiService.suggest(
                accountId = accountId,
                request = AiCategorizationRequestContract(
                    merchantTitle = "午餐",
                    sourceLabel = "微信",
                    transactionKind = "支出",
                    amountRangeLabel = "0-50",
                    categoryCandidates = listOf("餐饮")
                ),
                enhancedContextAuthorized = false
            )
        }
        cloudConfigService.writeConfig(
            StoredCloudConfig(
                accountId = accountId,
                aiConsentGranted = true,
                enhancedContextGranted = true,
                featureFlags = mapOf("beta" to true),
                updatedAtMillis = 1000
            )
        )

        accountService.requestAccountDeletion(tokenResult.token)
        accountService.advanceTimeBy(604_800_000)
        val deletionJob = AccountDeletionJob(accountService, aiService, cloudConfigService)
        val deletedAccountIds = deletionJob.runDueDeletion()

        assertEquals(listOf(accountId), deletedAccountIds)
        assertTrue(aiService.logs.isEmpty())
        assertEquals(emptyMap<String, Boolean>(), cloudConfigService.readConfig(accountId).featureFlags)
        assertTrue(deletionJob.runDueDeletion().isEmpty())
    }

    @Test
    fun finalDeletionJobPurgesLedgerSyncState() {
        val accountService = accountService(startMillis = 0)
        val syncStore = InMemoryLedgerSyncStore()
        val syncService = LedgerSyncService(syncStore, accountService)
        accountService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        val token = (accountService.registerIdentifier(
            "13800138000",
            "123456",
            "Aa123456!"
        ) as AccountResult.Success<AccountToken>).value
        syncService.initialize(token.accountId)
        syncService.push(
            token.accountId,
            "device-a",
            listOf(
                LedgerSyncMutationContract(
                    mutationId = "mutation-a",
                    entityType = LedgerSyncEntityTypeContract.LEDGER_BOOK,
                    entityId = "book-a",
                    baseVersion = 0,
                    deleted = false,
                    payload = LedgerSyncPayloadContract.LedgerBook("book-a", "日常账本", 1)
                )
            )
        )
        accountService.requestAccountDeletion(token.token)
        accountService.advanceTimeBy(AccountService.ACCOUNT_DELETION_COOLING_OFF_MILLIS)
        val deletionJob = AccountDeletionJob(
            accountService,
            AiCategorizationService(),
            CloudConfigService(InMemoryCloudConfigStore(), accountService),
            syncService
        )

        assertEquals(listOf(token.accountId), deletionJob.runDueDeletion())
        assertEquals(0, syncStore.recordCount(token.accountId))
        assertEquals(0, syncStore.currentCursor(token.accountId))
    }

    @Test
    fun finalDeletionRetainsAccountWhenCleanupFailsAndSucceedsOnRetry() {
        val accountService = accountService(startMillis = 0)
        val aiStore = InMemoryAiCategorizationLogStore()
        val aiService = AiCategorizationService(provider = RuleBasedAiProvider, logStore = aiStore)
        val configStore = FailingOnceCloudConfigStore()
        val cloudConfigService = CloudConfigService(configStore, accountService)
        accountService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        val tokenResult = (accountService.registerIdentifier("13800138000", "123456", "Aa123456!")
            as AccountResult.Success<AccountToken>).value
        val token = tokenResult.token
        val accountId = tokenResult.accountId

        runBlocking {
            aiService.suggest(
                accountId = accountId,
                request = AiCategorizationRequestContract(
                    merchantTitle = "merchant",
                    sourceLabel = "source",
                    transactionKind = "expense",
                    amountRangeLabel = "0-50",
                    categoryCandidates = listOf("其他")
                ),
                enhancedContextAuthorized = false
            )
        }
        cloudConfigService.writeConfig(
            StoredCloudConfig(accountId = accountId, updatedAtMillis = 0)
        )
        accountService.requestAccountDeletion(token)
        accountService.advanceTimeBy(AccountService.ACCOUNT_DELETION_COOLING_OFF_MILLIS)
        val job = AccountDeletionJob(accountService, aiService, cloudConfigService)

        assertTrue(job.runDueDeletion().isEmpty())
        assertTrue(accountService.verifyToken(token) is AccountResult.Success)
        assertTrue(aiStore.allLogs().isEmpty())

        assertEquals(listOf(accountId), job.runDueDeletion())
        assertEquals(AccountError.TOKEN_INVALID, accountService.verifyToken(token).error)
        assertTrue(job.runDueDeletion().isEmpty())
    }

    @Test
    fun deviceWritesArePausedWhileAccountDeletionIsPending() {
        val service = accountService()
        service.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        val registration = service.registerIdentifier(
            "13800138000",
            "123456",
            "Aa123456!",
            "device-a",
            "127.0.0.1"
        ) as AccountResult.Success<AccountToken>
        val token = registration.value.token
        val accountId = registration.value.accountId

        // Verify initial device registered
        assertEquals(1, service.registeredDevices(accountId).size)
        assertEquals("device-a", service.registeredDevices(accountId).first().deviceId)

        // Request account deletion -> enters pending state
        service.requestAccountDeletion(token)

        // Try to log in with new device during cooling-off -> should NOT write the new device
        service.loginIdentifier("13800138000", "Aa123456!", "device-b", "127.0.0.2")
        val devicesDuringPending = service.registeredDevices(accountId)
        assertEquals(1, devicesDuringPending.size)
        assertEquals("device-a", devicesDuringPending.first().deviceId)

        // Cancel deletion -> writes allowed again
        service.cancelAccountDeletion(token)

        // Log in again -> should write the new device
        service.loginIdentifier("13800138000", "Aa123456!", "device-b", "127.0.0.2")
        val devicesAfterCancel = service.registeredDevices(accountId)
        assertEquals(2, devicesAfterCancel.size)
        assertEquals(listOf("device-a", "device-b"), devicesAfterCancel.map { it.deviceId })
    }

    private fun accountService(
        startMillis: Long? = null,
        tokenGenerator: () -> String = { "token-1" }
    ): AccountService = AccountService(
        smsCodeGenerator = { "123456" },
        tokenGenerator = tokenGenerator,
        verificationCodeHasher = VerificationCodeHasher.forTests(),
        clock = startMillis?.let { MutableClock(it) } ?: MutableClock()
    )

    private class FailingOnceCloudConfigStore : CloudConfigStore {
        private val delegate = InMemoryCloudConfigStore()
        private var failNextDelete = true

        override fun findConfig(accountId: Long): StoredCloudConfig? = delegate.findConfig(accountId)

        override fun upsertConfig(config: StoredCloudConfig) = delegate.upsertConfig(config)

        override fun deleteConfig(accountId: Long) {
            if (failNextDelete) {
                failNextDelete = false
                error("simulated cleanup failure")
            }
            delegate.deleteConfig(accountId)
        }
    }
}
