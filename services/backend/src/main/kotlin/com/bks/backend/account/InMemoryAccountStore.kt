package com.bks.backend.account

class InMemoryAccountStore private constructor(
    components: InMemoryAccountComponents
) : AccountStore,
    AccountLifecycleStore by components.lifecycleStore,
    AccountIdentifierStore by components.identifierStore,
    AccountProfileStore by components.profileStore,
    AccountVerificationStore by components.verificationStore,
    AccountSessionStore by components.sessionStore,
    AccountWechatStore by components.wechatStore,
    AccountWechatTransactionStore by components.transactions {

    constructor() : this(createInMemoryAccountComponents())
}

private data class InMemoryAccountComponents(
    val lifecycleStore: InMemoryAccountLifecycleStore,
    val identifierStore: InMemoryAccountIdentifierStore,
    val profileStore: InMemoryAccountProfileStore,
    val verificationStore: InMemoryAccountVerificationStore,
    val sessionStore: InMemoryAccountSessionStore,
    val wechatStore: InMemoryAccountWechatStore,
    val transactions: InMemoryAccountTransactions
)

private fun createInMemoryAccountComponents(): InMemoryAccountComponents {
    val state = InMemoryAccountState()
    return InMemoryAccountComponents(
        lifecycleStore = InMemoryAccountLifecycleStore(state),
        identifierStore = InMemoryAccountIdentifierStore(state),
        profileStore = InMemoryAccountProfileStore(state),
        verificationStore = InMemoryAccountVerificationStore(state),
        sessionStore = InMemoryAccountSessionStore(state),
        wechatStore = InMemoryAccountWechatStore(state),
        transactions = InMemoryAccountTransactions(state)
    )
}
