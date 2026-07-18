package com.autoaccounting.feature.account

data class AccountDeletionUiState(
    val requestedAtEpochMillis: Long? = null,
    val finalDeletionAtEpochMillis: Long? = null
) {
    val isPending: Boolean
        get() = requestedAtEpochMillis != null && finalDeletionAtEpochMillis != null

    val cloudWritesAllowed: Boolean
        get() = !isPending
}
