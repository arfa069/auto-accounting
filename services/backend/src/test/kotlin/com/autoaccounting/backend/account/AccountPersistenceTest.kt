package com.autoaccounting.backend.account

import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPersistenceTest {
    @Test
    fun environmentBootstrapRequiresDatabaseUrl() {
        val error = assertThrows(IllegalStateException::class.java) {
            AccountService.fromEnvironment(emptyMap())
        }

        assertTrue(error.message.orEmpty().contains("AUTO_ACCOUNTING_DATABASE_URL"))
    }

    @Test
    fun jdbcStorePersistsAuthSmsSessionsAndRegisteredDevicesAcrossServiceInstances() {
        val databaseUrl = h2DatabaseUrl()
        val clock = MutableClock(0)
        var tokenIndex = 0
        val tokens = { "token-${++tokenIndex}" }
        val firstService = accountService(databaseUrl, clock, tokens)

        firstService.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        val registered = firstService.register("13800138000", "123456", "Aa123456!")
            as AccountResult.Success<AccountToken>

        clock.advanceBy(10_000)
        val restartedService = accountService(databaseUrl, clock, tokens)

        assertEquals(
            AccountResult.Success(AccountToken("13800138000", registered.value.token)),
            restartedService.verifyToken(registered.value.token)
        )
        assertEquals(
            AccountError.SMS_TOO_FREQUENT,
            restartedService.issueSmsCode("13800138000", "device-b", "127.0.0.2").error
        )

        clock.advanceBy(61_000)
        val login = restartedService.login("13800138000", "Aa123456!", "device-b", "127.0.0.2")
            as AccountResult.Success<AccountToken>

        assertEquals(
            AccountResult.Success(AccountToken("13800138000", login.value.token)),
            restartedService.verifyToken(login.value.token)
        )
        assertEquals(
            listOf("device-a", "device-b"),
            restartedService.registeredDevices("13800138000").map { it.deviceId }
        )
    }

    @Test
    fun jdbcStorePersistsLoginLockoutAcrossServiceRestart() {
        val databaseUrl = h2DatabaseUrl()
        val clock = MutableClock(0)
        val firstService = accountService(databaseUrl, clock)
        firstService.issueSmsCode("13800138000", "device-a", "127.0.0.1")
        firstService.register("13800138000", "123456", "Aa123456!")

        repeat(4) {
            assertEquals(AccountError.LOGIN_FAILED, firstService.login("13800138000", "wrong").error)
        }

        val restartedService = accountService(databaseUrl, clock)
        assertEquals(AccountError.ACCOUNT_LOCKED, restartedService.login("13800138000", "wrong").error)
        assertEquals(AccountError.ACCOUNT_LOCKED, restartedService.login("13800138000", "Aa123456!").error)
    }

    @Test
    fun jdbcMigrationsCreateAccountTables() {
        val databaseUrl = h2DatabaseUrl()
        JdbcAccountStore(databaseUrl)

        DriverManager.getConnection(databaseUrl).use { connection ->
            val appliedMigrationCount = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM schema_migrations WHERE version = 1").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
            val accountTableCount = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_name IN (
                        'account_users',
                        'account_password_credentials',
                        'account_sms_codes',
                        'account_sms_issues',
                        'account_sessions',
                        'registered_devices'
                    )
                    """.trimIndent()
                ).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }

            assertEquals(1, appliedMigrationCount)
            assertTrue(accountTableCount >= 6)
        }
    }

    private fun accountService(
        databaseUrl: String,
        clock: MutableClock,
        tokenGenerator: () -> String = { "token-1" }
    ): AccountService {
        return AccountService(
            store = JdbcAccountStore(databaseUrl),
            smsCodeGenerator = { "123456" },
            tokenGenerator = tokenGenerator,
            clock = clock
        )
    }

    private fun h2DatabaseUrl(): String {
        return "jdbc:h2:mem:${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    }
}
