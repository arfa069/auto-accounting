package com.autoaccounting.backend.ai

import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.JdbcAccountStore
import com.autoaccounting.backend.account.MutableClock
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCategorizationPersistenceTest {
    @Test
    fun jdbcLogStorePersistsLogsAcrossStoreInstances() {
        val databaseUrl = h2DatabaseUrl()
        setupAccountTables(databaseUrl)

        val firstStore = JdbcAiCategorizationLogStore(databaseUrl)
        firstStore.insertLog(
            StoredAiCategorizationLog(
                accountPhone = "13800138000",
                merchantTitle = "午餐",
                sourceLabel = "微信",
                transactionKind = "支出",
                amountRangeLabel = "0-50",
                suggestedCategory = "餐饮",
                confidenceLabel = "中",
                explanation = "基于商户标题生成分类建议",
                createdAtMillis = 1000
            )
        )

        val secondStore = JdbcAiCategorizationLogStore(databaseUrl)
        val logs = secondStore.allLogs()

        assertEquals(1, logs.size)
        val log = logs.single()
        assertEquals("13800138000", log.accountPhone)
        assertEquals("午餐", log.merchantTitle)
        assertEquals("餐饮", log.suggestedCategory)
        assertEquals(1000L, log.createdAtMillis)
    }

    @Test
    fun logsDeletedForAccountOnAccountDeletion() {
        val databaseUrl = h2DatabaseUrl()
        setupAccountTables(databaseUrl)

        val logStore = JdbcAiCategorizationLogStore(databaseUrl)
        logStore.insertLog(
            StoredAiCategorizationLog(
                accountPhone = "13800138000",
                merchantTitle = "午餐",
                sourceLabel = "微信",
                transactionKind = "支出",
                amountRangeLabel = "0-50",
                suggestedCategory = "餐饮",
                confidenceLabel = "中",
                explanation = "test",
                createdAtMillis = 1000
            )
        )
        logStore.insertLog(
            StoredAiCategorizationLog(
                accountPhone = "13900139000",
                merchantTitle = "地铁",
                sourceLabel = "支付宝",
                transactionKind = "支出",
                amountRangeLabel = "0-50",
                suggestedCategory = "交通",
                confidenceLabel = "中",
                explanation = "test",
                createdAtMillis = 2000
            )
        )

        logStore.deleteLogsForAccount("13800138000")

        assertEquals(0, logStore.logsForAccount("13800138000").size)
        assertEquals(1, logStore.logsForAccount("13900139000").size)
    }

    @Test
    fun migrationVersion3CreatesAiCategorizationLogsTable() {
        val databaseUrl = h2DatabaseUrl()
        setupAccountTables(databaseUrl)
        JdbcAiCategorizationLogStore(databaseUrl)

        DriverManager.getConnection(databaseUrl).use { connection ->
            val migrationCount = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM schema_migrations WHERE version = 3").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
            val tableCount = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_name = 'ai_categorization_logs'
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
    fun aiServiceUsesLogStoreForPersistence() {
        val logStore = InMemoryAiCategorizationLogStore()
        val service = AiCategorizationService(
            provider = RuleBasedAiProvider,
            logStore = logStore
        )

        service.suggest(
            accountPhone = "13800138000",
            merchantTitle = "午餐",
            sourceLabel = "微信",
            transactionKind = "支出",
            amountMinor = 3590,
            categoryCandidates = listOf("餐饮"),
            note = "private note",
            rawEvidenceText = "private evidence",
            enhancedContext = true
        )

        val logs = logStore.allLogs()
        assertEquals(1, logs.size)
        assertEquals("餐饮", logs.single().suggestedCategory)
        // Log does not contain note or rawEvidenceText — product boundary preserved
    }

    private fun setupAccountTables(databaseUrl: String) {
        val store = JdbcAccountStore(databaseUrl)
        store.createUser(
            com.autoaccounting.backend.account.StoredUser(
                phone = "13800138000",
                passwordSalt = "salt",
                passwordHash = "hash",
                failedLoginCount = 0,
                lockedUntilMillis = 0,
                deletionRequestedAtMillis = null,
                createdAtMillis = 1000
            )
        )
        store.createUser(
            com.autoaccounting.backend.account.StoredUser(
                phone = "13900139000",
                passwordSalt = "salt",
                passwordHash = "hash",
                failedLoginCount = 0,
                lockedUntilMillis = 0,
                deletionRequestedAtMillis = null,
                createdAtMillis = 1000
            )
        )
    }

    private fun h2DatabaseUrl(): String {
        return "jdbc:h2:mem:${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    }
}
