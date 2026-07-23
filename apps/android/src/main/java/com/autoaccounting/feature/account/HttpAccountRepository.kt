package com.autoaccounting.feature.account

import com.autoaccounting.api.AccountApiJsonContracts
import com.autoaccounting.api.AccountDeletionStatusContract
import com.autoaccounting.api.AccountSessionResponseContract
import com.autoaccounting.api.MergePreviewResponseContract
import com.autoaccounting.api.PhoneLinkPrepareResponseContract
import com.autoaccounting.api.WechatAuthResultContract
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class AccountHttpResponse(
    val statusCode: Int,
    val body: String
)

internal interface AccountHttpTransport {
    suspend fun post(
        url: String,
        form: Map<String, String>,
        bearerToken: String? = null
    ): AccountHttpResponse
}

internal enum class AccountHttpStage {
    RequestStarted,
    RequestBodyWritten,
    ResponseHeadersReceived,
    ResponseBodyRead,
    Cancelled
}

internal fun interface AccountHttpObserver {
    fun onStage(stage: AccountHttpStage)
}

internal class HttpUrlConnectionAccountTransport(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val observer: AccountHttpObserver = AccountHttpObserver { }
) : AccountHttpTransport {
    override suspend fun post(
        url: String,
        form: Map<String, String>,
        bearerToken: String?
    ): AccountHttpResponse = withContext(ioDispatcher) {
        val connection = URL(url).openConnection() as HttpURLConnection
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                try {
                    observer.onStage(AccountHttpStage.Cancelled)
                } finally {
                    connection.disconnect()
                }
            }
            try {
                observer.onStage(AccountHttpStage.RequestStarted)
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8"
                )
                bearerToken?.let { token ->
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }
                val requestBody = form.entries.joinToString("&") { (name, value) ->
                    "${name.formEncode()}=${value.formEncode()}"
                }.toByteArray(Charsets.UTF_8)
                connection.outputStream.use { output -> output.write(requestBody) }
                observer.onStage(AccountHttpStage.RequestBodyWritten)
                val status = connection.responseCode
                observer.onStage(AccountHttpStage.ResponseHeadersReceived)
                val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                observer.onStage(AccountHttpStage.ResponseBodyRead)
                val response = AccountHttpResponse(statusCode = status, body = body)
                continuation.resume(response)
            } catch (cause: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(cause)
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 15_000
    }
}

internal class HttpAccountRepository(
    backendUrl: String,
    private val installationId: () -> String,
    private val transport: AccountHttpTransport = HttpUrlConnectionAccountTransport()
) : AccountRepository {
    private val baseUrl = backendUrl.trim().trimEnd('/')

    override suspend fun requestSmsCode(
        phone: String,
        purpose: AccountSmsPurpose,
        contextKey: String?,
        bearerToken: String?
    ): AccountRepositoryResult<Unit> {
        return execute(
            path = "/account/sms",
            form = buildMap {
                put("phone", phone)
                put("deviceId", installationId())
                if (purpose != AccountSmsPurpose.Default) put("purpose", purpose.wireValue)
                contextKey?.let { put("contextKey", it) }
            },
            bearerToken = bearerToken
        ) { body ->
            AccountApiJsonContracts.parseSuccessResponse(body)
            Unit
        }
    }

    override suspend fun register(
        phone: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = authenticate(
        path = "/account/register",
        form = mapOf(
            "phone" to phone,
            "code" to code,
            "password" to password,
            "deviceId" to installationId()
        )
    )

    override suspend fun login(
        phone: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = authenticate(
        path = "/account/login",
        form = mapOf(
            "phone" to phone,
            "password" to password,
            "deviceId" to installationId()
        )
    )

    override suspend fun recoverPassword(
        phone: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = authenticate(
        path = "/account/recover",
        form = mapOf(
            "phone" to phone,
            "code" to code,
            "password" to password,
            "deviceId" to installationId()
        )
    )

    override suspend fun verifySession(
        credentials: AccountCredentials
    ): AccountRepositoryResult<AccountCredentials> {
        return execute(
            path = "/account/token/verify",
            bearerToken = credentials.token
        ) { body ->
            val response = AccountApiJsonContracts.parseSessionResponse(body)
            credentials.copy(
                phone = response.phone ?: credentials.phone,
                deletionState = response.deletionStatus.toUiState(),
                wechatLinked = response.wechatLinked,
                nickname = response.nickname,
                avatarUrl = response.avatarUrl
            )
        }
    }

    override suspend fun signOut(token: String): AccountRepositoryResult<Unit> {
        return execute(path = "/account/logout", bearerToken = token) { body ->
            AccountApiJsonContracts.parseSuccessResponse(body)
            Unit
        }
    }

    override suspend fun getDeletionStatus(
        token: String
    ): AccountRepositoryResult<AccountDeletionUiState> = deletionRequest(
        path = "/account/delete/status",
        token = token
    )

    override suspend fun requestDeletion(
        token: String
    ): AccountRepositoryResult<AccountDeletionUiState> = deletionRequest(
        path = "/account/delete/request",
        token = token
    )

    override suspend fun cancelDeletion(token: String): AccountRepositoryResult<Unit> {
        return execute(path = "/account/delete/cancel", bearerToken = token) { body ->
            AccountApiJsonContracts.parseSuccessResponse(body)
            Unit
        }
    }

    override suspend fun exchangeWechatCode(
        code: String,
        bearerToken: String?
    ): AccountRepositoryResult<AccountWechatAuthResult> {
        return execute(
            path = "/account/wechat/exchange",
            form = mapOf("code" to code, "deviceId" to installationId()),
            bearerToken = bearerToken
        ) { body ->
            when (val result = AccountApiJsonContracts.parseWechatExchangeResponse(body).result) {
                is WechatAuthResultContract.SignedIn -> AccountWechatAuthResult.SignedIn(
                    result.session.toCredentials()
                )
                is WechatAuthResultContract.RegistrationRequired ->
                    AccountWechatAuthResult.RegistrationRequired(
                        wechatTicket = result.wechatTicket,
                        nickname = result.nickname,
                        avatarUrl = result.avatarUrl,
                        ticketExpiresAtMillis = result.ticketExpiresAtMillis
                    )
                is WechatAuthResultContract.MergeRequired -> AccountWechatAuthResult.MergeRequired(
                    mergeTicket = result.mergeTicket,
                    sourceNickname = result.sourceNickname,
                    sourcePhone = result.sourcePhone,
                    ticketExpiresAtMillis = result.ticketExpiresAtMillis
                )
            }
        }
    }

    override suspend fun registerWithWechat(
        wechatTicket: String
    ): AccountRepositoryResult<AccountCredentials> = authenticateWechat(
        path = "/account/wechat/register",
        form = mapOf("wechatTicket" to wechatTicket, "deviceId" to installationId())
    )

    override suspend fun linkWechatWithPassword(
        wechatTicket: String,
        phone: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = authenticateWechat(
        path = "/account/wechat/link/password",
        form = mapOf(
            "wechatTicket" to wechatTicket,
            "phone" to phone,
            "password" to password,
            "deviceId" to installationId()
        )
    )

    override suspend fun linkWechatWithSms(
        wechatTicket: String,
        phone: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials> = authenticateWechat(
        path = "/account/wechat/link/sms",
        form = mapOf(
            "wechatTicket" to wechatTicket,
            "phone" to phone,
            "code" to code,
            "deviceId" to installationId()
        )
    )

    override suspend fun preparePhoneLink(
        token: String,
        phone: String,
        code: String
    ): AccountRepositoryResult<PhoneLinkPrepareResponseContract> = execute(
        path = "/account/phone/link/prepare",
        form = mapOf("phone" to phone, "code" to code),
        bearerToken = token,
        parse = AccountApiJsonContracts::parsePhoneLinkPrepareResponse
    )

    override suspend fun completePhoneLink(
        token: String,
        phoneTicket: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = authenticateWechat(
        path = "/account/phone/link/complete",
        form = mapOf(
            "phoneTicket" to phoneTicket,
            "password" to password,
            "deviceId" to installationId()
        ),
        bearerToken = token
    )

    override suspend fun prepareMergeWithPhonePassword(
        token: String,
        phone: String,
        password: String
    ): AccountRepositoryResult<MergePreviewResponseContract> = execute(
        path = "/account/merge/prepare/phone-password",
        form = mapOf("phone" to phone, "password" to password),
        bearerToken = token,
        parse = AccountApiJsonContracts::parseMergePreviewResponse
    )

    override suspend fun confirmMerge(
        token: String,
        mergeTicket: String,
        confirmText: String
    ): AccountRepositoryResult<AccountCredentials> = authenticateWechat(
        path = "/account/merge/confirm",
        form = mapOf(
            "mergeTicket" to mergeTicket,
            "confirmText" to confirmText,
            "deviceId" to installationId()
        ),
        bearerToken = token
    )

    override suspend fun unlinkWechatWithPassword(
        token: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = authenticateWechat(
        path = "/account/wechat/unlink/password",
        form = mapOf("password" to password, "deviceId" to installationId()),
        bearerToken = token
    )

    override suspend fun unlinkWechatWithSms(
        token: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials> = authenticateWechat(
        path = "/account/wechat/unlink/sms",
        form = mapOf("code" to code, "deviceId" to installationId()),
        bearerToken = token
    )

    private suspend fun authenticate(
        path: String,
        form: Map<String, String>
    ): AccountRepositoryResult<AccountCredentials> {
        return execute(path = path, form = form) { body ->
            val response = AccountApiJsonContracts.parseSessionResponse(body)
            val token = requireNotNull(response.token) { "Account response did not include a token." }
            val phone = requireNotNull(response.phone) { "Account response did not include a phone." }
            response.toCredentials().copy(phone = phone, token = token)
        }
    }

    private suspend fun authenticateWechat(
        path: String,
        form: Map<String, String>,
        bearerToken: String? = null
    ): AccountRepositoryResult<AccountCredentials> {
        return execute(path = path, form = form, bearerToken = bearerToken) { body ->
            AccountApiJsonContracts.parseSessionResponse(body).toCredentials()
        }
    }

    private suspend fun deletionRequest(
        path: String,
        token: String
    ): AccountRepositoryResult<AccountDeletionUiState> {
        return execute(path = path, bearerToken = token) { body ->
            AccountApiJsonContracts.parseDeletionStatusResponse(body).toUiState()
        }
    }

    private suspend fun <T> execute(
        path: String,
        form: Map<String, String> = emptyMap(),
        bearerToken: String? = null,
        parse: (String) -> T
    ): AccountRepositoryResult<T> {
        if (baseUrl.isBlank()) return configurationMissing()
        val response = try {
            transport.post("$baseUrl$path", form, bearerToken)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            return AccountRepositoryResult.Failure(
                kind = AccountFailureKind.Network,
                message = "网络连接失败，请检查网络后重试"
            )
        } catch (_: RuntimeException) {
            return AccountRepositoryResult.Failure(
                kind = AccountFailureKind.Network,
                message = "账号服务暂时无法连接，请稍后重试"
            )
        }

        if (response.statusCode !in 200..299) return response.toFailure()
        return try {
            AccountRepositoryResult.Success(parse(response.body))
        } catch (_: RuntimeException) {
            invalidResponse()
        }
    }

    private fun AccountHttpResponse.toFailure(): AccountRepositoryResult.Failure {
        val error = try {
            AccountApiJsonContracts.parseErrorResponse(body)
        } catch (_: RuntimeException) {
            return invalidResponse()
        }
        return when {
            error.error == "TOKEN_INVALID" ->
                AccountRepositoryResult.Failure(
                    kind = AccountFailureKind.InvalidSession,
                    code = error.error,
                    message = "登录状态已失效，请重新登录"
                )
            statusCode == 429 || error.error == "SMS_TOO_FREQUENT" ->
                AccountRepositoryResult.Failure(
                    kind = AccountFailureKind.RateLimited,
                    code = error.error,
                    message = error.message
                )
            else -> AccountRepositoryResult.Failure(
                kind = AccountFailureKind.Service,
                code = error.error,
                message = error.message
            )
        }
    }

    private fun configurationMissing(): AccountRepositoryResult.Failure =
        AccountRepositoryResult.Failure(
            kind = AccountFailureKind.ConfigurationMissing,
            message = "账号服务暂不可用，仍可继续使用本地模式"
        )

    private fun invalidResponse(): AccountRepositoryResult.Failure =
        AccountRepositoryResult.Failure(
            kind = AccountFailureKind.InvalidResponse,
            message = "账号服务返回异常，请稍后重试"
        )
}

private fun AccountDeletionStatusContract.toUiState(): AccountDeletionUiState =
    AccountDeletionUiState(
        requestedAtEpochMillis = requestedAtMillis,
        finalDeletionAtEpochMillis = finalDeletionAtMillis
    )

private fun AccountSessionResponseContract.toCredentials(): AccountCredentials {
    val sessionToken = requireNotNull(token) { "Account response did not include a token." }
    return AccountCredentials(
        phone = phone,
        token = sessionToken,
        deletionState = deletionStatus.toUiState(),
        wechatLinked = wechatLinked,
        nickname = nickname,
        avatarUrl = avatarUrl
    )
}

private fun String.formEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
