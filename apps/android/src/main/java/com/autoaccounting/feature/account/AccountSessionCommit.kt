package com.autoaccounting.feature.account

internal suspend fun persistAccountSessionOrRevoke(
    credentials: AccountCredentials,
    accountRepository: AccountRepository,
    persistSession: (AccountCredentials) -> Boolean,
    clearPersistedSession: () -> Boolean
): Boolean {
    if (persistSession(credentials)) return true

    clearPersistedSession()
    accountRepository.signOut(credentials.token)
    return false
}
