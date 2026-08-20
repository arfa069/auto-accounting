package com.bks.backend.ai

import com.bks.api.AiCategorizationRequestContract
import kotlinx.coroutines.runBlocking

import com.bks.backend.account.JdbcAccountStore
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCategorizationPersistenceTest {
    @Test
    fun jdbcLogStorePersistsLogsAcrossStoreInstances() {
        val databaseUrl = h2DatabaseUrl()
        val accountId = setupAccountTables(databaseUrl)

        val firstStore = JdbcAiCategorizationLogStore(databaseUrl)
        firstStore.insertLog(
            StoredAiCategorizationLog(
                accountId = accountId,
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
        assertEquals(accountId, log.accountId)
        assertEquals("午餐", log.merchantTitle)
        assertEquals("餐饮", log.suggestedCategory)
        assertEquals(1000L, log.createdAtMillis)
    }

    @Test
    fun logsDeletedForAccountOnAccountDeletion() {
        val databaseUrl = h2DatabaseUrl()
        val accountId1 = setupAccountTables(databaseUrl)
        val store = JdbcAccountStore(databaseUrl)
        val accountId2 = store.findAccountByIdentifier("PHONE", "13900139000")!!.accountId

        val logStore = JdbcAiCategorizationLogStore(databaseUrl)
        logStore.insertLog(
            StoredAiCategorizationLog(
                accountId = accountId1,
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
                accountId = accountId2,
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

        logStore.deleteLogsForAccount(accountId1)

        assertEquals(0, logStore.logsForAccount(accountId1).size)
        assertEquals(1, logStore.logsForAccount(accountId2).size)
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

        runBlocking {
            service.suggest(
                accountId = 1L,
                request = AiCategorizationRequestContract(
                    merchantTitle = "午餐",
                    sourceLabel = "微信",
                    transactionKind = "支出",
                    amountRangeLabel = "0-50",
                    categoryCandidates = listOf("餐饮"),
                    enhancedContext = true,
                    note = "private note",
                    rawEvidenceText = "private evidence"
                ),
                enhancedContextAuthorized = true
            )
        }

        val logs = logStore.allLogs()
        assertEquals(1, logs.size)
        assertEquals("餐饮", logs.single().suggestedCategory)
    }

    private fun setupAccountTables(databaseUrl: String): Long {
        val store = JdbcAccountStore(databaseUrl)
        val firstAccount = requireNotNull(
            store.createAccountWithIdentifier(
                primaryIdentifierType = "PHONE",
                rawValue = "13800138000",
                normalizedValue = "13800138000",
                passwordSalt = "salt",
                passwordHash = "hash",
                verified = true,
                now = 1000
            )
        )
        requireNotNull(
            store.createAccountWithIdentifier(
                primaryIdentifierType = "PHONE",
                rawValue = "13900139000",
                normalizedValue = "13900139000",
                passwordSalt = "salt",
                passwordHash = "hash",
                verified = true,
                now = 1000
            )
        )
        return firstAccount.accountId
    }

    private fun h2DatabaseUrl(): String {
        return "jdbc:h2:mem:${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    }
}
