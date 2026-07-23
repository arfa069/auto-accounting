package com.autoaccounting.feature.account

import com.autoaccounting.api.MergePreviewResponseContract
import com.autoaccounting.api.PhoneLinkPrepareResponseContract

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
    var wechatExchangeResult: AccountRepositoryResult<AccountWechatAuthResult>? = null
    var registerWithWechatResult: AccountRepositoryResult<AccountCredentials>? = null
    var linkWechatWithPasswordResult: AccountRepositoryResult<AccountCredentials>? = null
    var linkWechatWithSmsResult: AccountRepositoryResult<AccountCredentials>? = null
    var phoneLinkPrepareResult: AccountRepositoryResult<PhoneLinkPrepareResponseContract>? = null
    var phoneLinkCompleteResult: AccountRepositoryResult<AccountCredentials>? = null
    var mergePrepareResult: AccountRepositoryResult<MergePreviewResponseContract>? = null
    var mergeConfirmResult: AccountRepositoryResult<AccountCredentials>? = null
    var unlinkWechatWithPasswordResult: AccountRepositoryResult<AccountCredentials>? = null
    var unlinkWechatWithSmsResult: AccountRepositoryResult<AccountCredentials>? = null
    var smsCalls = 0
    var lastSmsPurpose: AccountSmsPurpose? = null
    var lastSmsContextKey: String? = null
    var exchangeWechatCalls = 0
    var registerWithWechatCalls = 0
    var linkWechatWithPasswordCalls = 0
    var linkWechatWithSmsCalls = 0
    var preparePhoneLinkCalls = 0
    var completePhoneLinkCalls = 0
    var prepareMergeCalls = 0
    var confirmMergeCalls = 0
    var unlinkWechatWithPasswordCalls = 0
    var unlinkWechatWithSmsCalls = 0
    var signOutCalls = 0
    var requestDeletionCalls = 0

    override suspend fun requestSmsCode(
        phone: String,
        purpose: AccountSmsPurpose,
        contextKey: String?,
        bearerToken: String?
    ): AccountRepositoryResult<Unit> {
        smsCalls += 1
        lastSmsPurpose = purpose
        lastSmsContextKey = contextKey
        return smsResult
    }

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
        phone: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> {
        linkWechatWithPasswordCalls += 1
        return linkWechatWithPasswordResult ?: authenticationResult
    }

    override suspend fun linkWechatWithSms(
        wechatTicket: String,
        phone: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials> {
        linkWechatWithSmsCalls += 1
        return linkWechatWithSmsResult ?: authenticationResult
    }

    override suspend fun preparePhoneLink(
        token: String,
        phone: String,
        code: String
    ): AccountRepositoryResult<PhoneLinkPrepareResponseContract> {
        preparePhoneLinkCalls += 1
        return phoneLinkPrepareResult ?: AccountRepositoryResult.Success(
            PhoneLinkPrepareResponseContract.PhoneTicketIssued("phone-ticket", 300_000L)
        )
    }

    override suspend fun completePhoneLink(
        token: String,
        phoneTicket: String,
        password: String
    ): AccountRepositoryResult<AccountCredentials> {
        completePhoneLinkCalls += 1
        return phoneLinkCompleteResult ?: authenticationResult
    }

    override suspend fun prepareMergeWithPhonePassword(
        token: String,
        phone: String,
        password: String
    ): AccountRepositoryResult<MergePreviewResponseContract> {
        prepareMergeCalls += 1
        return mergePrepareResult ?: AccountRepositoryResult.Success(
            MergePreviewResponseContract("merge-ticket", 300_000L, null, true, null, phone, false, null)
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

    override suspend fun unlinkWechatWithSms(
        token: String,
        code: String
    ): AccountRepositoryResult<AccountCredentials> {
        unlinkWechatWithSmsCalls += 1
        return unlinkWechatWithSmsResult ?: authenticationResult
    }
}
