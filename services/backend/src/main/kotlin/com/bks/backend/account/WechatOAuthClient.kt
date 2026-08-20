@file:Suppress("SwallowedException")

package com.bks.backend.account


import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration

data class WechatTokenResponse(
    val accessToken: String,
    val openid: String,
    val unionid: String? = null
)

data class WechatUserInfoResponse(
    val openid: String,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val unionid: String? = null
)

sealed interface WechatOAuthResult<out T> {
    data class Success<T>(val value: T) : WechatOAuthResult<T>
    sealed interface Failure : WechatOAuthResult<Nothing> {
        object AuthFailed : Failure
        object ServiceUnavailable : Failure
    }
}

interface WechatOAuthClient {
    val appId: String
    fun isConfigured(): Boolean
    fun exchangeCode(code: String): WechatOAuthResult<WechatTokenResponse>
    fun fetchUserInfo(accessToken: String, openid: String): WechatOAuthResult<WechatUserInfoResponse>

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): WechatOAuthClient {
            val appId = env["BKS_WECHAT_APP_ID"].orEmpty().trim()
            val appSecret = env["BKS_WECHAT_APP_SECRET"].orEmpty().trim()
            return DefaultWechatOAuthClient(appId = appId, appSecret = appSecret)
        }
    }
}

class DefaultWechatOAuthClient(
    override val appId: String = "",
    private val appSecret: String = "",
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val baseUrl: String = "https://api.weixin.qq.com",
    private val requestTimeout: Duration = Duration.ofSeconds(10)
) : WechatOAuthClient {

    private val json = Json { ignoreUnknownKeys = true }

    override fun isConfigured(): Boolean {
        return appId.isNotBlank() && appSecret.isNotBlank()
    }

    override fun exchangeCode(code: String): WechatOAuthResult<WechatTokenResponse> {
        if (!isConfigured() || code.isBlank()) {
            return WechatOAuthResult.Failure.AuthFailed
        }
        val encodedAppId = URLEncoder.encode(appId, StandardCharsets.UTF_8)
        val encodedSecret = URLEncoder.encode(appSecret, StandardCharsets.UTF_8)
        val encodedCode = URLEncoder.encode(code, StandardCharsets.UTF_8)
        val url = "$baseUrl/sns/oauth2/access_token?appid=$encodedAppId&secret=$encodedSecret&code=$encodedCode&grant_type=authorization_code"

        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() != 200) {
                return WechatOAuthResult.Failure.ServiceUnavailable
            }

            val bodyJson = json.parseToJsonElement(response.body()).jsonObject
            val errcode = bodyJson["errcode"]?.jsonPrimitive?.intOrNull
            if (errcode != null && errcode != 0) {
                return WechatOAuthResult.Failure.AuthFailed
            }

            val accessToken = bodyJson["access_token"]?.jsonPrimitive?.contentOrNull
            val openid = bodyJson["openid"]?.jsonPrimitive?.contentOrNull
            if (accessToken.isNullOrBlank() || openid.isNullOrBlank()) {
                return WechatOAuthResult.Failure.AuthFailed
            }

            val unionid = bodyJson["unionid"]?.jsonPrimitive?.contentOrNull
            WechatOAuthResult.Success(
                WechatTokenResponse(
                    accessToken = accessToken,
                    openid = openid,
                    unionid = unionid
                )
            )
        } catch (e: HttpTimeoutException) {
            WechatOAuthResult.Failure.ServiceUnavailable
        } catch (e: java.io.IOException) {
            WechatOAuthResult.Failure.ServiceUnavailable
        } catch (e: Exception) {
            WechatOAuthResult.Failure.AuthFailed
        }
    }

    override fun fetchUserInfo(accessToken: String, openid: String): WechatOAuthResult<WechatUserInfoResponse> {
        if (!isConfigured() || accessToken.isBlank() || openid.isBlank()) {
            return WechatOAuthResult.Failure.AuthFailed
        }
        val encodedToken = URLEncoder.encode(accessToken, StandardCharsets.UTF_8)
        val encodedOpenid = URLEncoder.encode(openid, StandardCharsets.UTF_8)
        val url = "$baseUrl/sns/userinfo?access_token=$encodedToken&openid=$encodedOpenid&lang=zh_CN"

        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() != 200) {
                return WechatOAuthResult.Failure.ServiceUnavailable
            }

            val bodyJson = json.parseToJsonElement(response.body()).jsonObject
            val errcode = bodyJson["errcode"]?.jsonPrimitive?.intOrNull
            if (errcode != null && errcode != 0) {
                return WechatOAuthResult.Failure.AuthFailed
            }

            val resOpenid = bodyJson["openid"]?.jsonPrimitive?.contentOrNull ?: openid
            val nickname = bodyJson["nickname"]?.jsonPrimitive?.contentOrNull
            val avatarUrl = bodyJson["headimgurl"]?.jsonPrimitive?.contentOrNull
            val unionid = bodyJson["unionid"]?.jsonPrimitive?.contentOrNull

            WechatOAuthResult.Success(
                WechatUserInfoResponse(
                    openid = resOpenid,
                    nickname = nickname,
                    avatarUrl = avatarUrl,
                    unionid = unionid
                )
            )
        } catch (e: HttpTimeoutException) {
            WechatOAuthResult.Failure.ServiceUnavailable
        } catch (e: java.io.IOException) {
            WechatOAuthResult.Failure.ServiceUnavailable
        } catch (e: Exception) {
            WechatOAuthResult.Failure.AuthFailed
        }
    }
}
