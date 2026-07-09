package com.autoaccounting.backend.config

import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.JdbcAccountStore
import com.autoaccounting.backend.account.MutableClock
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
        accountService.register("13800138000", "123456", "Aa123456!")

        val firstStore = JdbcCloudConfigStore(databaseUrl)
        firstStore.upsertConfig(
            StoredCloudConfig(
                phone = "13800138000",
                aiConsentGranted = true,
                enhancedContextGranted = true,
                featureFlags = """{"beta":true}""",
                updatedAtMillis = 1000
            )
        )

        val secondStore = JdbcCloudConfigStore(databaseUrl)
        val config = secondStore.findConfig("13800138000")!!

        assertTrue(config.aiConsentGranted)
        assertTrue(config.enhancedContextGranted)
        assertEquals("""{"beta":true}""", config.featureFlags)
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
        accountService.register("13800138000", "123456", "Aa123456!")

        val configStore = JdbcCloudConfigStore(databaseUrl)
        configStore.upsertConfig(
            StoredCloudConfig(
                phone = "13800138000",
                aiConsentGranted = true,
                enhancedContextGranted = false,
                featureFlags = "{}",
                updatedAtMillis = 1000
            )
        )

        accountService.requestAccountDeletion("13800138000")
        clock.advanceBy(AccountService.ACCOUNT_DELETION_COOLING_OFF_MILLIS)
        accountService.deleteDueAccounts()

        assertEquals(null, configStore.findConfig("13800138000"))
    }

    private fun accountService(databaseUrl: String, clock: MutableClock): AccountService {
        return AccountService(
            store = JdbcAccountStore(databaseUrl),
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "token-1" },
            clock = clock
        )
    }

    private fun h2DatabaseUrl(): String {
        return "jdbc:h2:mem:${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    }
}
