package com.bks.backend.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class SmsProviderTest {

    @Test
    fun percentEncodeConvertsCharactersCorrectly() {
        val encoded = AliyunPnvsSmsProvider.percentEncode("hello world+test*~")
        assertEquals("hello%20world%2Btest%2A~", encoded)
    }

    @Test
    fun computeHmacSha1ProducesValidBase64Signature() {
        val signature = AliyunPnvsSmsProvider.computeHmacSha1("test-string-to-sign", "secret-key&")
        assertTrue(signature.isNotBlank())
    }

    @Test
    fun fromEnvironmentParsesWebhookProvider() {
        val provider = SmsProvider.fromEnvironment(
            mapOf(
                "BKS_SMS_PROVIDER" to "webhook",
                "BKS_SMS_WEBHOOK_URL" to "https://example.com/webhook",
                "BKS_SMS_API_KEY" to "secret-key"
            )
        )
        assertTrue(provider is WebhookSmsProvider)
    }

    @Test
    fun fromEnvironmentParsesAliyunPnvsProvider() {
        val provider = SmsProvider.fromEnvironment(
            mapOf(
                "BKS_SMS_PROVIDER" to "aliyun_pnvs",
                "BKS_SMS_ALIYUN_ACCESS_KEY_ID" to "LTAI5tExampleKeyId",
                "BKS_SMS_ALIYUN_ACCESS_KEY_SECRET" to "ExampleAccessKeySecret123456",
                "BKS_SMS_SIGN_NAME" to "系统预置签名",
                "BKS_SMS_TEMPLATE_CODE" to "SMS_12345678"
            )
        )
        assertTrue(provider is AliyunPnvsSmsProvider)
    }

    @Test
    fun fromEnvironmentReturnsMissingSmsProviderWhenAliyunCredentialsIncomplete() {
        val provider = SmsProvider.fromEnvironment(
            mapOf(
                "BKS_SMS_PROVIDER" to "aliyun",
                "BKS_SMS_ALIYUN_ACCESS_KEY_ID" to "LTAI5tExampleKeyId"
                // Missing Secret, SignName, TemplateCode
            )
        )
        assertEquals(MissingSmsProvider, provider)
    }

    @Test
    fun fromEnvironmentReturnsMissingSmsProviderWhenProviderUnsetOrUnknown() {
        assertEquals(MissingSmsProvider, SmsProvider.fromEnvironment(emptyMap()))
        assertEquals(MissingSmsProvider, SmsProvider.fromEnvironment(mapOf("BKS_SMS_PROVIDER" to "unknown")))
    }

    @Test
    fun aliyunPnvsSmsProviderReturnsSentOnSuccessResponse() {
        val mockHttpClient = MockHttpClient(
            statusCode = 200,
            responseBody = """{"Code":"OK","Message":"OK","RequestId":"12345"}"""
        )
        val provider = AliyunPnvsSmsProvider(
            accessKeyId = "LTAI5tExampleKeyId",
            accessKeySecret = "SecretKey123",
            signName = "预置签名",
            templateCode = "SMS_1001",
            httpClient = mockHttpClient
        )

        val result = provider.sendCode("13800138000", "123456")
        assertEquals(SmsProviderResult.Sent, result)
        assertEquals(java.time.Duration.ofSeconds(10), mockHttpClient.lastRequest?.timeout()?.orElse(null))
    }

    @Test
    fun aliyunPnvsSmsProviderReturnsFailedOnErrorResponse() {
        val mockHttpClient = MockHttpClient(
            statusCode = 200,
            responseBody = """{"Code":"isv.BUSINESS_LIMIT_CONTROL","Message":"触发天发送限流"}"""
        )
        val provider = AliyunPnvsSmsProvider(
            config = AliyunPnvsSmsConfig(
                accessKeyId = "LTAI5tExampleKeyId",
                accessKeySecret = "SecretKey123",
                signName = "预置签名",
                templateCode = "SMS_1001"
            ),
            httpClient = mockHttpClient
        )

        val result = provider.sendCode("13800138000", "123456")
        assertEquals(SmsProviderResult.Failed(AccountError.SMS_SEND_FAILED), result)
    }

    private class MockHttpClient(
        private val statusCode: Int,
        private val responseBody: String
    ) : HttpClient() {
        var lastRequest: HttpRequest? = null

        override fun cookieHandler() = java.util.Optional.empty<java.net.CookieHandler>()
        override fun connectTimeout() = java.util.Optional.empty<java.time.Duration>()
        override fun followRedirects() = Redirect.NEVER
        override fun proxy() = java.util.Optional.empty<java.net.ProxySelector>()
        override fun sslContext(): javax.net.ssl.SSLContext = javax.net.ssl.SSLContext.getDefault()
        override fun sslParameters(): javax.net.ssl.SSLParameters = javax.net.ssl.SSLParameters()
        override fun authenticator() = java.util.Optional.empty<java.net.Authenticator>()
        override fun version() = Version.HTTP_1_1
        override fun executor() = java.util.Optional.empty<java.util.concurrent.Executor>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> send(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): HttpResponse<T> {
            lastRequest = request
            val mockResponse = object : HttpResponse<T> {
                override fun statusCode() = statusCode
                override fun request() = request
                override fun previousResponse() = java.util.Optional.empty<HttpResponse<T>>()
                override fun headers() = java.net.http.HttpHeaders.of(emptyMap()) { _, _ -> true }
                override fun body(): T = responseBody as T
                override fun sslSession() = java.util.Optional.empty<javax.net.ssl.SSLSession>()
                override fun uri() = request?.uri()
                override fun version() = Version.HTTP_1_1
            }
            return mockResponse
        }

        override fun <T : Any?> sendAsync(request: HttpRequest?, responseBodyHandler: HttpResponse.BodyHandler<T>?): java.util.concurrent.CompletableFuture<HttpResponse<T>> {
            throw UnsupportedOperationException()
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest?,
            responseBodyHandler: HttpResponse.BodyHandler<T>?,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?
        ): java.util.concurrent.CompletableFuture<HttpResponse<T>> {
            throw UnsupportedOperationException()
        }
    }
}
