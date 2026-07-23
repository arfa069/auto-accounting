package com.autoaccounting.feature.account

import com.autoaccounting.api.MergePreviewResponseContract
import com.autoaccounting.api.PhoneLinkPrepareResponseContract

interface AccountRepository {
    suspend fun requestSmsCode(
        phone: String,
        purpose: AccountSmsPurpose = AccountSmsPurpose.Default,
        contextKey: String? = null,
        bearerToken: String? = null
    ): AccountRepositoryResult<Unit>

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

    suspend fun exchangeWechatCode(
        code: String,
        bearerToken: String? = null
    ): AccountRepositoryResult<AccountWechatAuthResult>

    suspend fun registerWithWechat(wechatTicket: String): AccountRepositoryResult<AccountCredentials>

    suspend fun linkWechatWithPassword(
        wechatTicket: String,
        phone: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun linkWechatWithSms(
        wechatTicket: String,
        phone: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun preparePhoneLink(
        token: String,
        phone: String,
        code: String
    ): AccountRepositoryResult<PhoneLinkPrepareResponseContract>

    suspend fun completePhoneLink(
        token: String,
        phoneTicket: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun prepareMergeWithPhonePassword(
        token: String,
        phone: String,
        password: String
    ): AccountRepositoryResult<MergePreviewResponseContract>

    suspend fun confirmMerge(
        token: String,
        mergeTicket: String,
        confirmText: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun unlinkWechatWithPassword(
        token: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun unlinkWechatWithSms(
        token: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials>
}

data class AccountCredentials(
    val phone: String?,
    val token: String,
    val deletionState: AccountDeletionUiState = AccountDeletionUiState(),
    val wechatLinked: Boolean = false,
    val nickname: String? = null,
    val avatarUrl: String? = null
)

enum class AccountSmsPurpose(val wireValue: String) {
    Default("DEFAULT"),
    WechatLink("WECHAT_LINK"),
    PhoneLink("PHONE_LINK"),
    WechatUnlink("WECHAT_UNLINK")
}

sealed interface AccountWechatAuthResult {
    data class SignedIn(val credentials: AccountCredentials) : AccountWechatAuthResult

    data class RegistrationRequired(
        val wechatTicket: String,
        val nickname: String?,
        val avatarUrl: String?,
        val ticketExpiresAtMillis: Long
    ) : AccountWechatAuthResult

    data class MergeRequired(
        val mergeTicket: String,
        val sourceNickname: String?,
        val sourcePhone: String?,
        val ticketExpiresAtMillis: Long
    ) : AccountWechatAuthResult
}

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

    override suspend fun requestSmsCode(
        phone: String,
        purpose: AccountSmsPurpose,
        contextKey: String?,
        bearerToken: String?
    ): AccountRepositoryResult<Unit> =
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

    override suspend fun exchangeWechatCode(
        code: String,
        bearerToken: String?
    ): AccountRepositoryResult<AccountWechatAuthResult> = AccountRepositoryResult.Success(
        AccountWechatAuthResult.RegistrationRequired(
            wechatTicket = "mock-wechat-ticket",
            nickname = "微信用户",
            avatarUrl = null,
            ticketExpiresAtMillis = 300_000L
        )
    )

    override suspend fun registerWithWechat(
        wechatTicket: String
    ): AccountRepositoryResult<AccountCredentials> = wechatSuccess()

    override suspend fun linkWechatWithPassword(
        wechatTicket: String,
        phone: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = success(phone, wechatLinked = true)

    override suspend fun linkWechatWithSms(
        wechatTicket: String,
        phone: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials> = success(phone, wechatLinked = true)

    override suspend fun preparePhoneLink(
        token: String,
        phone: String,
        code: String
    ): AccountRepositoryResult<PhoneLinkPrepareResponseContract> = AccountRepositoryResult.Success(
        PhoneLinkPrepareResponseContract.PhoneTicketIssued("mock-phone-ticket", 300_000L)
    )

    override suspend fun completePhoneLink(
        token: String,
        phoneTicket: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = success("13800138000", wechatLinked = true)

    override suspend fun prepareMergeWithPhonePassword(
        token: String,
        phone: String,
        password: String
    ): AccountRepositoryResult<MergePreviewResponseContract> = AccountRepositoryResult.Success(
        MergePreviewResponseContract("mock-merge-ticket", 300_000L, null, true, "微信用户", phone, false, null)
    )

    override suspend fun confirmMerge(
        token: String,
        mergeTicket: String,
        confirmText: String
    ): AccountRepositoryResult<AccountCredentials> = success("13800138000", wechatLinked = true)

    override suspend fun unlinkWechatWithPassword(
        token: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = success("13800138000")

    override suspend fun unlinkWechatWithSms(
        token: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials> = success("13800138000")

    private fun success(
        phone: String,
        wechatLinked: Boolean = false
    ): AccountRepositoryResult<AccountCredentials> =
        AccountRepositoryResult.Success(
            AccountCredentials(
                phone = phone,
                token = "mock-token",
                deletionState = deletionState,
                wechatLinked = wechatLinked,
                nickname = "微信用户".takeIf { wechatLinked }
            )
        )

    private fun wechatSuccess(): AccountRepositoryResult<AccountCredentials> = AccountRepositoryResult.Success(
        AccountCredentials(
            phone = null,
            token = "mock-token",
            deletionState = deletionState,
            wechatLinked = true,
            nickname = "微信用户"
        )
    )
}
