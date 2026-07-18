package com.autoaccounting.feature.account

internal class TestAccountRepository : AccountRepository {
    var smsResult: AccountRepositoryResult<Unit> = AccountRepositoryResult.Success(Unit)
    var authenticationResult: AccountRepositoryResult<AccountCredentials> =
        AccountRepositoryResult.Success(AccountCredentials("13800138000", "token-1"))
    var verificationResult: AccountRepositoryResult<AccountCredentials> = authenticationResult
    var signOutResult: AccountRepositoryResult<Unit> = AccountRepositoryResult.Success(Unit)
    var deletionResult: AccountRepositoryResult<AccountDeletionUiState> =
        AccountRepositoryResult.Success(
            AccountDeletionUiState(1_000, 604_801_000)
        )
    var cancelDeletionResult: AccountRepositoryResult<Unit> = AccountRepositoryResult.Success(Unit)
    var signOutCalls = 0
    var requestDeletionCalls = 0

    override suspend fun requestSmsCode(phone: String): AccountRepositoryResult<Unit> = smsResult

    override suspend fun register(
        phone: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = authenticationResult

    override suspend fun login(
        phone: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = authenticationResult

    override suspend fun recoverPassword(
        phone: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = authenticationResult

    override suspend fun verifySession(
        credentials: AccountCredentials
    ): AccountRepositoryResult<AccountCredentials> = verificationResult

    override suspend fun signOut(token: String): AccountRepositoryResult<Unit> {
        signOutCalls += 1
        return signOutResult
    }

    override suspend fun getDeletionStatus(
        token: String
    ): AccountRepositoryResult<AccountDeletionUiState> = deletionResult

    override suspend fun requestDeletion(
        token: String
    ): AccountRepositoryResult<AccountDeletionUiState> {
        requestDeletionCalls += 1
        return deletionResult
    }

    override suspend fun cancelDeletion(token: String): AccountRepositoryResult<Unit> =
        cancelDeletionResult
}
