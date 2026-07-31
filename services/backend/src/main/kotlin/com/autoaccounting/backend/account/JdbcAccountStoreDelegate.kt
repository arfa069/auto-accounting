package com.autoaccounting.backend.account

internal class JdbcAccountStoreDelegate private constructor(
    lifecycleProfileStore: JdbcAccountLifecycleProfileStore,
    identifierStore: JdbcAccountIdentifierStore,
    verificationStore: JdbcAccountVerificationStore,
    sessionStore: JdbcAccountSessionStore,
    wechatStore: JdbcAccountWechatStore,
    transactionStore: JdbcAccountTransactionStore
) : AccountStore,
    AccountLifecycleStore by lifecycleProfileStore,
    AccountIdentifierStore by identifierStore,
    AccountProfileStore by lifecycleProfileStore,
    AccountVerificationStore by verificationStore,
    AccountSessionStore by sessionStore,
    AccountWechatStore by wechatStore,
    AccountWechatTransactionStore by transactionStore {

    private constructor(context: JdbcAccountStoreContext) : this(
        lifecycleProfileStore = JdbcAccountLifecycleProfileStore(context),
        identifierStore = JdbcAccountIdentifierStore(context),
        verificationStore = JdbcAccountVerificationStore(context),
        sessionStore = JdbcAccountSessionStore(context),
        wechatStore = JdbcAccountWechatStore(context),
        transactionStore = JdbcAccountTransactionStore(
            credentialTransactions = JdbcWechatCredentialTransactions(context),
            mergeTransaction = JdbcAccountMergeTransaction(context)
        )
    )

    constructor(jdbcUrl: String, username: String = "", password: String = "") : this(
        JdbcAccountStoreContext(jdbcUrl, username, password)
    )
}
