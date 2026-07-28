package com.autoaccounting.backend.account

import java.sql.DriverManager
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

        firstService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        val registered = firstService.registerIdentifier(
            "13800138000",
            "123456",
            "Aa123456!",
            "device-a",
            "127.0.0.1"
        )
            as AccountResult.Success<AccountToken>

        clock.advanceBy(10_000)
        val restartedService = accountService(databaseUrl, clock, tokens)

        val verifiedRegistered = restartedService.verifyToken(registered.value.token) as AccountResult.Success<AccountToken>
        assertEquals("13800138000", verifiedRegistered.value.phone)
        assertEquals(registered.value.token, verifiedRegistered.value.token)
        assertEquals(registered.value.accountId, verifiedRegistered.value.accountId)
        assertEquals(
            registered.value.accountUuid,
            UUID.fromString(requireNotNull(verifiedRegistered.value.accountUuid)).toString()
        )

        assertEquals(
            AccountError.SMS_TOO_FREQUENT,
            restartedService.issueVerificationCode("13800138000", "device-b", "127.0.0.2").error
        )

        clock.advanceBy(61_000)
        val login = restartedService.loginIdentifier("13800138000", "Aa123456!", "device-b", "127.0.0.2")
            as AccountResult.Success<AccountToken>

        val verifiedLogin = restartedService.verifyToken(login.value.token) as AccountResult.Success<AccountToken>
        assertEquals("13800138000", verifiedLogin.value.phone)
        assertEquals(login.value.token, verifiedLogin.value.token)
        assertEquals(login.value.accountId, verifiedLogin.value.accountId)
        assertEquals(verifiedRegistered.value.accountUuid, verifiedLogin.value.accountUuid)

        assertEquals(
            listOf("device-a", "device-b"),
            restartedService.registeredDevices(login.value.accountId).map { it.deviceId }
        )
    }

    @Test
    fun jdbcStorePersistsLoginLockoutAcrossServiceRestart() {
        val databaseUrl = h2DatabaseUrl()
        val clock = MutableClock(0)
        val firstService = accountService(databaseUrl, clock)
        firstService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        firstService.registerIdentifier("13800138000", "123456", "Aa123456!")

        repeat(4) {
            assertEquals(AccountError.LOGIN_FAILED, firstService.loginIdentifier("13800138000", "wrong").error)
        }

        val restartedService = accountService(databaseUrl, clock)
        assertEquals(AccountError.ACCOUNT_LOCKED, restartedService.loginIdentifier("13800138000", "wrong").error)
        assertEquals(AccountError.ACCOUNT_LOCKED, restartedService.loginIdentifier("13800138000", "Aa123456!").error)
    }

    @Test
    fun jdbcMigrationsCreateAccountTables() {
        val databaseUrl = h2DatabaseUrl()
        JdbcAccountStore(databaseUrl)

        DriverManager.getConnection(databaseUrl).use { connection ->
            val appliedMigrationCount = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM schema_migrations").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }

            val accountTableCount = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_name IN (
                        'accounts',
                        'account_password_credentials',
                        'account_identifiers',
                        'verification_codes',
                        'verification_code_send_logs',
                        'account_sessions',
                        'registered_devices',
                        'account_wechat_identities',
                        'account_one_time_tickets',
                        'account_profiles'
                    )
                    """.trimIndent()
                ).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }

            assertEquals(10, appliedMigrationCount)
            assertTrue(accountTableCount >= 9)
        }
    }

    @Test
    fun jdbcAccountProfilePersistsWithoutWechatIdentity() {
        val databaseUrl = h2DatabaseUrl()
        val store = JdbcAccountStore(databaseUrl)
        val service = AccountService(store = store, tokenGenerator = { "profile-token" })
        val registered = service.registerIdentifier(
            "profile_user",
            null,
            "Password123!"
        ) as AccountResult.Success

        val nicknameUpdated = service.updateNickname(
            registered.value.token,
            "持久昵称"
        ) as AccountResult.Success
        assertEquals("持久昵称", nicknameUpdated.value.nickname)
        assertFalse(nicknameUpdated.value.wechatLinked)

        val avatarUpdated = service.updateAvatar(
            registered.value.token,
            "data:image/jpeg;base64,/9j/"
        ) as AccountResult.Success
        assertEquals("data:image/jpeg;base64,/9j/", avatarUpdated.value.avatarUrl)

        val restartedService = AccountService(store = JdbcAccountStore(databaseUrl))
        val verified = restartedService.verifyToken(registered.value.token) as AccountResult.Success
        assertEquals("持久昵称", verified.value.nickname)
        assertEquals("data:image/jpeg;base64,/9j/", verified.value.avatarUrl)
        assertFalse(verified.value.wechatLinked)
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
                statement.executeQuery("SELECT COUNT(*) FROM verification_codes").use { result ->
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
                        if (table == "verification_codes" || table == "account_sessions") {
                            add(table to result.getString("COLUMN_NAME").lowercase())
                        }
                    }
                }
            }
            assertTrue("verification_codes" to "code_hash" in columns)
            assertTrue("account_sessions" to "token_hash" in columns)
        }
    }

    @Test
    fun newVerificationCodesAndSessionsPersistOnlyHashes() {
        val databaseUrl = h2DatabaseUrl()
        val service = accountService(databaseUrl, MutableClock(0))
        service.issueVerificationCode("13800138000", "device-a", "127.0.0.1")

        DriverManager.getConnection(databaseUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT code_hash FROM verification_codes").use { result ->
                    result.next()
                    assertNotEquals("123456", result.getString(1))
                }
            }
        }

        service.registerIdentifier("13800138000", "123456", "Aa123456!")

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
        val firstAccountId = createPhoneAccount(firstStore, "13800138001").accountId
        val secondAccountId = createPhoneAccount(secondStore, "13800138002").accountId
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

    @Test
    fun jdbcWechatRegistrationCanRetryAfterTokenGenerationFailure() {
        val databaseUrl = h2DatabaseUrl()
        val store = JdbcAccountStore(databaseUrl)
        val failingService = AccountService(
            store = store,
            tokenGenerator = { error("token generation failed") },
            wechatOAuthClient = FakeWechatOAuthClient(configured = true)
        )
        val exchange = failingService.exchangeWechatCode("good_code") as AccountResult.Success
        val ticket = (exchange.value.result as com.autoaccounting.api.WechatAuthResultContract.RegistrationRequired).wechatTicket

        assertThrows(IllegalStateException::class.java) {
            failingService.registerWithWechat(ticket)
        }

        val retryService = AccountService(
            store = JdbcAccountStore(databaseUrl),
            tokenGenerator = { "retry-token" },
            wechatOAuthClient = FakeWechatOAuthClient(configured = true)
        )
        val retry = retryService.registerWithWechat(ticket)
        assertTrue(retry is AccountResult.Success)
        assertTrue(retryService.verifyToken((retry as AccountResult.Success).value.token) is AccountResult.Success)
    }

    @Test
    fun jdbcWechatLinkCanRetryAfterTokenGenerationFailure() {
        val databaseUrl = h2DatabaseUrl()
        val store = JdbcAccountStore(databaseUrl)
        val setupService = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "setup-token" },
            wechatOAuthClient = FakeWechatOAuthClient(configured = true)
        )
        setupService.issueVerificationCode("13800138000", "device-a", "127.0.0.1")
        setupService.registerIdentifier("13800138000", "123456", "Aa123456!")
        val exchange = setupService.exchangeWechatCode("good_code") as AccountResult.Success
        val ticket = (exchange.value.result as com.autoaccounting.api.WechatAuthResultContract.RegistrationRequired).wechatTicket
        val failingService = AccountService(
            store = store,
            tokenGenerator = { error("token generation failed") },
            wechatOAuthClient = FakeWechatOAuthClient(configured = true)
        )

        assertThrows(IllegalStateException::class.java) {
            failingService.linkWechatWithPassword(ticket, "13800138000", "Aa123456!")
        }

        val retryService = AccountService(
            store = JdbcAccountStore(databaseUrl),
            tokenGenerator = { "retry-token" },
            wechatOAuthClient = FakeWechatOAuthClient(configured = true)
        )
        val retry = retryService.linkWechatWithPassword(ticket, "13800138000", "Aa123456!")
        assertTrue(retry is AccountResult.Success)
        assertNotNull(JdbcAccountStore(databaseUrl).findWechatIdentityByAccountId((retry as AccountResult.Success).value.accountId))
    }

    @Test
    fun jdbcStorePersistsWechatLinkSmsContextAsTicketHash() {
        val databaseUrl = h2DatabaseUrl()
        val store = JdbcAccountStore(databaseUrl)
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "654321" },
            wechatOAuthClient = FakeWechatOAuthClient(configured = true)
        )
        val exchange = service.exchangeWechatCode("good_code") as AccountResult.Success
        val ticket = (exchange.value.result as com.autoaccounting.api.WechatAuthResultContract.RegistrationRequired).wechatTicket

        val issued = service.issueVerificationCode(
            identifier = "13800138000",
            deviceId = "device-a",
            ipAddress = "127.0.0.1",
            purpose = "WECHAT_LINK",
            contextKey = ticket
        )

        assertTrue(issued is AccountResult.Success)
        val persisted = JdbcAccountStore(databaseUrl)
            .findVerificationCode("PHONE", "13800138000", "WECHAT_LINK")!!
        assertEquals("WECHAT_LINK", persisted.purpose)
        assertNotNull(persisted.contextKey)
        assertNotEquals(ticket, persisted.contextKey)
    }

    @Test
    fun jdbcPureWechatFirstIdentifierLinkRollsBackAllChangesWhenSessionCreationFails() {
        val databaseUrl = h2DatabaseUrl()
        val store = JdbcAccountStore(databaseUrl)
        val clock = MutableClock(0)
        val hasher = VerificationCodeHasher.fromSecret("test-verification-secret")
        var tokenIndex = 0
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            tokenGenerator = { "token-${++tokenIndex}" },
            verificationCodeHasher = hasher,
            clock = clock,
            wechatOAuthClient = FakeWechatOAuthClient(configured = true)
        )
        val exchange = service.exchangeWechatCode("good_code") as AccountResult.Success
        val registration = exchange.value.result as com.autoaccounting.api.WechatAuthResultContract.RegistrationRequired
        val wechatSession = service.registerWithWechat(registration.wechatTicket, "device-1") as AccountResult.Success
        val accountId = wechatSession.value.accountId
        val prepared = service.prepareIdentifierLink(
            wechatSession.value.token,
            "13800138000",
            "device-1"
        ) as AccountResult.Success
        val linkTicket = (
            prepared.value as com.autoaccounting.api.IdentifierLinkPrepareResponseContract.LinkTicketIssued
        ).linkTicket
        val failingService = AccountService(
            store = store,
            tokenGenerator = { error("forced session creation failure") },
            verificationCodeHasher = hasher,
            clock = clock
        )

        assertThrows(IllegalStateException::class.java) {
            failingService.confirmIdentifierLink(
                wechatSession.value.token,
                linkTicket,
                "123456",
                "device-1",
                password = "Password123!"
            )
        }

        assertEquals(null, store.findPasswordCredentialByAccountId(accountId))
        assertTrue(store.findIdentifiersByAccountId(accountId).isEmpty())
        assertTrue(service.verifyToken(wechatSession.value.token) is AccountResult.Success)
        assertNotNull(store.findVerificationCode("PHONE", "13800138000", "IDENTIFIER_LINK"))

        val retried = service.confirmIdentifierLink(
            wechatSession.value.token,
            linkTicket,
            "123456",
            "device-1",
            password = "Password123!"
        )
        assertTrue(retried is AccountResult.Success)
    }

    @Test
    fun jdbcIdentifierLinkTicketCanOnlyBeConsumedOnceConcurrently() {
        val databaseUrl = h2DatabaseUrl()
        val store = JdbcAccountStore(databaseUrl)
        val clock = MutableClock(0)
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            clock = clock
        )
        val registered = service.registerIdentifier("user_primary", null, "Password123!") as AccountResult.Success
        val prepared = service.prepareIdentifierLink(
            registered.value.token,
            "13800138000",
            "device-1"
        ) as AccountResult.Success
        val linkTicket = (
            prepared.value as com.autoaccounting.api.IdentifierLinkPrepareResponseContract.LinkTicketIssued
        ).linkTicket
        val ticketHash = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(linkTicket.toByteArray(Charsets.UTF_8))
        )
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val attempts = (1..2).map { index ->
            executor.submit<AccountResult<AccountToken>> {
                ready.countDown()
                assertTrue(start.await(5, TimeUnit.SECONDS))
                JdbcAccountStore(databaseUrl).completeIdentifierLink(
                    ticketHash = ticketHash,
                    accountId = registered.value.accountId,
                    identifierType = "PHONE",
                    rawValue = "13800138000",
                    normalizedValue = "13800138000",
                    newPasswordSalt = null,
                    newPasswordHash = null,
                    deviceId = "device-$index",
                    ipAddress = "127.0.0.$index",
                    now = clock.millis(),
                    tokenGenerator = { "concurrent-token-$index" }
                )
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        val results = attempts.map { it.get(10, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(1, results.count { it is AccountResult.Success })
        assertEquals(
            1,
            store.findIdentifiersByAccountId(registered.value.accountId).count { it.identifierType == "PHONE" }
        )
        assertNotNull(store.findOneTimeTicket(ticketHash)?.usedAtMillis)
        assertEquals(null, store.findVerificationCode("PHONE", "13800138000", "IDENTIFIER_LINK"))
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

    private fun createPhoneAccount(store: AccountStore, phone: String): StoredAccount {
        return requireNotNull(
            store.createAccountWithIdentifier(
                primaryIdentifierType = "PHONE",
                rawValue = phone,
                normalizedValue = phone,
                passwordSalt = "salt",
                passwordHash = "hash",
                verified = true,
                now = 1000L
            )
        )
    }
}
