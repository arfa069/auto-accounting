package com.autoaccounting.backend.account

import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun environmentBootstrapRequiresStrongAuthPepper() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AccountService.fromEnvironment(
                mapOf(
                    "AUTO_ACCOUNTING_DATABASE_URL" to h2DatabaseUrl(),
                    "AUTO_ACCOUNTING_AUTH_PEPPER" to "too-short"
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("AUTO_ACCOUNTING_AUTH_PEPPER"))
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

        val verifiedRegistered = restartedService.verifyToken(registered.value.token) as AccountResult.Success<AccountToken>
        assertEquals("13800138000", verifiedRegistered.value.phone)
        assertEquals(registered.value.token, verifiedRegistered.value.token)
        assertEquals(registered.value.accountId, verifiedRegistered.value.accountId)

        assertEquals(
            AccountError.SMS_TOO_FREQUENT,
            restartedService.issueSmsCode("13800138000", "device-b", "127.0.0.2").error
        )

        clock.advanceBy(61_000)
        val login = restartedService.login("13800138000", "Aa123456!", "device-b", "127.0.0.2")
            as AccountResult.Success<AccountToken>

        val verifiedLogin = restartedService.verifyToken(login.value.token) as AccountResult.Success<AccountToken>
        assertEquals("13800138000", verifiedLogin.value.phone)
        assertEquals(login.value.token, verifiedLogin.value.token)
        assertEquals(login.value.accountId, verifiedLogin.value.accountId)

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
                statement.executeQuery("SELECT COUNT(*) FROM schema_migrations WHERE version = 5").use { rs ->
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
                        'accounts',
                        'account_phone_credentials',
                        'account_wechat_identities',
                        'account_one_time_tickets',
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
            assertTrue(accountTableCount >= 8)
        }
    }

    @Test
    fun migrationVersion4ClearsLegacyCredentialsAndRenamesSensitiveColumns() {
        val databaseUrl = h2DatabaseUrl()
        DriverManager.getConnection(databaseUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at_millis BIGINT NOT NULL)"
                )
                statement.execute("INSERT INTO schema_migrations VALUES (1, 0)")
                statement.execute(
                    """
                    CREATE TABLE account_users (
                        phone VARCHAR(32) PRIMARY KEY,
                        failed_login_count INTEGER NOT NULL,
                        locked_until_millis BIGINT NOT NULL,
                        deletion_requested_at_millis BIGINT,
                        created_at_millis BIGINT NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE account_password_credentials (
                        phone VARCHAR(32) PRIMARY KEY REFERENCES account_users(phone) ON DELETE CASCADE,
                        password_salt TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        updated_at_millis BIGINT NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE account_sms_codes (
                        phone VARCHAR(32) PRIMARY KEY,
                        code VARCHAR(16) NOT NULL,
                        expires_at_millis BIGINT NOT NULL,
                        failed_attempts INTEGER NOT NULL,
                        invalidated BOOLEAN NOT NULL,
                        device_id TEXT NOT NULL,
                        ip_address TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    "CREATE TABLE account_sessions (token TEXT PRIMARY KEY, phone VARCHAR(32) NOT NULL REFERENCES account_users(phone) ON DELETE CASCADE, device_id TEXT NOT NULL, issued_at_millis BIGINT NOT NULL)"
                )
                statement.execute(
                    """
                    CREATE TABLE registered_devices (
                        phone VARCHAR(32) NOT NULL REFERENCES account_users(phone) ON DELETE CASCADE,
                        device_id TEXT NOT NULL,
                        first_seen_at_millis BIGINT NOT NULL,
                        last_seen_at_millis BIGINT NOT NULL,
                        ip_address TEXT NOT NULL,
                        PRIMARY KEY (phone, device_id)
                    )
                    """.trimIndent()
                )
                statement.execute(
                    "INSERT INTO account_users VALUES ('13800138000', 0, 0, NULL, 0)"
                )
                statement.execute(
                    "INSERT INTO account_password_credentials VALUES ('13800138000', 'salt', 'hash', 0)"
                )
                statement.execute(
                    "INSERT INTO account_sms_codes VALUES ('13800138000', '123456', 1000, 0, FALSE, 'device-a', '127.0.0.1')"
                )
                statement.execute(
                    "INSERT INTO account_sessions VALUES ('raw-token', '13800138000', 'device-a', 0)"
                )
            }
        }

        JdbcAccountStore(databaseUrl)

        DriverManager.getConnection(databaseUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM account_sms_codes").use { result ->
                    result.next()
                    assertEquals(0, result.getInt(1))
                }
                statement.executeQuery("SELECT COUNT(*) FROM account_sessions").use { result ->
                    result.next()
                    assertEquals(0, result.getInt(1))
                }
            }
            val columns = connection.metaData.getColumns(null, null, null, null).use { result ->
                buildSet {
                    while (result.next()) {
                        val table = result.getString("TABLE_NAME").lowercase()
                        if (table == "account_sms_codes" || table == "account_sessions") {
                            add(table to result.getString("COLUMN_NAME").lowercase())
                        }
                    }
                }
            }
            assertTrue("account_sms_codes" to "code_hash" in columns)
            assertTrue("account_sessions" to "token_hash" in columns)
            assertFalse("account_sms_codes" to "code" in columns)
            assertFalse("account_sessions" to "token" in columns)
        }
    }

    @Test
    fun newVerificationCodesAndSessionsPersistOnlyHashes() {
        val databaseUrl = h2DatabaseUrl()
        val service = accountService(databaseUrl, MutableClock(0))
        service.issueSmsCode("13800138000", "device-a", "127.0.0.1")

        DriverManager.getConnection(databaseUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT code_hash FROM account_sms_codes").use { result ->
                    result.next()
                    assertNotEquals("123456", result.getString(1))
                }
            }
        }

        service.register("13800138000", "123456", "Aa123456!")

        DriverManager.getConnection(databaseUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT token_hash FROM account_sessions").use { result ->
                    result.next()
                    assertNotEquals("token-1", result.getString(1))
                }
            }
        }
    }

    @Test
    fun jdbcStoreAtomicallyClaimsWechatIdentity() {
        val databaseUrl = h2DatabaseUrl()
        val firstStore = JdbcAccountStore(databaseUrl)
        val secondStore = JdbcAccountStore(databaseUrl)
        firstStore.createUser(storedUser("13800138001"))
        secondStore.createUser(storedUser("13800138002"))
        val firstAccountId = firstStore.findUser("13800138001")!!.accountId
        val secondAccountId = secondStore.findUser("13800138002")!!.accountId
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val results = listOf(
                submitWechatClaim(executor, ready, start, firstStore, firstAccountId),
                submitWechatClaim(executor, ready, start, secondStore, secondAccountId)
            )
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val claimResults = results.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, claimResults.count { it is WechatIdentityClaimResult.Claimed })
            assertEquals(1, claimResults.count { it is WechatIdentityClaimResult.Conflict })
            assertEquals(1, wechatIdentityCount(databaseUrl))
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private fun submitWechatClaim(
        executor: ExecutorService,
        ready: CountDownLatch,
        start: CountDownLatch,
        store: AccountStore,
        accountId: Long
    ): Future<WechatIdentityClaimResult> {
        return executor.submit<WechatIdentityClaimResult> {
            ready.countDown()
            start.await()
            store.claimWechatIdentity(
                StoredWechatIdentity(
                    accountId = accountId,
                    appId = "wx_app",
                    openid = "shared_openid",
                    unionid = "shared_unionid",
                    createdAtMillis = 1000L,
                    updatedAtMillis = 1000L
                )
            )
        }
    }

    private fun wechatIdentityCount(databaseUrl: String): Int {
        return DriverManager.getConnection(databaseUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM account_wechat_identities").use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
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
            verificationCodeHasher = VerificationCodeHasher.forTests(),
            clock = clock
        )
    }

    private fun h2DatabaseUrl(): String {
        return "jdbc:h2:mem:${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    }

    private fun storedUser(phone: String): StoredUser {
        return StoredUser(
            accountId = 0L,
            phone = phone,
            passwordSalt = "salt",
            passwordHash = "hash",
            createdAtMillis = 1000L
        )
    }
}
