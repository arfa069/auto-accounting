package com.autoaccounting.feature.account

import com.autoaccounting.api.AccountApiJsonContracts
import com.autoaccounting.api.AccountDeletionStatusContract
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

internal class HttpUrlConnectionAccountTransport(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AccountHttpTransport {
    override suspend fun post(
        url: String,
        form: Map<String, String>,
        bearerToken: String?
    ): AccountHttpResponse = withContext(ioDispatcher) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
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
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            AccountHttpResponse(statusCode = status, body = body)
        } finally {
            connection.disconnect()
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

    override suspend fun requestSmsCode(phone: String): AccountRepositoryResult<Unit> {
        return execute(
            path = "/account/sms",
            form = mapOf("phone" to phone, "deviceId" to installationId())
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
                phone = response.phone,
                deletionState = response.deletionStatus.toUiState()
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

    private suspend fun authenticate(
        path: String,
        form: Map<String, String>
    ): AccountRepositoryResult<AccountCredentials> {
        return execute(path = path, form = form) { body ->
            val response = AccountApiJsonContracts.parseSessionResponse(body)
            val token = requireNotNull(response.token) { "Account response did not include a token." }
            AccountCredentials(
                phone = response.phone,
                token = token,
                deletionState = response.deletionStatus.toUiState()
            )
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
            statusCode == HttpURLConnection.HTTP_UNAUTHORIZED || error.error == "TOKEN_INVALID" ->
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

private fun String.formEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
