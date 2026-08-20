package com.bks.backend.account

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.time.Duration


class WechatOAuthClientTest {

    private lateinit var server: HttpServer
    private var serverPort: Int = 0

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server.address.port
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun testUnconfiguredClient() {
        val client = DefaultWechatOAuthClient(appId = "", appSecret = "")
        assertFalse(client.isConfigured())
        val exchangeRes = client.exchangeCode("valid_code")
        assertTrue(exchangeRes is WechatOAuthResult.Failure.AuthFailed)

        val userRes = client.fetchUserInfo("token", "openid")
        assertTrue(userRes is WechatOAuthResult.Failure.AuthFailed)
    }

    @Test
    fun testExchangeCodeSuccessWithUnionId() {
        server.createContext("/sns/oauth2/access_token") { exchange ->
            val responseJson = """
                {
                    "access_token": "mock_access_token",
                    "expires_in": 7200,
                    "refresh_token": "mock_refresh_token",
                    "openid": "mock_openid",
                    "scope": "snsapi_userinfo",
                    "unionid": "mock_unionid"
                }
            """.trimIndent()
            exchange.sendResponseHeaders(200, responseJson.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseJson.toByteArray()) }
        }

        val client = DefaultWechatOAuthClient(
            appId = "wx_test_appid",
            appSecret = "wx_test_secret",
            baseUrl = "http://127.0.0.1:$serverPort"
        )
        assertTrue(client.isConfigured())

        val result = client.exchangeCode("good_code")
        assertTrue(result is WechatOAuthResult.Success)
        val tokenResp = (result as WechatOAuthResult.Success).value
        assertEquals("mock_access_token", tokenResp.accessToken)
        assertEquals("mock_openid", tokenResp.openid)
        assertEquals("mock_unionid", tokenResp.unionid)
    }

    @Test
    fun testExchangeCodeSuccessWithoutUnionId() {
        server.createContext("/sns/oauth2/access_token") { exchange ->
            val responseJson = """
                {
                    "access_token": "mock_access_token",
                    "expires_in": 7200,
                    "openid": "mock_openid"
                }
            """.trimIndent()
            exchange.sendResponseHeaders(200, responseJson.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseJson.toByteArray()) }
        }

        val client = DefaultWechatOAuthClient(
            appId = "wx_test_appid",
            appSecret = "wx_test_secret",
            baseUrl = "http://127.0.0.1:$serverPort"
        )

        val result = client.exchangeCode("good_code")
        assertTrue(result is WechatOAuthResult.Success)
        val tokenResp = (result as WechatOAuthResult.Success).value
        assertEquals("mock_access_token", tokenResp.accessToken)
        assertEquals("mock_openid", tokenResp.openid)
        assertNull(tokenResp.unionid)
    }

    @Test
    fun testExchangeCodeWechatErrCode() {
        server.createContext("/sns/oauth2/access_token") { exchange ->
            val responseJson = """
                {
                    "errcode": 40029,
                    "errmsg": "invalid code"
                }
            """.trimIndent()
            exchange.sendResponseHeaders(200, responseJson.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseJson.toByteArray()) }
        }

        val client = DefaultWechatOAuthClient(
            appId = "wx_test_appid",
            appSecret = "wx_test_secret",
            baseUrl = "http://127.0.0.1:$serverPort"
        )

        val result = client.exchangeCode("bad_code")
        assertTrue(result is WechatOAuthResult.Failure.AuthFailed)
    }

    @Test
    fun testExchangeCodeHttp500() {
        server.createContext("/sns/oauth2/access_token") { exchange ->
            exchange.sendResponseHeaders(500, 0)
            exchange.close()
        }

        val client = DefaultWechatOAuthClient(
            appId = "wx_test_appid",
            appSecret = "wx_test_secret",
            baseUrl = "http://127.0.0.1:$serverPort"
        )

        val result = client.exchangeCode("code")
        assertTrue(result is WechatOAuthResult.Failure.ServiceUnavailable)
    }

    @Test
    fun testExchangeCodeTimeout() {
        server.createContext("/sns/oauth2/access_token") { exchange ->
            Thread.sleep(500)
            val responseJson = """{"access_token":"token","openid":"openid"}"""
            exchange.sendResponseHeaders(200, responseJson.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseJson.toByteArray()) }
        }

        val client = DefaultWechatOAuthClient(
            appId = "wx_test_appid",
            appSecret = "wx_test_secret",
            baseUrl = "http://127.0.0.1:$serverPort",
            requestTimeout = Duration.ofMillis(100)
        )



        val result = client.exchangeCode("code")
        assertTrue(result is WechatOAuthResult.Failure.ServiceUnavailable)
    }

    @Test
    fun testFetchUserInfoSuccess() {
        server.createContext("/sns/userinfo") { exchange ->
            val responseJson = """
                {
                    "openid": "mock_openid",
                    "nickname": "TestUser",
                    "headimgurl": "https://example.com/avatar.jpg",
                    "unionid": "mock_unionid"
                }
            """.trimIndent()
            exchange.sendResponseHeaders(200, responseJson.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseJson.toByteArray()) }
        }

        val client = DefaultWechatOAuthClient(
            appId = "wx_test_appid",
            appSecret = "wx_test_secret",
            baseUrl = "http://127.0.0.1:$serverPort"
        )

        val result = client.fetchUserInfo("token", "mock_openid")
        assertTrue(result is WechatOAuthResult.Success)
        val userResp = (result as WechatOAuthResult.Success).value
        assertEquals("mock_openid", userResp.openid)
        assertEquals("TestUser", userResp.nickname)
        assertEquals("https://example.com/avatar.jpg", userResp.avatarUrl)
        assertEquals("mock_unionid", userResp.unionid)
    }
}
