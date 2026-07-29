package com.autoaccounting.backend.config

import com.autoaccounting.backend.account.AccountResult
import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.AccountToken
import com.autoaccounting.backend.account.MutableClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConfigServiceTest {
    @Test
    fun readConfigReturnsDefaultsForNewUser() {
        val service = cloudConfigService()
        service.accountService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        val reg = (service.accountService.registerIdentifier("13800138000", "123456", "Aa123456!") as AccountResult.Success<AccountToken>).value

        val config = service.configService.readConfig(reg.accountId)

        assertEquals(reg.accountId, config.accountId)
        assertFalse(config.aiConsentGranted)
        assertFalse(config.enhancedContextGranted)
        assertEquals(emptyMap<String, Boolean>(), config.featureFlags)
    }

    @Test
    fun writeConfigPersistsAndSurvivesRead() {
        val service = cloudConfigService()
        service.accountService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        val reg = (service.accountService.registerIdentifier("13800138000", "123456", "Aa123456!") as AccountResult.Success<AccountToken>).value

        val result = service.configService.writeConfig(
            StoredCloudConfig(
                accountId = reg.accountId,
                aiConsentGranted = true,
                enhancedContextGranted = true,
                featureFlags = mapOf("beta_reports" to true),
                updatedAtMillis = 1000
            )
        )

        assertEquals(CloudConfigResult.Written, result)
        val config = service.configService.readConfig(reg.accountId)
        assertTrue(config.aiConsentGranted)
        assertTrue(config.enhancedContextGranted)
        assertEquals(mapOf("beta_reports" to true), config.featureFlags)
    }

    @Test
    fun writeConfigBlockedDuringDeletionPending() {
        val service = cloudConfigService()
        service.accountService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        val reg = (service.accountService.registerIdentifier("13800138000", "123456", "Aa123456!") as AccountResult.Success<AccountToken>).value
        service.accountService.requestAccountDeletion(reg.token)

        val result = service.configService.writeConfig(
            StoredCloudConfig(
                accountId = reg.accountId,
                aiConsentGranted = true,
                enhancedContextGranted = false,
                featureFlags = emptyMap(),
                updatedAtMillis = 1000
            )
        )

        assertEquals(CloudConfigResult.DeletionPending, result)
    }

    @Test
    fun deleteConfigRemovesPersistedData() {
        val service = cloudConfigService()
        service.accountService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        val reg = (service.accountService.registerIdentifier("13800138000", "123456", "Aa123456!") as AccountResult.Success<AccountToken>).value
        service.configService.writeConfig(
            StoredCloudConfig(
                accountId = reg.accountId,
                aiConsentGranted = true,
                enhancedContextGranted = true,
                featureFlags = emptyMap(),
                updatedAtMillis = 1000
            )
        )

        service.configService.deleteConfig(reg.accountId)

        val config = service.configService.readConfig(reg.accountId)
        assertFalse(config.aiConsentGranted)
    }

    @Test
    fun mergeAndWriteConfigDoesNotClearMissingFields() {
        val service = cloudConfigService()
        service.accountService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        val reg = (service.accountService.registerIdentifier("13800138000", "123456", "Aa123456!") as AccountResult.Success<AccountToken>).value
        service.configService.writeConfig(
            StoredCloudConfig(
                accountId = reg.accountId,
                aiConsentGranted = true,
                enhancedContextGranted = true,
                featureFlags = mapOf("beta_reports" to true),
                updatedAtMillis = 1000
            )
        )

        val result = service.configService.mergeAndWriteConfig(
            accountId = reg.accountId,
            update = CloudConfigUpdate(aiConsentGranted = false),
            now = 2000
        )

        assertEquals(CloudConfigResult.Written, result)
        val config = service.configService.readConfig(reg.accountId)
        assertFalse(config.aiConsentGranted)
        assertFalse(config.enhancedContextGranted)
        assertEquals(mapOf("beta_reports" to true), config.featureFlags)
        assertEquals(2000L, config.updatedAtMillis)
    }


    @Test
    fun readConfigNormalizesLegacyEnhancedContextWithoutAiConsent() {
        val accountService = AccountService(
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "token-1" },
            clock = MutableClock(0)
        )
        val store = InMemoryCloudConfigStore().apply {
            upsertConfig(
                StoredCloudConfig(
                    accountId = 99,
                    aiConsentGranted = false,
                    enhancedContextGranted = true,
                    updatedAtMillis = 1000
                )
            )
        }
        val service = CloudConfigService(store = store, accountService = accountService)

        val config = service.readConfig(99)

        assertFalse(config.aiConsentGranted)
        assertFalse(config.enhancedContextGranted)
    }

    @Test
    fun directWriteCannotPersistEnhancedContextWithoutAiConsent() {
        val service = cloudConfigService()
        service.accountService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        val reg = (service.accountService.registerIdentifier(
            "13800138000",
            "123456",
            "Aa123456!"
        ) as AccountResult.Success<AccountToken>).value

        service.configService.writeConfig(
            StoredCloudConfig(
                accountId = reg.accountId,
                aiConsentGranted = false,
                enhancedContextGranted = true,
                updatedAtMillis = 1000
            )
        )

        val config = service.configService.readConfig(reg.accountId)
        assertFalse(config.aiConsentGranted)
        assertFalse(config.enhancedContextGranted)
    }

    private fun cloudConfigService(): CloudConfigTestHarness {
        val accountService = AccountService(
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "token-1" },
            clock = MutableClock(0)
        )
        val configService = CloudConfigService(
            store = InMemoryCloudConfigStore(),
            accountService = accountService
        )
        return CloudConfigTestHarness(accountService, configService)
    }

    private data class CloudConfigTestHarness(
        val accountService: AccountService,
        val configService: CloudConfigService
    )
}
