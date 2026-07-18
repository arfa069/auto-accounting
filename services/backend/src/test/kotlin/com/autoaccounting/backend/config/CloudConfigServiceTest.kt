package com.autoaccounting.backend.config

import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.MutableClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConfigServiceTest {
    @Test
    fun readConfigReturnsDefaultsForNewUser() {
        val service = cloudConfigService()
        service.accountService.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        service.accountService.register("13800138000", "123456", "Aa123456!")

        val config = service.configService.readConfig("13800138000")

        assertEquals("13800138000", config.phone)
        assertFalse(config.aiConsentGranted)
        assertFalse(config.enhancedContextGranted)
        assertEquals(emptyMap<String, Boolean>(), config.featureFlags)
    }

    @Test
    fun writeConfigPersistsAndSurvivesRead() {
        val service = cloudConfigService()
        service.accountService.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        service.accountService.register("13800138000", "123456", "Aa123456!")

        val result = service.configService.writeConfig(
            StoredCloudConfig(
                phone = "13800138000",
                aiConsentGranted = true,
                enhancedContextGranted = true,
                featureFlags = mapOf("beta_reports" to true),
                updatedAtMillis = 1000
            )
        )

        assertEquals(CloudConfigResult.Written, result)
        val config = service.configService.readConfig("13800138000")
        assertTrue(config.aiConsentGranted)
        assertTrue(config.enhancedContextGranted)
        assertEquals(mapOf("beta_reports" to true), config.featureFlags)
    }

    @Test
    fun writeConfigBlockedDuringDeletionPending() {
        val service = cloudConfigService()
        service.accountService.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        service.accountService.register("13800138000", "123456", "Aa123456!")
        service.accountService.requestAccountDeletion("token-1")

        val result = service.configService.writeConfig(
            StoredCloudConfig(
                phone = "13800138000",
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
        service.accountService.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        service.accountService.register("13800138000", "123456", "Aa123456!")
        service.configService.writeConfig(
            StoredCloudConfig(
                phone = "13800138000",
                aiConsentGranted = true,
                enhancedContextGranted = true,
                featureFlags = emptyMap(),
                updatedAtMillis = 1000
            )
        )

        service.configService.deleteConfig("13800138000")

        val config = service.configService.readConfig("13800138000")
        assertFalse(config.aiConsentGranted)
    }

    @Test
    fun mergeAndWriteConfigDoesNotClearMissingFields() {
        val service = cloudConfigService()
        service.accountService.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        service.accountService.register("13800138000", "123456", "Aa123456!")
        service.configService.writeConfig(
            StoredCloudConfig(
                phone = "13800138000",
                aiConsentGranted = true,
                enhancedContextGranted = true,
                featureFlags = mapOf("beta_reports" to true),
                updatedAtMillis = 1000
            )
        )

        val result = service.configService.mergeAndWriteConfig(
            phone = "13800138000",
            update = CloudConfigUpdate(aiConsentGranted = false),
            now = 2000
        )

        assertEquals(CloudConfigResult.Written, result)
        val config = service.configService.readConfig("13800138000")
        assertFalse(config.aiConsentGranted)
        assertTrue(config.enhancedContextGranted)
        assertEquals(mapOf("beta_reports" to true), config.featureFlags)
        assertEquals(2000L, config.updatedAtMillis)
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
