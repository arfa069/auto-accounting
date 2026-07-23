package com.autoaccounting.backend.config

import com.autoaccounting.backend.AccountDeletionJob
import com.autoaccounting.backend.account.AccountResult
import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.AccountToken
import com.autoaccounting.backend.account.JdbcAccountStore
import com.autoaccounting.backend.account.MutableClock
import com.autoaccounting.backend.ai.AiCategorizationService
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConfigPersistenceTest {
    @Test
    fun jdbcStorePersistsConfigAcrossStoreInstances() {
        val databaseUrl = h2DatabaseUrl()
        val clock = MutableClock(0)
        val accountService = accountService(databaseUrl, clock)
        accountService.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        val reg = (accountService.register("13800138000", "123456", "Aa123456!") as AccountResult.Success<AccountToken>).value

        val firstStore = JdbcCloudConfigStore(databaseUrl)
        firstStore.upsertConfig(
            StoredCloudConfig(
                accountId = reg.accountId,
                aiConsentGranted = true,
                enhancedContextGranted = true,
                featureFlags = mapOf("beta" to true),
                updatedAtMillis = 1000
            )
        )

        val secondStore = JdbcCloudConfigStore(databaseUrl)
        val config = secondStore.findConfig(reg.accountId)!!

        assertTrue(config.aiConsentGranted)
        assertTrue(config.enhancedContextGranted)
        assertEquals(mapOf("beta" to true), config.featureFlags)
        assertEquals(1000L, config.updatedAtMillis)
    }

    @Test
    fun migrationVersion2CreatesCloudConfigTable() {
        val databaseUrl = h2DatabaseUrl()
        JdbcAccountStore(databaseUrl)
        JdbcCloudConfigStore(databaseUrl)

        DriverManager.getConnection(databaseUrl).use { connection ->
            val migrationCount = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM schema_migrations WHERE version = 2").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
            val tableCount = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_name = 'cloud_config'
                    """.trimIndent()
                ).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }

            assertEquals(1, migrationCount)
            assertTrue(tableCount >= 1)
        }
    }

    @Test
    fun cascadeDeleteRemovesConfigWhenUserDeleted() {
        val databaseUrl = h2DatabaseUrl()
        val clock = MutableClock(0)
        val accountService = accountService(databaseUrl, clock)
        accountService.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        val tokenResult = (accountService.register("13800138000", "123456", "Aa123456!")
            as AccountResult.Success<AccountToken>).value

        val configStore = JdbcCloudConfigStore(databaseUrl)
        configStore.upsertConfig(
            StoredCloudConfig(
                accountId = tokenResult.accountId,
                aiConsentGranted = true,
                enhancedContextGranted = false,
                featureFlags = emptyMap(),
                updatedAtMillis = 1000
            )
        )

        accountService.requestAccountDeletion(tokenResult.token)
        clock.advanceBy(AccountService.ACCOUNT_DELETION_COOLING_OFF_MILLIS)
        val deletionJob = AccountDeletionJob(
            accountService = accountService,
            aiCategorizationService = AiCategorizationService(),
            cloudConfigService = CloudConfigService(configStore, accountService)
        )
        assertEquals(listOf(tokenResult.accountId), deletionJob.runDueDeletion())
        assertTrue(deletionJob.runDueDeletion().isEmpty())

        assertEquals(null, configStore.findConfig(tokenResult.accountId))
    }

    private fun accountService(databaseUrl: String, clock: MutableClock): AccountService {
        return AccountService(
            store = JdbcAccountStore(databaseUrl),
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "token-1" },
            verificationCodeHasher = com.autoaccounting.backend.account.VerificationCodeHasher.forTests(),
            clock = clock
        )
    }

    private fun h2DatabaseUrl(): String {
        return "jdbc:h2:mem:${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    }
}
