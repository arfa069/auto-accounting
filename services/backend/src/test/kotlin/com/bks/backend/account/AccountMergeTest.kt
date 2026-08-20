package com.bks.backend.account

import com.bks.api.AccountApiJsonContracts
import com.bks.api.LedgerSyncEntityTypeContract
import com.bks.api.LedgerSyncMutationContract
import com.bks.api.LedgerSyncPayloadContract
import com.bks.api.WechatAuthResultContract
import com.bks.backend.ai.JdbcAiCategorizationLogStore
import com.bks.backend.ai.StoredAiCategorizationLog
import com.bks.backend.config.CloudConfigService
import com.bks.backend.config.CloudConfigUpdate
import com.bks.backend.config.JdbcCloudConfigStore
import com.bks.backend.module
import com.bks.backend.sync.JdbcLedgerSyncStore
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountMergeTest {

    private fun createClock() = MutableClock(1774262400000L)

    private fun h2DatabaseUrl(): String {
        return "jdbc:h2:mem:account_merge_${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    }

    @Suppress("LongParameterList")
    private fun registerPhoneUser(
        service: AccountService,
        clock: MutableClock,
        phone: String,
        code: String = "123456",
        password: String = "Pass123456!",
        deviceId: String = "dev-1"
    ): AccountToken {
        clock.advanceBy(60_001)
        val smsRes = service.issueVerificationCode(phone, deviceId, "127.0.0.1")
        assertTrue("SMS issue should succeed for $phone: ${(smsRes as? AccountResult.Failure)?.error}", smsRes is AccountResult.Success)
        val regRes = service.registerIdentifier(phone, code, password, deviceId)
        assertTrue("Register phone user $phone should succeed: ${(regRes as? AccountResult.Failure)?.error}", regRes is AccountResult.Success)
        return (regRes as AccountResult.Success).value
    }

    @Test
    fun testWechatTargetMergesPhoneSourceWithPasswordPreparation() {
        val store = InMemoryAccountStore()
        val clock = createClock()
        val fakeOAuth = FakeWechatOAuthClient(
            userInfoResult = WechatOAuthResult.Success(
                WechatUserInfoResponse("fake_openid", "TargetWxUser", "https://img/target.jpg", "fake_unionid")
            )
        )
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            clock = clock,
            wechatOAuthClient = fakeOAuth
        )

        // 1. Create phone account (source)
        val sourcePhoneToken = registerPhoneUser(service, clock, "13800138000", deviceId = "dev-1")
        val sourceAccountId = sourcePhoneToken.accountId

        // 2. Create pure WeChat account (target)
        val exchangeRes = service.exchangeWechatCode("good_code", null, "dev-2")
        assertTrue(exchangeRes is AccountResult.Success)
        val regRequired = (exchangeRes as AccountResult.Success).value.result as WechatAuthResultContract.RegistrationRequired

        val targetTokenRes = service.registerWithWechat(regRequired.wechatTicket, "dev-2")
        assertTrue(targetTokenRes is AccountResult.Success)
        val targetToken = (targetTokenRes as AccountResult.Success).value
        val targetAccountId = targetToken.accountId

        // 3. Prepare merge with phone & password
        val prepareRes = service.prepareMergeWithIdentifierPassword(
            bearerToken = targetToken.token,
            identifier = "13800138000",
            password = "Pass123456!"
        )
        assertTrue("Prepare merge should succeed", prepareRes is AccountResult.Success)
        val preview = (prepareRes as AccountResult.Success).value
        assertTrue("Target identifiers should be empty", preview.currentIdentifiers.isEmpty())
        assertTrue("Target WeChat should be linked", preview.currentWechatLinked)
        assertEquals(
            "Source phone matches",
            "13800138000",
            preview.sourceIdentifiers.single { it.type.name == "PHONE" }.value
        )
        assertFalse("Source WeChat is not linked", preview.sourceWechatLinked)

        // 4. Confirm merge
        val confirmRes = service.confirmMerge(
            bearerToken = targetToken.token,
            mergeTicket = preview.mergeTicket,
            confirmText = "合并账号",
            deviceId = "dev-2"
        )
        assertTrue("Confirm merge should succeed", confirmRes is AccountResult.Success)
        val mergedToken = (confirmRes as AccountResult.Success).value
        assertEquals("Target account ID preserved", targetAccountId, mergedToken.accountId)
        assertEquals("Target now has source phone", "13800138000", mergedToken.phone)
        assertEquals("PHONE", mergedToken.primaryIdentifier?.type?.name)
        assertTrue("Target still has WeChat linked", mergedToken.wechatLinked)
        assertEquals("Target nickname preserved", "TargetWxUser", mergedToken.nickname)

        // 5. Source account is deleted
        assertNull("Source account should be deleted", store.findAccount(sourceAccountId))
    }

    @Test
    fun passwordMergeFailurePreservesExistingExpiredLockDeadline() {
        val store = InMemoryAccountStore()
        val clock = createClock()
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            clock = clock,
            wechatOAuthClient = FakeWechatOAuthClient(configured = true)
        )

        val sourceToken = registerPhoneUser(service, clock, "13800138000")
        val sourceAccountId = sourceToken.accountId
        val sourceCredential = store.findPasswordCredentialByAccountId(sourceAccountId)!!
        val existingLockDeadline = clock.millis() - 1
        store.updatePasswordCredential(
            sourceCredential.copy(
                lockedUntilMillis = existingLockDeadline,
                updatedAtMillis = clock.millis()
            )
        )
        clock.advanceBy(1)

        val exchange = service.exchangeWechatCode("good_code", null, "dev-2") as AccountResult.Success
        val targetTicket = (exchange.value.result as WechatAuthResultContract.RegistrationRequired).wechatTicket
        val targetToken = (service.registerWithWechat(targetTicket, "dev-2") as AccountResult.Success).value

        assertEquals(
            AccountResult.Failure(AccountError.LOGIN_FAILED),
            service.prepareMergeWithIdentifierPassword(
                bearerToken = targetToken.token,
                identifier = "13800138000",
                password = "WrongPass123!"
            )
        )
        val afterFailure = store.findPasswordCredentialByAccountId(sourceAccountId)!!
        assertEquals(1, afterFailure.failedLoginCount)
        assertEquals(existingLockDeadline, afterFailure.lockedUntilMillis)
        assertEquals(clock.millis(), afterFailure.updatedAtMillis)
    }

    @Test
    fun testPhoneTargetMergesPureWechatSourceViaExchange() {
        val store = InMemoryAccountStore()
        val clock = createClock()
        val fakeOAuth = FakeWechatOAuthClient(
            exchangeResult = WechatOAuthResult.Success(
                WechatTokenResponse("fake_access_token", "fake_openid", "fake_unionid")
            ),
            userInfoResult = WechatOAuthResult.Success(
                WechatUserInfoResponse("fake_openid", "SourceWxUser", "https://img/source.jpg", "fake_unionid")
            )
        )
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            clock = clock,
            wechatOAuthClient = fakeOAuth
        )

        // 1. Create pure WeChat source account
        val exchange1 = service.exchangeWechatCode("good_code", null, "dev-wx")
        assertTrue(exchange1 is AccountResult.Success)
        val regRequired = (exchange1 as AccountResult.Success).value.result as WechatAuthResultContract.RegistrationRequired
        service.registerWithWechat(regRequired.wechatTicket, "dev-wx")

        // 2. Create phone target account
        val targetPhoneToken = registerPhoneUser(service, clock, "13900139000", deviceId = "dev-target")

        // 3. Exchange WeChat code while logged in as phone target -> triggers MERGE_REQUIRED
        val exchange2 = service.exchangeWechatCode("good_code", targetPhoneToken.token, "dev-target")
        assertTrue(exchange2 is AccountResult.Success)
        val mergeReq = (exchange2 as AccountResult.Success).value.result as WechatAuthResultContract.MergeRequired
        assertTrue("Source pure WeChat account has no identifiers", mergeReq.sourceIdentifiers.isEmpty())

        // 4. Confirm merge
        val confirmRes = service.confirmMerge(
            bearerToken = targetPhoneToken.token,
            mergeTicket = mergeReq.mergeTicket,
            confirmText = "MERGE_ACCOUNT",
            deviceId = "dev-target"
        )
        assertTrue("Confirm merge succeeds", confirmRes is AccountResult.Success)
        val mergedToken = (confirmRes as AccountResult.Success).value
        assertEquals("Target account ID preserved", targetPhoneToken.accountId, mergedToken.accountId)
        assertEquals("Phone preserved", "13900139000", mergedToken.phone)
        assertTrue("WeChat linked to target", mergedToken.wechatLinked)
        assertEquals("Source nickname transferred", "SourceWxUser", mergedToken.nickname)
    }

    @Test
    fun testMergeBlocksWhenBothAccountsHaveConflictingCredentials() {
        val store = InMemoryAccountStore()
        val clock = createClock()

        val fakeOAuth1 = FakeWechatOAuthClient(
            exchangeResult = WechatOAuthResult.Success(WechatTokenResponse("t1", "openid_1", "unionid_1")),
            userInfoResult = WechatOAuthResult.Success(WechatUserInfoResponse("openid_1", "User1", null, "unionid_1"))
        )
        val service1 = AccountService(store = store, smsCodeGenerator = { "123456" }, clock = clock, wechatOAuthClient = fakeOAuth1)

        // Account 1: Phone + WeChat 1
        val t1 = registerPhoneUser(service1, clock, "13800000001", deviceId = "d1")
        val ex1 = service1.exchangeWechatCode("code_1", t1.token, "d1")
        assertTrue(ex1 is AccountResult.Success)
        assertTrue((ex1 as AccountResult.Success).value.result is WechatAuthResultContract.SignedIn)

        // Account 2: Phone + WeChat 2
        val fakeOAuth2 = FakeWechatOAuthClient(
            exchangeResult = WechatOAuthResult.Success(WechatTokenResponse("t2", "openid_2", "unionid_2")),
            userInfoResult = WechatOAuthResult.Success(WechatUserInfoResponse("openid_2", "User2", null, "unionid_2"))
        )
        val service2 = AccountService(store = store, smsCodeGenerator = { "123456" }, clock = clock, wechatOAuthClient = fakeOAuth2)
        val t2 = registerPhoneUser(service2, clock, "13800000002", deviceId = "d2")

        val ex2 = service2.exchangeWechatCode("code_2", t2.token, "d2")
        assertTrue(ex2 is AccountResult.Success)
        assertTrue((ex2 as AccountResult.Success).value.result is WechatAuthResultContract.SignedIn)

        // Attempting password prepare with Account 2 from Account 1
        val prepareRes = service1.prepareMergeWithIdentifierPassword(t1.token, "13800000002", "Pass123456!")
        assertTrue("Merge is blocked due to duplicate phone and wechat credentials", prepareRes is AccountResult.Failure)
        assertEquals(AccountError.MERGE_BLOCKED, (prepareRes as AccountResult.Failure).error)
    }

    @Test
    fun jdbcMergeBlocksIdentifierTypeConflictBeforeTransfer() {
        val store = JdbcAccountStore(h2DatabaseUrl())
        val clock = createClock()
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            clock = clock,
            wechatOAuthClient = FakeWechatOAuthClient()
        )
        val source = registerPhoneUser(service, clock, "13800000001", deviceId = "source")
        val exchange = service.exchangeWechatCode("code_wx", null, "target") as AccountResult.Success
        val registration = exchange.value.result as WechatAuthResultContract.RegistrationRequired
        val target = (service.registerWithWechat(registration.wechatTicket, "target") as AccountResult.Success).value
        assertTrue(
            store.addIdentifierToAccount(
                accountId = target.accountId,
                identifierType = "PHONE",
                rawValue = "13900000001",
                normalizedValue = "13900000001",
                verified = true,
                now = clock.millis()
            )
        )

        val preview = service.prepareMergeWithIdentifierPassword(
            target.token,
            "13800000001",
            "Pass123456!"
        ) as AccountResult.Success
        val result = service.confirmMerge(target.token, preview.value.mergeTicket, "合并账号")

        assertEquals(AccountError.MERGE_BLOCKED, (result as AccountResult.Failure).error)
        assertNotNull(store.findAccount(source.accountId))
        assertNotNull(store.findAccount(target.accountId))
        assertEquals("13800000001", store.findIdentifiersByAccountId(source.accountId).single().rawValue)
        assertEquals("13900000001", store.findIdentifiersByAccountId(target.accountId).single().rawValue)
    }

    @Test
    fun testMergeBlocksDuringAccountDeletionCoolingOffPeriod() {
        val store = InMemoryAccountStore()
        val clock = createClock()
        val fakeOAuth = FakeWechatOAuthClient()
        val service = AccountService(store = store, smsCodeGenerator = { "123456" }, clock = clock, wechatOAuthClient = fakeOAuth)

        val t1 = registerPhoneUser(service, clock, "13800000001", deviceId = "d1")

        // Register pure WeChat target
        val exRes = service.exchangeWechatCode("code_wx", null, "d2")
        assertTrue(exRes is AccountResult.Success)
        val ex = (exRes as AccountResult.Success).value.result as WechatAuthResultContract.RegistrationRequired

        val t2Res = service.registerWithWechat(ex.wechatTicket, "d2")
        assertTrue(t2Res is AccountResult.Success)
        val t2 = (t2Res as AccountResult.Success).value

        // Source account requests deletion
        service.requestAccountDeletion(t1.token)

        // Prepare merge should fail with ACCOUNT_DELETION_PENDING
        val prepareRes = service.prepareMergeWithIdentifierPassword(t2.token, "13800000001", "Pass123456!")
        assertTrue("Prepare merge fails when deletion pending", prepareRes is AccountResult.Failure)
        assertEquals(AccountError.ACCOUNT_DELETION_PENDING, (prepareRes as AccountResult.Failure).error)
    }

    @Test
    fun testMergeConfirmValidatesConfirmTextAndSingleTicketConsumption() {
        val store = InMemoryAccountStore()
        val clock = createClock()
        val fakeOAuth = FakeWechatOAuthClient()
        val service = AccountService(store = store, smsCodeGenerator = { "123456" }, clock = clock, wechatOAuthClient = fakeOAuth)

        registerPhoneUser(service, clock, "13800000001", deviceId = "d1")

        val exRes = service.exchangeWechatCode("code_wx", null, "d2")
        assertTrue(exRes is AccountResult.Success)
        val ex = (exRes as AccountResult.Success).value.result as WechatAuthResultContract.RegistrationRequired

        val t2Res = service.registerWithWechat(ex.wechatTicket, "d2")
        assertTrue(t2Res is AccountResult.Success)
        val t2 = (t2Res as AccountResult.Success).value

        val prepareRes = service.prepareMergeWithIdentifierPassword(t2.token, "13800000001", "Pass123456!")
        assertTrue(prepareRes is AccountResult.Success)
        val preview = (prepareRes as AccountResult.Success).value

        // Wrong confirm text -> INVALID_REQUEST
        val wrongConfirm = service.confirmMerge(t2.token, preview.mergeTicket, "WRONG_TEXT")
        assertTrue(wrongConfirm is AccountResult.Failure)
        assertEquals(AccountError.INVALID_REQUEST, (wrongConfirm as AccountResult.Failure).error)

        // Correct confirm -> Success
        val successConfirm = service.confirmMerge(t2.token, preview.mergeTicket, "MERGE_ACCOUNT")
        assertTrue(successConfirm is AccountResult.Success)
        val newSessionToken = (successConfirm as AccountResult.Success).value.token

        // Repeat confirm with same ticket using new rotated session token -> TICKET_ALREADY_USED
        val repeatConfirm = service.confirmMerge(newSessionToken, preview.mergeTicket, "MERGE_ACCOUNT")
        assertTrue(repeatConfirm is AccountResult.Failure)
        assertEquals(AccountError.TICKET_ALREADY_USED, (repeatConfirm as AccountResult.Failure).error)
    }

    @Test
    fun testCloudConfigAndDevicesAreProperlyMergedAndAiLogsDeleted() {
        val databaseUrl = h2DatabaseUrl()
        val accountStore = JdbcAccountStore(databaseUrl)
        val cloudStore = JdbcCloudConfigStore(databaseUrl)
        val aiStore = JdbcAiCategorizationLogStore(databaseUrl)
        val clock = createClock()
        val fakeOAuth = FakeWechatOAuthClient()

        val service = AccountService(store = accountStore, smsCodeGenerator = { "123456" }, clock = clock, wechatOAuthClient = fakeOAuth)
        val configService = CloudConfigService(store = cloudStore, accountService = service)

        // Source Phone Account
        val t1 = registerPhoneUser(service, clock, "13800000001", deviceId = "source-device")
        val sourceId = t1.accountId
        accountStore.addIdentifierToAccount(
            sourceId,
            "EMAIL",
            "source@example.com",
            "source@example.com",
            true,
            clock.millis()
        )
        accountStore.upsertVerificationCode(
            StoredVerificationCode("PHONE", "13800000001", "RECOVERY", "hash-phone", Long.MAX_VALUE)
        )
        accountStore.upsertVerificationCode(
            StoredVerificationCode("EMAIL", "source@example.com", "RECOVERY", "hash-email", Long.MAX_VALUE)
        )

        // Target WeChat Account
        val exRes = service.exchangeWechatCode("code_wx", null, "target-device")
        assertTrue(exRes is AccountResult.Success)
        val ex = (exRes as AccountResult.Success).value.result as WechatAuthResultContract.RegistrationRequired

        val t2Res = service.registerWithWechat(ex.wechatTicket, "target-device")
        assertTrue(t2Res is AccountResult.Success)
        val t2 = (t2Res as AccountResult.Success).value
        val targetId = t2.accountId

        accountStore.upsertRegisteredDevice(
            StoredRegisteredDevice(sourceId, "device-shared", 100L, 400L, "source-latest")
        )
        accountStore.upsertRegisteredDevice(
            StoredRegisteredDevice(targetId, "device-shared", 200L, 300L, "target-older")
        )

        // Write Source Cloud Config
        configService.mergeAndWriteConfig(sourceId, CloudConfigUpdate(aiConsentGranted = true, featureFlags = mapOf("flagA" to true, "flagB" to false)))
        // Write Target Cloud Config
        configService.mergeAndWriteConfig(targetId, CloudConfigUpdate(aiConsentGranted = false, featureFlags = mapOf("flagB" to true, "flagC" to true)))

        // Insert Source AI Log
        aiStore.insertLog(StoredAiCategorizationLog(accountId = sourceId, merchantTitle = "M", sourceLabel = "S", transactionKind = "K", amountRangeLabel = "A", suggestedCategory = "C", confidenceLabel = "H", explanation = "E", createdAtMillis = clock.millis()))

        val prepareRes = service.prepareMergeWithIdentifierPassword(t2.token, "13800000001", "Pass123456!")
        assertTrue(prepareRes is AccountResult.Success)
        val preview = (prepareRes as AccountResult.Success).value

        // Execute merge
        val confirmRes = service.confirmMerge(t2.token, preview.mergeTicket, "合并账号", "confirm-device")
        assertTrue(confirmRes is AccountResult.Success)
        val merged = (confirmRes as AccountResult.Success).value
        assertEquals("PHONE", merged.primaryIdentifier?.type?.name)
        assertNull(accountStore.findVerificationCode("PHONE", "13800000001", "RECOVERY"))
        assertNull(accountStore.findVerificationCode("EMAIL", "source@example.com", "RECOVERY"))

        val mergedConfig = cloudStore.findConfig(targetId)
        assertNotNull("Target cloud config should remain", mergedConfig)
        assertFalse("Target AI consent should win", mergedConfig!!.aiConsentGranted)
        assertEquals(
            mapOf("flagA" to true, "flagB" to true, "flagC" to true),
            mergedConfig.featureFlags
        )
        assertEquals(null, cloudStore.findConfig(sourceId))

        val mergedDevices = accountStore.registeredDevices(targetId).associateBy { it.deviceId }
        val sharedDevice = mergedDevices["device-shared"]
        assertNotNull("Shared device should be retained", sharedDevice)
        assertEquals(100L, sharedDevice!!.firstSeenAtMillis)
        assertEquals(400L, sharedDevice.lastSeenAtMillis)
        assertEquals("source-latest", sharedDevice.ipAddress)
        assertTrue("Source-only device should transfer", "source-device" in mergedDevices)
        assertTrue("Confirmation device should be registered", "confirm-device" in mergedDevices)

        assertTrue("Source AI logs deleted", aiStore.logsForAccount(sourceId).isEmpty())
    }

    @Test
    fun jdbcMergeUsesTargetBusinessIdsRemapsEntriesAndPreservesDifferentCandidates() {
        val databaseUrl = h2DatabaseUrl()
        val accountStore = JdbcAccountStore(databaseUrl)
        val syncStore = JdbcLedgerSyncStore(databaseUrl)
        val clock = createClock()
        val service = AccountService(
            store = accountStore,
            smsCodeGenerator = { "123456" },
            clock = clock,
            wechatOAuthClient = FakeWechatOAuthClient()
        )
        val source = registerPhoneUser(service, clock, "13800000003", deviceId = "source-device")
        val exchange = service.exchangeWechatCode("code_wx", null, "target-device") as AccountResult.Success
        val registration = exchange.value.result as WechatAuthResultContract.RegistrationRequired
        val target = (service.registerWithWechat(
            registration.wechatTicket,
            "target-device"
        ) as AccountResult.Success).value
        val targetProfile = syncStore.getOrCreateProfile(target.accountId, 100)
        syncStore.getOrCreateProfile(source.accountId, 100)
        syncStore.push(
            target.accountId,
            "target-device",
            listOf(
                categorySyncMutation("target-food", "餐饮", "EXPENSE", "target-food-mutation"),
                categorySyncMutation("target-transit", "交通", "EXPENSE", "target-transit-mutation"),
                fundingSyncMutation("target-cash", "现金", "target-cash-mutation")
            ),
            200
        )
        syncStore.push(
            source.accountId,
            "source-device",
            listOf(
                categorySyncMutation("source-food", "餐饮", "EXPENSE", "source-food-mutation"),
                categorySyncMutation("source-transit", "交通", "INCOME", "source-transit-mutation"),
                fundingSyncMutation("source-cash", "现金", "source-cash-mutation"),
                entrySyncMutation("source-entry", "source-food", "source-cash", "source-entry-mutation")
            ),
            300
        )

        val preview = service.prepareMergeWithIdentifierPassword(
            target.token,
            "13800000003",
            "Pass123456!"
        ) as AccountResult.Success
        val merged = service.confirmMerge(target.token, preview.value.mergeTicket, "合并账号")

        assertTrue(merged is AccountResult.Success)
        assertEquals(targetProfile.profileKey, syncStore.getOrCreateProfile(target.accountId, 500).profileKey)
        assertEquals(0, syncStore.recordCount(source.accountId))
        val snapshot = syncStore.snapshot(target.accountId, 0, 100)
        assertTrue(snapshot.none { it.entityId == "source-food" || it.entityId == "source-cash" })
        val entry = snapshot.single { it.entityId == "source-entry" }.payload as LedgerSyncPayloadContract.LedgerEntry
        assertEquals("target-food", entry.categoryId)
        assertEquals("target-cash", entry.fundingAccountSyncId)
        val conflict = syncStore.pull(target.accountId, 0, 100).conflicts.single {
            it.entityId == "target-transit"
        }
        val candidate = conflict.candidatePayload as LedgerSyncPayloadContract.Category
        assertEquals("target-transit", candidate.id)
        assertEquals("INCOME", candidate.kind)
    }

    @Test
    fun testKtorEndToEndHttpEndpointsForAccountMerge() = testApplication {
        val store = InMemoryAccountStore()
        val clock = createClock()
        val fakeOAuth = FakeWechatOAuthClient(
            userInfoResult = WechatOAuthResult.Success(
                WechatUserInfoResponse("openid_h2", "H2WxUser", "https://img/h2.jpg", "unionid_h2")
            )
        )
        val service = AccountService(store = store, smsCodeGenerator = { "123456" }, clock = clock, wechatOAuthClient = fakeOAuth)

        // Register Phone User
        registerPhoneUser(service, clock, "13999999999", deviceId = "d1")

        // Register Pure WeChat User
        val exRes = service.exchangeWechatCode("good_code", null, "d2")
        assertTrue(exRes is AccountResult.Success)
        val exReq = (exRes as AccountResult.Success).value.result as WechatAuthResultContract.RegistrationRequired

        val wxUserTokenRes = service.registerWithWechat(exReq.wechatTicket, "d2")
        assertTrue(wxUserTokenRes is AccountResult.Success)
        val wxUserToken = (wxUserTokenRes as AccountResult.Success).value

        application {
            module(accountService = service)
        }

        // 1. Prepare merge via the final unified identifier endpoint.
        val prepResponse = client.submitForm(
            url = "/account/merge/prepare/identifier-password",
            formParameters = Parameters.build {
                append("identifier", "13999999999")
                append("password", "Pass123456!")
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer ${wxUserToken.token}")
        }
        assertEquals(HttpStatusCode.OK, prepResponse.status)
        val preview = AccountApiJsonContracts.parseMergePreviewResponse(prepResponse.bodyAsText())
        assertNotNull(preview.mergeTicket)
        assertEquals("13999999999", preview.sourceIdentifiers.single { it.type.name == "PHONE" }.value)

        // 2. Confirm merge via HTTP POST /account/merge/confirm
        val confirmResponse = client.submitForm(
            url = "/account/merge/confirm",
            formParameters = Parameters.build {
                append("mergeTicket", preview.mergeTicket)
                append("confirmText", "MERGE_ACCOUNT")
                append("deviceId", "d2")
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer ${wxUserToken.token}")
        }
        assertEquals(HttpStatusCode.OK, confirmResponse.status)
        val session = AccountApiJsonContracts.parseSessionResponse(confirmResponse.bodyAsText())
        assertNotNull(session.token)
        assertTrue(session.identifiers.any { it.value == "13999999999" })
        assertTrue(session.wechatLinked)
        assertEquals("H2WxUser", session.nickname)
    }

    @Test
    fun testPrepareAndMergeWithEmailIdentifierAndPassword() {
        val store = InMemoryAccountStore()
        val clock = createClock()
        val fakeOAuth = FakeWechatOAuthClient(
            userInfoResult = WechatOAuthResult.Success(
                WechatUserInfoResponse("fake_openid_email", "EmailWxUser", "https://img/email.jpg", "fake_unionid_email")
            )
        )
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            emailCodeGenerator = { "123456" },
            emailProvider = object : EmailProvider {
                override fun sendCode(email: String, code: String, purpose: String) = EmailProviderResult.Sent
            },
            clock = clock,
            wechatOAuthClient = fakeOAuth
        )

        // 1. Register email account
        val issueRes = service.issueVerificationCode(
            identifier = "user@example.com",
            deviceId = "dev-email",
            ipAddress = "127.0.0.1",
            purpose = "REGISTER"
        )
        assertTrue("Email code issue should succeed", issueRes is AccountResult.Success)
        val emailRes = service.registerIdentifier("user@example.com", "123456", "Pass123456!", "dev-email")
        assertTrue("Email reg should succeed", emailRes is AccountResult.Success)
        val emailToken = (emailRes as AccountResult.Success).value
        val sourceAccountId = emailToken.accountId

        // 2. Create pure WeChat account
        val exchangeRes = service.exchangeWechatCode("good_code", null, "dev-wx")
        assertTrue(exchangeRes is AccountResult.Success)
        val regReq = (exchangeRes as AccountResult.Success).value.result as WechatAuthResultContract.RegistrationRequired
        val targetTokenRes = service.registerWithWechat(regReq.wechatTicket, "dev-wx")
        assertTrue(targetTokenRes is AccountResult.Success)
        val targetToken = (targetTokenRes as AccountResult.Success).value
        val targetAccountId = targetToken.accountId

        // 3. Prepare merge using email identifier and password
        val prepRes = service.prepareMergeWithIdentifierPassword(
            bearerToken = targetToken.token,
            identifier = "user@example.com",
            password = "Pass123456!"
        )
        assertTrue("Prepare merge with email should succeed", prepRes is AccountResult.Success)
        val preview = (prepRes as AccountResult.Success).value
        assertEquals(1, preview.sourceIdentifiers.size)
        assertEquals("user@example.com", preview.sourceIdentifiers[0].value)

        // 4. Confirm merge
        val confirmRes = service.confirmMerge(
            bearerToken = targetToken.token,
            mergeTicket = preview.mergeTicket,
            confirmText = "合并账号",
            deviceId = "dev-wx"
        )
        assertTrue("Confirm merge should succeed", confirmRes is AccountResult.Success)
        val mergedToken = (confirmRes as AccountResult.Success).value
        assertEquals(targetAccountId, mergedToken.accountId)

        // 5. Source account deleted, email identifier & password cred transferred to target account
        assertNull(store.findAccount(sourceAccountId))
        val targetIdents = store.findIdentifiersByAccountId(targetAccountId)
        assertTrue(targetIdents.any { it.identifierType == "EMAIL" && it.normalizedValue == "user@example.com" })
        assertNotNull(store.findPasswordCredentialByAccountId(targetAccountId))

        // 6. Login with email & password works for merged account
        val loginRes = service.loginIdentifier("user@example.com", "Pass123456!", "dev-wx")
        assertTrue("Login with transferred email should succeed", loginRes is AccountResult.Success)
    }

    private fun categorySyncMutation(
        entityId: String,
        name: String,
        kind: String,
        mutationId: String
    ) = LedgerSyncMutationContract(
        mutationId = mutationId,
        entityType = LedgerSyncEntityTypeContract.CATEGORY,
        entityId = entityId,
        baseVersion = 0,
        deleted = false,
        payload = LedgerSyncPayloadContract.Category(entityId, name, kind, 1, false, 100)
    )

    private fun fundingSyncMutation(entityId: String, label: String, mutationId: String) =
        LedgerSyncMutationContract(
            mutationId = mutationId,
            entityType = LedgerSyncEntityTypeContract.FUNDING_ACCOUNT,
            entityId = entityId,
            baseVersion = 0,
            deleted = false,
            payload = LedgerSyncPayloadContract.FundingAccount(entityId, "MANUAL", null, label, 100)
        )

    private fun entrySyncMutation(
        entityId: String,
        categoryId: String,
        fundingAccountSyncId: String,
        mutationId: String
    ) = LedgerSyncMutationContract(
        mutationId = mutationId,
        entityType = LedgerSyncEntityTypeContract.LEDGER_ENTRY,
        entityId = entityId,
        baseVersion = 0,
        deleted = false,
        payload = LedgerSyncPayloadContract.LedgerEntry(
            id = entityId,
            ledgerBookId = "source-book",
            paymentSource = null,
            originalCaptureSource = null,
            entryOrigin = "MANUAL",
            flowDirection = "OUTFLOW",
            transactionKind = "EXPENSE",
            amountMinor = 100,
            currency = "CNY",
            merchantTitle = "测试账目",
            transactionTimeMillis = 100,
            categoryId = categoryId,
            fundingAccountSyncId = fundingAccountSyncId,
            note = null,
            confirmedAtMillis = 100,
            updatedAtMillis = 100,
            deletedAtMillis = null
        )
    )
}
