package com.bks.feature.account

import com.bks.api.AccountApiJsonContracts
import com.bks.api.AccountDeletionStatusContract
import com.bks.api.AccountSessionResponseContract
import com.bks.api.MergePreviewResponseContract
import com.bks.api.IdentifierLinkPrepareResponseContract
import com.bks.api.WechatAuthResultContract
import com.bks.feature.categorization.toBackendEndpointOrNull
import java.io.ByteArrayOutputStream
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
                    ?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytesRead = input.read(buffer)
                        while (bytesRead >= 0) {
                            if (output.size() + bytesRead > MAX_RESPONSE_BYTES) {
                                throw IOException("Account response is too large")
                            }
                            output.write(buffer, 0, bytesRead)
                            bytesRead = input.read(buffer)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
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
        const val MAX_RESPONSE_BYTES = 256 * 1024
    }
}

internal class HttpAccountRepository(
    backendUrl: String,
    private val installationId: () -> String,
    private val transport: AccountHttpTransport = HttpUrlConnectionAccountTransport(),
    allowHttp: Boolean = false
) : AccountRepository {
    private val baseUrl = backendUrl.toBackendEndpointOrNull("", allowHttp)?.trimEnd('/').orEmpty()

    override suspend fun requestVerificationCode(
        identifier: String,
        purpose: AccountVerificationPurpose,
        contextKey: String?,
        bearerToken: String?
    ): AccountRepositoryResult<Unit> {
        return execute(
            path = "/account/verification-code",
            form = buildMap {
                put("identifier", identifier)
                put("deviceId", installationId())
                put("purpose", purpose.wireValue)
                contextKey?.let { put("contextKey", it) }
            },
            bearerToken = bearerToken
        ) { body ->
            AccountApiJsonContracts.parseSuccessResponse(body)
            Unit
        }
    }

    override suspend fun register(
        identifier: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = authenticate(
        path = "/account/register",
        form = mapOf(
            "identifier" to identifier,
            "code" to code,
            "password" to password,
            "deviceId" to installationId()
        )
    )

    override suspend fun login(
        identifier: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = authenticate(
        path = "/account/login",
        form = mapOf(
            "identifier" to identifier,
            "password" to password,
            "deviceId" to installationId()
        )
    )

    override suspend fun recoverPassword(
        identifier: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = authenticate(
        path = "/account/recover",
        form = mapOf(
            "identifier" to identifier,
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
                accountId = response.accountId ?: credentials.accountId,
                accountUuid = response.accountUuid ?: credentials.accountUuid,
                primaryIdentifier = response.primaryIdentifier ?: credentials.primaryIdentifier,
                identifiers = response.identifiers.ifEmpty { credentials.identifiers },
                rawPhone = credentials.rawPhone,
                deletionState = response.deletionStatus.toUiState(),
                wechatLinked = response.wechatLinked,
                nickname = response.nickname,
                avatarUrl = response.avatarUrl
            )
        }
    }

    override suspend fun updateNickname(
        credentials: AccountCredentials,
        nickname: String
    ): AccountRepositoryResult<AccountCredentials> {
        return execute(
            path = "/account/profile/nickname",
            form = mapOf("nickname" to nickname),
            bearerToken = credentials.token
        ) { body ->
            val response = AccountApiJsonContracts.parseSessionResponse(body)
            credentials.copy(
                accountId = response.accountId ?: credentials.accountId,
                accountUuid = response.accountUuid ?: credentials.accountUuid,
                primaryIdentifier = response.primaryIdentifier ?: credentials.primaryIdentifier,
                identifiers = response.identifiers.ifEmpty { credentials.identifiers },
                deletionState = response.deletionStatus.toUiState(),
                wechatLinked = response.wechatLinked,
                nickname = response.nickname,
                avatarUrl = response.avatarUrl
            )
        }
    }

    override suspend fun updateAvatar(
        credentials: AccountCredentials,
        avatarDataUrl: String
    ): AccountRepositoryResult<AccountCredentials> {
        return execute(
            path = "/account/profile/avatar",
            form = mapOf("avatarDataUrl" to avatarDataUrl),
            bearerToken = credentials.token
        ) { body ->
            val response = AccountApiJsonContracts.parseSessionResponse(body)
            credentials.copy(
                accountId = response.accountId ?: credentials.accountId,
                accountUuid = response.accountUuid ?: credentials.accountUuid,
                primaryIdentifier = response.primaryIdentifier ?: credentials.primaryIdentifier,
                identifiers = response.identifiers.ifEmpty { credentials.identifiers },
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
                    sourceIdentifiers = result.sourceIdentifiers,
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
        identifier: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = authenticateWechat(
        path = "/account/wechat/link/password",
        form = mapOf(
            "wechatTicket" to wechatTicket,
            "identifier" to identifier,
            "password" to password,
            "deviceId" to installationId()
        )
    )

    override suspend fun linkWechatWithCode(
        wechatTicket: String,
        identifier: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials> = authenticateWechat(
        path = "/account/wechat/link/code",
        form = mapOf(
            "wechatTicket" to wechatTicket,
            "identifier" to identifier,
            "code" to code,
            "deviceId" to installationId()
        )
    )

    override suspend fun prepareIdentifierLink(
        token: String,
        identifier: String,
        replaceExisting: Boolean
    ): AccountRepositoryResult<IdentifierLinkPrepareResponseContract> = execute(
        path = "/account/identifier/link/prepare",
        form = mapOf(
            "identifier" to identifier,
            "deviceId" to installationId()
        ) + if (replaceExisting) mapOf("replaceExisting" to "true") else emptyMap(),
        bearerToken = token,
        parse = AccountApiJsonContracts::parseIdentifierLinkPrepareResponse
    )

    override suspend fun completeIdentifierLink(
        token: String,
        linkTicket: String,
        code: String,
        password: String?
    ): AccountRepositoryResult<AccountCredentials> = authenticateWechat(
        path = "/account/identifier/link/complete",
        form = buildMap {
            put("linkTicket", linkTicket)
            put("code", code)
            put("deviceId", installationId())
            password?.let { put("password", it) }
        },
        bearerToken = token
    )

    override suspend fun prepareMergeWithIdentifierPassword(
        token: String,
        identifier: String,
        password: String
    ): AccountRepositoryResult<MergePreviewResponseContract> = execute(
        path = "/account/merge/prepare/identifier-password",
        form = mapOf("identifier" to identifier, "password" to password),
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

    override suspend fun unlinkWechatWithCode(
        token: String,
        identifier: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials> = authenticateWechat(
        path = "/account/wechat/unlink/code",
        form = mapOf(
            "identifier" to identifier,
            "code" to code,
            "deviceId" to installationId()
        ),
        bearerToken = token
    )

    private suspend fun authenticate(
        path: String,
        form: Map<String, String>
    ): AccountRepositoryResult<AccountCredentials> {
        return execute(path = path, form = form) { body ->
            val response = AccountApiJsonContracts.parseSessionResponse(body)
            val token = requireNotNull(response.token) { "Account response did not include a token." }
            response.toCredentials().copy(token = token)
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
        accountId = accountId,
        accountUuid = accountUuid,
        primaryIdentifier = primaryIdentifier,
        identifiers = identifiers,
        rawPhone = null,
        token = sessionToken,
        deletionState = deletionStatus.toUiState(),
        wechatLinked = wechatLinked,
        nickname = nickname,
        avatarUrl = avatarUrl
    )
}

private fun String.formEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
