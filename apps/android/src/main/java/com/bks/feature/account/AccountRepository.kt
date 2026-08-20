package com.bks.feature.account

import com.bks.api.MergePreviewResponseContract
import com.bks.api.IdentifierLinkPrepareResponseContract

interface AccountRepository {
    suspend fun requestVerificationCode(
        identifier: String,
        purpose: AccountVerificationPurpose,
        contextKey: String? = null,
        bearerToken: String? = null
    ): AccountRepositoryResult<Unit>

    suspend fun register(
        identifier: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun login(
        identifier: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun recoverPassword(
        identifier: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun verifySession(
        credentials: AccountCredentials
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun updateNickname(
        credentials: AccountCredentials,
        nickname: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun updateAvatar(
        credentials: AccountCredentials,
        avatarDataUrl: String
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
        identifier: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun linkWechatWithCode(
        wechatTicket: String,
        identifier: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun prepareIdentifierLink(
        token: String,
        identifier: String,
        replaceExisting: Boolean = false
    ): AccountRepositoryResult<IdentifierLinkPrepareResponseContract>

    suspend fun completeIdentifierLink(
        token: String,
        linkTicket: String,
        code: String,
        password: String? = null
    ): AccountRepositoryResult<AccountCredentials>

    suspend fun prepareMergeWithIdentifierPassword(
        token: String,
        identifier: String,
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

    suspend fun unlinkWechatWithCode(
        token: String,
        identifier: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials>
}

data class AccountCredentials(
    val accountId: Long? = null,
    val primaryIdentifier: com.bks.api.AccountIdentifierContract? = null,
    val identifiers: List<com.bks.api.AccountIdentifierContract> = emptyList(),
    val rawPhone: String? = null,
    val token: String,
    val accountUuid: String? = null,
    val deletionState: AccountDeletionUiState = AccountDeletionUiState(),
    val wechatLinked: Boolean = false,
    val nickname: String? = null,
    val avatarUrl: String? = null
) {
    constructor(
        phone: String?,
        token: String,
        deletionState: AccountDeletionUiState = AccountDeletionUiState(),
        wechatLinked: Boolean = false,
        nickname: String? = null,
        avatarUrl: String? = null
    ) : this(
        primaryIdentifier = phone?.let {
            com.bks.api.AccountIdentifierContract(
                type = com.bks.api.AccountIdentifierTypeContract.PHONE,
                value = it
            )
        },
        identifiers = phone?.let {
            listOf(
                com.bks.api.AccountIdentifierContract(
                    type = com.bks.api.AccountIdentifierTypeContract.PHONE,
                    value = it
                )
            )
        } ?: emptyList(),
        rawPhone = phone,
        token = token,
        deletionState = deletionState,
        wechatLinked = wechatLinked,
        nickname = nickname,
        avatarUrl = avatarUrl
    )

    val phone: String?
        get() = identifiers.find { it.type == com.bks.api.AccountIdentifierTypeContract.PHONE }?.value ?: rawPhone

    val email: String?
        get() = identifiers.find { it.type == com.bks.api.AccountIdentifierTypeContract.EMAIL }?.value

    val username: String?
        get() = identifiers.find { it.type == com.bks.api.AccountIdentifierTypeContract.USERNAME }?.value
}

enum class AccountVerificationPurpose(val wireValue: String) {
    Register("REGISTER"),
    Recovery("RECOVERY"),
    WechatLink("WECHAT_LINK"),
    IdentifierLink("IDENTIFIER_LINK"),
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
        val sourceIdentifiers: List<com.bks.api.AccountIdentifierContract>,
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

    override suspend fun requestVerificationCode(
        identifier: String,
        purpose: AccountVerificationPurpose,
        contextKey: String?,
        bearerToken: String?
    ): AccountRepositoryResult<Unit> =
        AccountRepositoryResult.Success(Unit)

    override suspend fun register(
        identifier: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = success(identifier)

    override suspend fun login(
        identifier: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = success(identifier)

    override suspend fun recoverPassword(
        identifier: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = success(identifier)

    override suspend fun verifySession(
        credentials: AccountCredentials
    ): AccountRepositoryResult<AccountCredentials> = AccountRepositoryResult.Success(
        credentials.copy(deletionState = deletionState)
    )

    override suspend fun updateNickname(
        credentials: AccountCredentials,
        nickname: String
    ): AccountRepositoryResult<AccountCredentials> = AccountRepositoryResult.Success(
        credentials.copy(
            deletionState = deletionState,
            nickname = nickname
        )
    )

    override suspend fun updateAvatar(
        credentials: AccountCredentials,
        avatarDataUrl: String
    ): AccountRepositoryResult<AccountCredentials> = AccountRepositoryResult.Success(
        credentials.copy(
            deletionState = deletionState,
            avatarUrl = avatarDataUrl
        )
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
        identifier: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = success(identifier, wechatLinked = true)

    override suspend fun linkWechatWithCode(
        wechatTicket: String,
        identifier: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials> = success(identifier, wechatLinked = true)

    override suspend fun prepareIdentifierLink(
        token: String,
        identifier: String,
        replaceExisting: Boolean
    ): AccountRepositoryResult<IdentifierLinkPrepareResponseContract> = AccountRepositoryResult.Success(
        IdentifierLinkPrepareResponseContract.LinkTicketIssued("mock-link-ticket", 300_000L)
    )

    override suspend fun completeIdentifierLink(
        token: String,
        linkTicket: String,
        code: String,
        password: String?
    ): AccountRepositoryResult<AccountCredentials> = success("13800138000", wechatLinked = true)

    override suspend fun prepareMergeWithIdentifierPassword(
        token: String,
        identifier: String,
        password: String
    ): AccountRepositoryResult<MergePreviewResponseContract> = AccountRepositoryResult.Success(
        MergePreviewResponseContract(
            mergeTicket = "mock-merge-ticket",
            ticketExpiresAtMillis = 300_000L,
            currentWechatLinked = true,
            currentNickname = "微信用户",
            sourceIdentifiers = listOf(
                com.bks.api.AccountIdentifierParser.parse(identifier).toContract()
            ),
            sourceWechatLinked = false,
            sourceNickname = null
        )
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

    override suspend fun unlinkWechatWithCode(
        token: String,
        identifier: String,
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
