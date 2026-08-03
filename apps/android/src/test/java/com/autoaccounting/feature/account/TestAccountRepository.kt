package com.autoaccounting.feature.account

import com.autoaccounting.api.MergePreviewResponseContract
import com.autoaccounting.api.IdentifierLinkPrepareResponseContract
import kotlinx.coroutines.CompletableDeferred

internal class TestAccountRepository : AccountRepository {
    var smsResult: AccountRepositoryResult<Unit> = AccountRepositoryResult.Success(Unit)
    var authenticationResult: AccountRepositoryResult<AccountCredentials> =
        AccountRepositoryResult.Success(AccountCredentials("13800138000", "token-1"))
    var verificationResult: AccountRepositoryResult<AccountCredentials> = authenticationResult
    var updateNicknameResult: AccountRepositoryResult<AccountCredentials>? = null
    var updateAvatarResult: AccountRepositoryResult<AccountCredentials>? = null
    var signOutResult: AccountRepositoryResult<Unit> = AccountRepositoryResult.Success(Unit)
    var deletionResult: AccountRepositoryResult<AccountDeletionUiState> =
        AccountRepositoryResult.Success(
            AccountDeletionUiState(1_000, 604_801_000)
        )
    var cancelDeletionResult: AccountRepositoryResult<Unit> = AccountRepositoryResult.Success(Unit)
    var wechatExchangeResult: AccountRepositoryResult<AccountWechatAuthResult>? = null
    var registerWithWechatResult: AccountRepositoryResult<AccountCredentials>? = null
    var linkWechatWithPasswordResult: AccountRepositoryResult<AccountCredentials>? = null
    var linkWechatWithCodeResult: AccountRepositoryResult<AccountCredentials>? = null
    var phoneLinkPrepareResult: AccountRepositoryResult<IdentifierLinkPrepareResponseContract>? = null
    var prepareIdentifierLinkGate: CompletableDeferred<Unit>? = null
    var phoneLinkCompleteResult: AccountRepositoryResult<AccountCredentials>? = null
    var mergePrepareResult: AccountRepositoryResult<MergePreviewResponseContract>? = null
    var mergeConfirmResult: AccountRepositoryResult<AccountCredentials>? = null
    var unlinkWechatWithPasswordResult: AccountRepositoryResult<AccountCredentials>? = null
    var unlinkWechatWithCodeResult: AccountRepositoryResult<AccountCredentials>? = null
    var smsGate: CompletableDeferred<AccountRepositoryResult<Unit>>? = null
    var smsCalls = 0
    var lastSmsIdentifier: String? = null
    var lastSmsPurpose: AccountVerificationPurpose? = null
    var lastSmsContextKey: String? = null
    var exchangeWechatCalls = 0
    var registerWithWechatCalls = 0
    var linkWechatWithPasswordCalls = 0
    var linkWechatWithCodeCalls = 0
    var prepareIdentifierLinkCalls = 0
    var completeIdentifierLinkCalls = 0
    var lastCompleteIdentifierPassword: String? = null
    var prepareMergeCalls = 0
    var confirmMergeCalls = 0
    var unlinkWechatWithPasswordCalls = 0
    var unlinkWechatWithCodeCalls = 0
    var lastUnlinkWechatIdentifier: String? = null
    var lastUnlinkWechatCode: String? = null
    var signOutCalls = 0
    var requestDeletionCalls = 0
    var updateNicknameCalls = 0
    var updateAvatarCalls = 0
    var lastNickname: String? = null
    var lastAvatarDataUrl: String? = null
    var lastReplaceExisting = false

    override suspend fun requestVerificationCode(
        identifier: String,
        purpose: AccountVerificationPurpose,
        contextKey: String?,
        bearerToken: String?
    ): AccountRepositoryResult<Unit> {
        smsCalls += 1
        lastSmsIdentifier = identifier
        lastSmsPurpose = purpose
        lastSmsContextKey = contextKey
        return smsGate?.await() ?: smsResult
    }

    override suspend fun register(
        identifier: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = authenticationResult

    override suspend fun login(
        identifier: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = authenticationResult

    override suspend fun recoverPassword(
        identifier: String,
        code: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> = authenticationResult

    override suspend fun verifySession(
        credentials: AccountCredentials
    ): AccountRepositoryResult<AccountCredentials> = verificationResult

    override suspend fun updateNickname(
        credentials: AccountCredentials,
        nickname: String
    ): AccountRepositoryResult<AccountCredentials> {
        updateNicknameCalls += 1
        lastNickname = nickname
        return updateNicknameResult ?: AccountRepositoryResult.Success(
            credentials.copy(nickname = nickname)
        )
    }

    override suspend fun updateAvatar(
        credentials: AccountCredentials,
        avatarDataUrl: String
    ): AccountRepositoryResult<AccountCredentials> {
        updateAvatarCalls += 1
        lastAvatarDataUrl = avatarDataUrl
        return updateAvatarResult ?: AccountRepositoryResult.Success(
            credentials.copy(avatarUrl = avatarDataUrl)
        )
    }

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

    override suspend fun exchangeWechatCode(
        code: String,
        bearerToken: String?
    ): AccountRepositoryResult<AccountWechatAuthResult> {
        exchangeWechatCalls += 1
        return wechatExchangeResult ?: AccountRepositoryResult.Success(
            AccountWechatAuthResult.RegistrationRequired("ticket", "微信用户", null, 300_000L)
        )
    }

    override suspend fun registerWithWechat(
        wechatTicket: String
    ): AccountRepositoryResult<AccountCredentials> {
        registerWithWechatCalls += 1
        return registerWithWechatResult ?: authenticationResult
    }

    override suspend fun linkWechatWithPassword(
        wechatTicket: String,
        identifier: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> {
        linkWechatWithPasswordCalls += 1
        return linkWechatWithPasswordResult ?: authenticationResult
    }

    override suspend fun linkWechatWithCode(
        wechatTicket: String,
        identifier: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials> {
        linkWechatWithCodeCalls += 1
        return linkWechatWithCodeResult ?: authenticationResult
    }

    override suspend fun prepareIdentifierLink(
        token: String,
        identifier: String,
        replaceExisting: Boolean
    ): AccountRepositoryResult<IdentifierLinkPrepareResponseContract> {
        prepareIdentifierLinkCalls += 1
        lastReplaceExisting = replaceExisting
        prepareIdentifierLinkGate?.await()
        return phoneLinkPrepareResult ?: AccountRepositoryResult.Success(
            IdentifierLinkPrepareResponseContract.LinkTicketIssued("link-ticket", 300_000L)
        )
    }

    override suspend fun completeIdentifierLink(
        token: String,
        linkTicket: String,
        code: String,
        password: String?
    ): AccountRepositoryResult<AccountCredentials> {
        completeIdentifierLinkCalls += 1
        lastCompleteIdentifierPassword = password
        return phoneLinkCompleteResult ?: authenticationResult
    }

    override suspend fun prepareMergeWithIdentifierPassword(
        token: String,
        identifier: String,
        password: String
    ): AccountRepositoryResult<MergePreviewResponseContract> {
        prepareMergeCalls += 1
        return mergePrepareResult ?: AccountRepositoryResult.Success(
            MergePreviewResponseContract(
                mergeTicket = "merge-ticket",
                ticketExpiresAtMillis = 300_000L,
                currentWechatLinked = true,
                currentNickname = null,
                sourceIdentifiers = listOf(
                    com.autoaccounting.api.AccountIdentifierParser.parse(identifier).toContract()
                ),
                sourceWechatLinked = false,
                sourceNickname = null
            )
        )
    }

    override suspend fun confirmMerge(
        token: String,
        mergeTicket: String,
        confirmText: String
    ): AccountRepositoryResult<AccountCredentials> {
        confirmMergeCalls += 1
        return mergeConfirmResult ?: authenticationResult
    }

    override suspend fun unlinkWechatWithPassword(
        token: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> {
        unlinkWechatWithPasswordCalls += 1
        return unlinkWechatWithPasswordResult ?: authenticationResult
    }

    override suspend fun unlinkWechatWithCode(
        token: String,
        identifier: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials> {
        unlinkWechatWithCodeCalls += 1
        lastUnlinkWechatIdentifier = identifier
        lastUnlinkWechatCode = code
        return unlinkWechatWithCodeResult ?: authenticationResult
    }
}
