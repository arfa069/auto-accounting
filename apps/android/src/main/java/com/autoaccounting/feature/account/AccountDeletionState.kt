package com.autoaccounting.feature.account

const val ACCOUNT_DELETION_COOLING_OFF_MILLIS: Long = 7 * 24 * 60 * 60 * 1_000L

data class AccountDeletionUiState(
    val requestedAtEpochMillis: Long? = null,
    val finalDeletionAtEpochMillis: Long? = null
) {
    val isPending: Boolean
        get() = requestedAtEpochMillis != null && finalDeletionAtEpochMillis != null

    val cloudWritesAllowed: Boolean
        get() = !isPending
}

sealed interface AccountDeletionUiAction {
    data class RequestDeletion(val nowEpochMillis: Long) : AccountDeletionUiAction
    data object CancelDeletion : AccountDeletionUiAction
}

fun reduceAccountDeletionState(
    state: AccountDeletionUiState,
    action: AccountDeletionUiAction
): AccountDeletionUiState = when (action) {
    is AccountDeletionUiAction.RequestDeletion -> state.copy(
        requestedAtEpochMillis = action.nowEpochMillis,
        finalDeletionAtEpochMillis = action.nowEpochMillis + ACCOUNT_DELETION_COOLING_OFF_MILLIS
    )
    AccountDeletionUiAction.CancelDeletion -> AccountDeletionUiState()
}
