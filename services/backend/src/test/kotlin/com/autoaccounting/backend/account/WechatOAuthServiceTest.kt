package com.autoaccounting.backend.account

import com.autoaccounting.api.WechatAuthResultContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeWechatOAuthClient(
    override val appId: String = "wx_fake_appid",
    var configured: Boolean = true,
    var exchangeResult: WechatOAuthResult<WechatTokenResponse> = WechatOAuthResult.Success(
        WechatTokenResponse(
            accessToken = "fake_access_token",
            openid = "fake_openid",
            unionid = "fake_unionid"
        )
    ),
    var userInfoResult: WechatOAuthResult<WechatUserInfoResponse> = WechatOAuthResult.Success(
        WechatUserInfoResponse(
            openid = "fake_openid",
            nickname = "微信小张",
            avatarUrl = "https://example.com/avatar.jpg",
            unionid = "fake_unionid"
        )
    )
) : WechatOAuthClient {
    override fun isConfigured(): Boolean = configured

    override fun exchangeCode(code: String): WechatOAuthResult<WechatTokenResponse> {
        if (code == "invalid_code") return WechatOAuthResult.Failure.AuthFailed
        if (code == "timeout_code") return WechatOAuthResult.Failure.ServiceUnavailable
        return exchangeResult
    }

    override fun fetchUserInfo(accessToken: String, openid: String): WechatOAuthResult<WechatUserInfoResponse> {
        return userInfoResult
    }
}

class WechatOAuthServiceTest {

    @Test
    fun testExchangeWechatCodeUnconfigured() {
        val fakeClient = FakeWechatOAuthClient(configured = false)
        val service = AccountService(wechatOAuthClient = fakeClient)

        val result = service.exchangeWechatCode("valid_code")
        assertTrue(result is AccountResult.Failure)
        assertEquals(AccountError.WECHAT_NOT_CONFIGURED, (result as AccountResult.Failure).error)
    }

    @Test
    fun testExchangeWechatCodeInvalidRequest() {
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(wechatOAuthClient = fakeClient)

        val result = service.exchangeWechatCode("")
        assertTrue(result is AccountResult.Failure)
        assertEquals(AccountError.INVALID_REQUEST, (result as AccountResult.Failure).error)
    }

    @Test
    fun testExchangeWechatCodeAuthFailed() {
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(wechatOAuthClient = fakeClient)

        val result = service.exchangeWechatCode("invalid_code")
        assertTrue(result is AccountResult.Failure)
        assertEquals(AccountError.WECHAT_AUTH_FAILED, (result as AccountResult.Failure).error)
    }

    @Test
    fun testExchangeWechatCodeServiceUnavailable() {
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(wechatOAuthClient = fakeClient)

        val result = service.exchangeWechatCode("timeout_code")
        assertTrue(result is AccountResult.Failure)
        assertEquals(AccountError.WECHAT_SERVICE_UNAVAILABLE, (result as AccountResult.Failure).error)
    }

    @Test
    fun testExchangeWechatCodeUnboundNewUserReturnsRegistrationRequired() {
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(wechatOAuthClient = fakeClient)

        val result = service.exchangeWechatCode("good_code")
        assertTrue(result is AccountResult.Success)

        val response = (result as AccountResult.Success).value
        assertTrue(response.result is WechatAuthResultContract.RegistrationRequired)

        val regReq = response.result as WechatAuthResultContract.RegistrationRequired
        assertNotNull(regReq.wechatTicket)
        assertEquals("微信小张", regReq.nickname)
        assertEquals("https://example.com/avatar.jpg", regReq.avatarUrl)
        assertTrue(regReq.ticketExpiresAtMillis > System.currentTimeMillis())
    }

    @Test
    fun testExchangeWechatCodeUserInfoFailedStillSucceedsWithNullProfile() {
        val fakeClient = FakeWechatOAuthClient(
            configured = true,
            userInfoResult = WechatOAuthResult.Failure.AuthFailed
        )
        val service = AccountService(wechatOAuthClient = fakeClient)

        val result = service.exchangeWechatCode("good_code")
        assertTrue(result is AccountResult.Success)

        val response = (result as AccountResult.Success).value
        assertTrue(response.result is WechatAuthResultContract.RegistrationRequired)

        val regReq = response.result as WechatAuthResultContract.RegistrationRequired
        assertNull(regReq.nickname)
        assertNull(regReq.avatarUrl)
    }

    @Test
    fun testExchangeWechatCodeAlreadyBoundSignsInAndRefreshesProfile() {
        val store = InMemoryAccountStore()
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(store = store, wechatOAuthClient = fakeClient)

        // Pre-create an account and bind a WeChat identity
        val created = store.createUser(
            StoredUser(
                accountId = 100L,
                phone = "13800138000",
                passwordSalt = "salt",
                passwordHash = "hash",
                createdAtMillis = 1000L
            )
        )
        assertTrue(created)
        val user = store.findUser("13800138000")!!

        store.upsertWechatIdentity(
            StoredWechatIdentity(
                accountId = user.accountId,
                appId = "wx_fake_appid",
                openid = "fake_openid",
                unionid = "fake_unionid",
                nickname = "旧昵称",
                avatarUrl = "https://example.com/old.jpg",
                createdAtMillis = 1000L,
                updatedAtMillis = 1000L
            )
        )

        // Exchange code
        val result = service.exchangeWechatCode("good_code")
        assertTrue(result is AccountResult.Success)

        val response = (result as AccountResult.Success).value
        assertTrue(response.result is WechatAuthResultContract.SignedIn)

        val signedIn = response.result as WechatAuthResultContract.SignedIn
        assertEquals("13800138000", signedIn.session.phone)
        assertNotNull(signedIn.session.token)
        assertTrue(signedIn.session.wechatLinked)
        assertEquals("微信小张", signedIn.session.nickname)
        assertEquals("https://example.com/avatar.jpg", signedIn.session.avatarUrl)

        // Check updated profile in store
        val identity = store.findWechatIdentityByAccountId(user.accountId)
        assertNotNull(identity)
        assertEquals("微信小张", identity?.nickname)
    }

    @Test
    fun testExchangeWechatCodeWithBearerBindsDirectlyIfUnbound() {
        val store = InMemoryAccountStore()
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            wechatOAuthClient = fakeClient
        )

        service.issueSmsCode("13800138000", "device_1", "127.0.0.1")
        val regResult = service.register("13800138000", "123456", "Pass1234!", "device_1", "127.0.0.1")
        assertTrue(regResult is AccountResult.Success)
        val token = (regResult as AccountResult.Success).value.token

        val exchangeResult = service.exchangeWechatCode("good_code", bearerToken = token)
        assertTrue(exchangeResult is AccountResult.Success)

        val response = (exchangeResult as AccountResult.Success).value
        assertTrue(response.result is WechatAuthResultContract.SignedIn)

        val signedIn = response.result as WechatAuthResultContract.SignedIn
        assertEquals("13800138000", signedIn.session.phone)
        assertEquals(token, signedIn.session.token)
        assertTrue(signedIn.session.wechatLinked)
        assertEquals("微信小张", signedIn.session.nickname)
    }

    @Test
    fun testExchangeWechatCodeWithBearerRejectsReplacingExistingWechatIdentity() {
        val store = InMemoryAccountStore()
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            wechatOAuthClient = fakeClient
        )
        service.issueSmsCode("13800138000", "device_1", "127.0.0.1")
        val registration = service.register("13800138000", "123456", "Pass1234!", "device_1", "127.0.0.1")
            as AccountResult.Success<AccountToken>
        val accountId = store.findUser("13800138000")!!.accountId
        val originalIdentity = StoredWechatIdentity(
            accountId = accountId,
            appId = "wx_fake_appid",
            openid = "original_openid",
            unionid = "original_unionid",
            createdAtMillis = 1000L,
            updatedAtMillis = 1000L
        )
        store.upsertWechatIdentity(originalIdentity)

        val result = service.exchangeWechatCode("good_code", bearerToken = registration.value.token)

        assertTrue(result is AccountResult.Failure)
        assertEquals(AccountError.WECHAT_ALREADY_LINKED, (result as AccountResult.Failure).error)
        assertEquals(originalIdentity, store.findWechatIdentityByAccountId(accountId))
    }

    @Test
    fun testExchangeWechatCodeMapsConcurrentClaimConflictToMergeRequired() {
        val delegate = InMemoryAccountStore()
        delegate.createUser(
            StoredUser(
                accountId = 0L,
                phone = "13800138002",
                passwordSalt = "salt",
                passwordHash = "hash",
                createdAtMillis = 1000L
            )
        )
        val sourceAccount = delegate.findUser("13800138002")!!
        val concurrentlyClaimedIdentity = StoredWechatIdentity(
            accountId = sourceAccount.accountId,
            appId = "wx_fake_appid",
            openid = "fake_openid",
            unionid = "fake_unionid",
            nickname = "并发来源账号",
            createdAtMillis = 1000L,
            updatedAtMillis = 1000L
        )
        val store = object : AccountStore by delegate {
            override fun claimWechatIdentity(identity: StoredWechatIdentity): WechatIdentityClaimResult {
                return WechatIdentityClaimResult.Conflict(concurrentlyClaimedIdentity)
            }
        }
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            wechatOAuthClient = FakeWechatOAuthClient(configured = true)
        )
        service.issueSmsCode("13800138001", "device_1", "127.0.0.1")
        val registration = service.register("13800138001", "123456", "Pass1234!", "device_1", "127.0.0.1")
            as AccountResult.Success<AccountToken>

        val result = service.exchangeWechatCode("good_code", bearerToken = registration.value.token)

        assertTrue(result is AccountResult.Success)
        val response = (result as AccountResult.Success).value
        assertTrue(response.result is WechatAuthResultContract.MergeRequired)
        val mergeRequired = response.result as WechatAuthResultContract.MergeRequired
        assertEquals("13800138002", mergeRequired.sourcePhone)
        assertEquals("并发来源账号", mergeRequired.sourceNickname)
    }

    @Test
    fun testExchangeWechatCodeWithBearerBelongsToAnotherAccountReturnsMergeRequired() {
        val store = InMemoryAccountStore()
        val fakeClient = FakeWechatOAuthClient(configured = true)
        val service = AccountService(
            store = store,
            smsCodeGenerator = { "123456" },
            wechatOAuthClient = fakeClient
        )

        // Account 1: Target account logged in with Bearer token
        service.issueSmsCode("13800138001", "device_1", "127.0.0.1")
        val regResult = service.register("13800138001", "123456", "Pass1234!", "device_1", "127.0.0.1")
        val regSuccess = regResult as AccountResult.Success
        val token1 = regSuccess.value.token






        // Account 2: Source account with phone 13800138002, bound to WeChat identity
        val created2 = store.createUser(
            StoredUser(
                accountId = 0L,
                phone = "13800138002",
                passwordSalt = "salt",
                passwordHash = "hash",
                createdAtMillis = 1000L
            )
        )
        assertTrue(created2)
        val user2 = store.findUser("13800138002")!!
        store.upsertWechatIdentity(
            StoredWechatIdentity(
                accountId = user2.accountId,
                appId = "wx_fake_appid",
                openid = "fake_openid",
                unionid = "fake_unionid",
                nickname = "微信原昵称",
                avatarUrl = "https://example.com/source.jpg",
                createdAtMillis = 1000L,
                updatedAtMillis = 1000L
            )
        )

        // Account 1 exchanges code for Account 2's WeChat identity
        val exchangeResult = service.exchangeWechatCode("good_code", bearerToken = token1)
        assertTrue(exchangeResult is AccountResult.Success)

        val response = (exchangeResult as AccountResult.Success).value
        assertTrue(response.result is WechatAuthResultContract.MergeRequired)

        val mergeReq = response.result as WechatAuthResultContract.MergeRequired
        assertNotNull(mergeReq.mergeTicket)
        assertEquals("13800138002", mergeReq.sourcePhone)
        assertEquals("微信原昵称", mergeReq.sourceNickname)
    }
}
