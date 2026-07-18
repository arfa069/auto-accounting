package com.autoaccounting.feature.account

interface AccountRepository {
    suspend fun requestSmsCode(phone: String): AccountRepositoryResult<Unit>

    suspend fun register(
        phone: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun login(
        phone: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun recoverPassword(
        phone: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun verifySession(
        credentials: AccountCredentials
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun signOut(token: String): AccountRepositoryResult<Unit>

    suspend fun getDeletionStatus(token: String): AccountRepositoryResult<AccountDeletionUiState>

    suspend fun requestDeletion(token: String): AccountRepositoryResult<AccountDeletionUiState>

    suspend fun cancelDeletion(token: String): AccountRepositoryResult<Unit>
}

data class AccountCredentials(
    val phone: String,
    val token: String,
    val deletionState: AccountDeletionUiState = AccountDeletionUiState()
)

enum class AccountFailureKind {
    ConfigurationMissing,
    Network,
    InvalidSession,
    RateLimited,
    Service,
    InvalidResponse
}

sealed interface AccountRepositoryResult<out T> {
    data class Success<T>(val value: T) : AccountRepositoryResult<T>

    data class Failure(
        val kind: AccountFailureKind,
        val message: String,
        val code: String? = null
    ) : AccountRepositoryResult<Nothing>
}

class FakeAccountRepository : AccountRepository {
    private var deletionState = AccountDeletionUiState()

    override suspend fun requestSmsCode(phone: String): AccountRepositoryResult<Unit> =
        AccountRepositoryResult.Success(Unit)

    override suspend fun register(
        phone: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = success(phone)

    override suspend fun login(
        phone: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = success(phone)

    override suspend fun recoverPassword(
        phone: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = success(phone)

    override suspend fun verifySession(
        credentials: AccountCredentials
    ): AccountRepositoryResult<AccountCredentials> = AccountRepositoryResult.Success(
        credentials.copy(deletionState = deletionState)
    )

    override suspend fun signOut(token: String): AccountRepositoryResult<Unit> =
        AccountRepositoryResult.Success(Unit)

    override suspend fun getDeletionStatus(
        token: String
    ): AccountRepositoryResult<AccountDeletionUiState> = AccountRepositoryResult.Success(deletionState)

    override suspend fun requestDeletion(
        token: String
    ): AccountRepositoryResult<AccountDeletionUiState> {
        deletionState = AccountDeletionUiState(
            requestedAtEpochMillis = 1_000,
            finalDeletionAtEpochMillis = 604_801_000
        )
        return AccountRepositoryResult.Success(deletionState)
    }

    override suspend fun cancelDeletion(token: String): AccountRepositoryResult<Unit> {
        deletionState = AccountDeletionUiState()
        return AccountRepositoryResult.Success(Unit)
    }

    private fun success(phone: String): AccountRepositoryResult<AccountCredentials> =
        AccountRepositoryResult.Success(
            AccountCredentials(phone = phone, token = "mock-token", deletionState = deletionState)
        )
}
