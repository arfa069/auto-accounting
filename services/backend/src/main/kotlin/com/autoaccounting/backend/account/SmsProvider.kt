package com.autoaccounting.backend.account

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

interface SmsProvider {
    fun sendCode(phone: String, code: String): SmsProviderResult

    companion object
}
sealed interface SmsProviderResult {
    data object Sent : SmsProviderResult
    data class Failed(val error: AccountError) : SmsProviderResult
}

object NoopSmsProvider : SmsProvider {
    override fun sendCode(phone: String, code: String): SmsProviderResult = SmsProviderResult.Sent
}

object MissingSmsProvider : SmsProvider {
    override fun sendCode(phone: String, code: String): SmsProviderResult {
        return SmsProviderResult.Failed(AccountError.SMS_PROVIDER_UNCONFIGURED)
    }
}

class WebhookSmsProvider(
    private val webhookUrl: String,
    private val apiKey: String,
    private val httpClient: HttpClient = defaultSmsHttpClient()
) : SmsProvider {
    override fun sendCode(phone: String, code: String): SmsProviderResult {
        return try {
            val body = listOf(
                "phone" to phone,
                "code" to code
            ).joinToString("&") { (key, value) ->
                "${key}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
            }
            val request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(SMS_REQUEST_TIMEOUT)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() in 200..299) {
                SmsProviderResult.Sent
            } else {
                SmsProviderResult.Failed(AccountError.SMS_SEND_FAILED)
            }
        } catch (_: RuntimeException) {
            SmsProviderResult.Failed(AccountError.SMS_SEND_FAILED)
        } catch (_: java.io.IOException) {
            SmsProviderResult.Failed(AccountError.SMS_SEND_FAILED)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            SmsProviderResult.Failed(AccountError.SMS_SEND_FAILED)
        }
    }

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): SmsProvider {
            return SmsProvider.fromEnvironment(env)
        }
    }
}

class AliyunPnvsSmsProvider internal constructor(
    private val config: AliyunPnvsSmsConfig,
    private val httpClient: HttpClient = defaultSmsHttpClient()
) : SmsProvider {

    @Suppress("LongParameterList")
    constructor(
        accessKeyId: String,
        accessKeySecret: String,
        signName: String,
        templateCode: String,
        schemeName: String = "",
        endpoint: String = "https://dypnsapi.aliyuncs.com",
        httpClient: HttpClient = defaultSmsHttpClient()
    ) : this(
        config = AliyunPnvsSmsConfig(
            accessKeyId = accessKeyId,
            accessKeySecret = accessKeySecret,
            signName = signName,
            templateCode = templateCode,
            schemeName = schemeName,
            endpoint = endpoint
        ),
        httpClient = httpClient
    )

    override fun sendCode(phone: String, code: String): SmsProviderResult {
        return try {
            val timestamp = ISO_INSTANT_FORMATTER.format(java.time.Instant.now())
            val nonce = java.util.UUID.randomUUID().toString()

            val params = mutableMapOf(
                "AccessKeyId" to config.accessKeyId,
                "Action" to "SendSmsVerifyCode",
                "Format" to "JSON",
                "PhoneNumber" to phone,
                "SignName" to config.signName,
                "SignatureMethod" to "HMAC-SHA1",
                "SignatureNonce" to nonce,
                "SignatureVersion" to "1.0",
                "TemplateCode" to config.templateCode,
                "TemplateParam" to "{\"code\":\"$code\",\"min\":\"5\"}",
                "Timestamp" to timestamp,
                "Version" to "2017-05-25"
            )
            if (config.schemeName.isNotBlank()) {
                params["SchemeName"] = config.schemeName
            }

            val canonicalizedQueryString = params.entries
                .sortedBy { it.key }
                .joinToString("&") { (k, v) ->
                    "${percentEncode(k)}=${percentEncode(v)}"
                }

            val stringToSign = "POST&${percentEncode("/")}&${percentEncode(canonicalizedQueryString)}"
            val signature = computeHmacSha1(stringToSign, "${config.accessKeySecret}&")
            val requestBody = "Signature=${percentEncode(signature)}&$canonicalizedQueryString"

            val request = HttpRequest.newBuilder(URI.create(config.endpoint))
                .timeout(SMS_REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            val body = response.body().orEmpty()
            if (response.statusCode() in 200..299 && isSuccessResponse(body)) {
                SmsProviderResult.Sent
            } else {
                SmsProviderResult.Failed(AccountError.SMS_SEND_FAILED)
            }
        } catch (_: RuntimeException) {
            SmsProviderResult.Failed(AccountError.SMS_SEND_FAILED)
        } catch (_: java.io.IOException) {
            SmsProviderResult.Failed(AccountError.SMS_SEND_FAILED)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            SmsProviderResult.Failed(AccountError.SMS_SEND_FAILED)
        }
    }

    companion object {
        private val ISO_INSTANT_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(java.time.ZoneOffset.UTC)

        fun percentEncode(value: String): String {
            return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~")
        }

        fun computeHmacSha1(data: String, key: String): String {
            val mac = javax.crypto.Mac.getInstance("HmacSHA1")
            val secretKey = javax.crypto.spec.SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA1")
            mac.init(secretKey)
            val signData = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            return java.util.Base64.getEncoder().encodeToString(signData)
        }

        private fun isSuccessResponse(body: String?): Boolean {
            if (body.isNullOrBlank()) return false
            return body.contains("\"Code\":\"OK\"") || body.contains("\"code\":\"OK\"")
        }
    }
}

private val SMS_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)

private fun defaultSmsHttpClient(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(SMS_REQUEST_TIMEOUT)
    .build()

fun SmsProvider.Companion.fromEnvironment(env: Map<String, String> = System.getenv()): SmsProvider {
    val provider = env["AUTO_ACCOUNTING_SMS_PROVIDER"].orEmpty().lowercase()
    if (provider.isBlank()) return MissingSmsProvider

    return when (provider) {
        "webhook" -> fromWebhookEnvironment(env)
        "aliyun_pnvs", "aliyun" -> fromAliyunEnvironment(env)
        else -> MissingSmsProvider
    }
}

private fun fromWebhookEnvironment(env: Map<String, String>): SmsProvider {
    val url = env["AUTO_ACCOUNTING_SMS_WEBHOOK_URL"].orEmpty()
    val key = env["AUTO_ACCOUNTING_SMS_API_KEY"].orEmpty()
    return if (url.isBlank() || key.isBlank()) MissingSmsProvider else WebhookSmsProvider(url, key)
}

private fun fromAliyunEnvironment(env: Map<String, String>): SmsProvider {
    val config = env.aliyunPnvsSmsConfig() ?: return MissingSmsProvider
    return AliyunPnvsSmsProvider(config)
}

private fun Map<String, String>.aliyunPnvsSmsConfig(): AliyunPnvsSmsConfig? {
    val keyId = this["AUTO_ACCOUNTING_SMS_ALIYUN_ACCESS_KEY_ID"]
        ?: this["AUTO_ACCOUNTING_ALIYUN_ACCESS_KEY_ID"]
        ?: this["ALIYUN_ACCESS_KEY_ID"].orEmpty()
    val keySecret = this["AUTO_ACCOUNTING_SMS_ALIYUN_ACCESS_KEY_SECRET"]
        ?: this["AUTO_ACCOUNTING_ALIYUN_ACCESS_KEY_SECRET"]
        ?: this["ALIYUN_ACCESS_KEY_SECRET"].orEmpty()
    val signName = this["AUTO_ACCOUNTING_SMS_SIGN_NAME"].orEmpty()
    val templateCode = this["AUTO_ACCOUNTING_SMS_TEMPLATE_CODE"].orEmpty()
    if (listOf(keyId, keySecret, signName, templateCode).any(String::isBlank)) return null
    return AliyunPnvsSmsConfig(
        accessKeyId = keyId,
        accessKeySecret = keySecret,
        signName = signName,
        templateCode = templateCode,
        schemeName = this["AUTO_ACCOUNTING_SMS_SCHEME_NAME"].orEmpty()
    )
}
