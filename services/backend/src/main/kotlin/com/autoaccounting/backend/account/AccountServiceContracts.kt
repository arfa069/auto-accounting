package com.autoaccounting.backend.account

import com.autoaccounting.api.AccountDeletionStatusContract

internal fun StoredAccount.deletionStatus(
    phone: String?,
    requestedAtMillis: Long
): AccountDeletionStatus = AccountDeletionStatus(
    accountId = accountId,
    phone = phone,
    requestedAtMillis = requestedAtMillis,
    finalDeletionAtMillis = requestedAtMillis + ACCOUNT_DELETION_COOLING_OFF_MILLIS
)

internal fun AccountDeletionStatus.toSessionContract(): AccountDeletionStatusContract =
    AccountDeletionStatusContract(
        pending = true,
        requestedAtMillis = requestedAtMillis,
        finalDeletionAtMillis = finalDeletionAtMillis
    )
