package com.autoaccounting.backend.account

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

interface SmsProvider {
    fun sendCode(phone: String, code: String): SmsProviderResult
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
    private val httpClient: HttpClient = HttpClient.newHttpClient()
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
            val provider = env["AUTO_ACCOUNTING_SMS_PROVIDER"].orEmpty().lowercase()
            if (provider.isBlank()) return MissingSmsProvider
            if (provider != "webhook") return MissingSmsProvider

            val url = env["AUTO_ACCOUNTING_SMS_WEBHOOK_URL"].orEmpty()
            val key = env["AUTO_ACCOUNTING_SMS_API_KEY"].orEmpty()
            return if (url.isBlank() || key.isBlank()) {
                MissingSmsProvider
            } else {
                WebhookSmsProvider(url, key)
            }
        }
    }
}
