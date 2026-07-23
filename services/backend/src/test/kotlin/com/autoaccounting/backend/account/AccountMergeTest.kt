package com.autoaccounting.backend.account

import com.autoaccounting.api.AccountApiJsonContracts
import com.autoaccounting.api.WechatAuthResultContract
import com.autoaccounting.backend.ai.JdbcAiCategorizationLogStore
import com.autoaccounting.backend.ai.StoredAiCategorizationLog
import com.autoaccounting.backend.config.CloudConfigService
import com.autoaccounting.backend.config.CloudConfigUpdate
import com.autoaccounting.backend.config.JdbcCloudConfigStore
import com.autoaccounting.backend.module
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
        val smsRes = service.issueSmsCode(phone, deviceId, "127.0.0.1")
        assertTrue("SMS issue should succeed for $phone: ${(smsRes as? AccountResult.Failure)?.error}", smsRes is AccountResult.Success)
        val regRes = service.register(phone, code, password, deviceId)
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
        val prepareRes = service.prepareMergeWithPhonePassword(
            bearerToken = targetToken.token,
            phone = "13800138000",
            password = "Pass123456!"
        )
        assertTrue("Prepare merge should succeed", prepareRes is AccountResult.Success)
        val preview = (prepareRes as AccountResult.Success).value
        assertEquals("Target phone should be null", null, preview.currentPhone)
        assertTrue("Target WeChat should be linked", preview.currentWechatLinked)
        assertEquals("Source phone matches", "13800138000", preview.sourcePhone)
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
        assertTrue("Target still has WeChat linked", mergedToken.wechatLinked)
        assertEquals("Target nickname preserved", "TargetWxUser", mergedToken.nickname)

        // 5. Source account is deleted
        assertNull("Source account should be deleted", store.findAccount(sourceAccountId))
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
        assertNull("Source pure WeChat account has no phone", mergeReq.sourcePhone)

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
        val prepareRes = service1.prepareMergeWithPhonePassword(t1.token, "13800000002", "Pass123456!")
        assertTrue("Merge is blocked due to duplicate phone and wechat credentials", prepareRes is AccountResult.Failure)
        assertEquals(AccountError.MERGE_BLOCKED, (prepareRes as AccountResult.Failure).error)
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
        val prepareRes = service.prepareMergeWithPhonePassword(t2.token, "13800000001", "Pass123456!")
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

        val prepareRes = service.prepareMergeWithPhonePassword(t2.token, "13800000001", "Pass123456!")
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

        val prepareRes = service.prepareMergeWithPhonePassword(t2.token, "13800000001", "Pass123456!")
        assertTrue(prepareRes is AccountResult.Success)
        val preview = (prepareRes as AccountResult.Success).value

        // Execute merge
        val confirmRes = service.confirmMerge(t2.token, preview.mergeTicket, "合并账号", "confirm-device")
        assertTrue(confirmRes is AccountResult.Success)

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

        // 1. Prepare merge via HTTP POST /account/merge/prepare/phone-password
        val prepResponse = client.submitForm(
            url = "/account/merge/prepare/phone-password",
            formParameters = Parameters.build {
                append("phone", "13999999999")
                append("password", "Pass123456!")
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer ${wxUserToken.token}")
        }
        assertEquals(HttpStatusCode.OK, prepResponse.status)
        val preview = AccountApiJsonContracts.parseMergePreviewResponse(prepResponse.bodyAsText())
        assertNotNull(preview.mergeTicket)
        assertEquals("13999999999", preview.sourcePhone)

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
        assertEquals("13999999999", session.phone)
        assertTrue(session.wechatLinked)
        assertEquals("H2WxUser", session.nickname)
    }
}
