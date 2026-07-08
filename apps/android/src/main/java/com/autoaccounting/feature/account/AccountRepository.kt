package com.autoaccounting.feature.account

interface AccountRepository {
    fun requestSmsCode(phone: String): AccountRepositoryResult<Unit>

    fun register(
        phone: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    fun login(
        phone: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    fun recoverPassword(
        phone: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>
}

data class AccountCredentials(
    val phone: String,
    val token: String
)

sealed interface AccountRepositoryResult<out T> {
    data class Success<T>(val value: T) : AccountRepositoryResult<T>
    data class Failure(val message: String) : AccountRepositoryResult<Nothing>
}

class FakeAccountRepository : AccountRepository {
    override fun requestSmsCode(phone: String): AccountRepositoryResult<Unit> {
        return AccountRepositoryResult.Success(Unit)
    }

    override fun register(
        phone: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> {
        return AccountRepositoryResult.Success(AccountCredentials(phone, "mock-token"))
    }

    override fun login(
        phone: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> {
        return AccountRepositoryResult.Success(AccountCredentials(phone, "mock-token"))
    }

    override fun recoverPassword(
        phone: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> {
        return AccountRepositoryResult.Success(AccountCredentials(phone, "mock-token"))
    }
}
