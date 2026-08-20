package com.bks.feature.sync

import com.bks.data.local.AccountSyncConflictEntity

data class LedgerSyncUiState(
    val signedIn: Boolean = false,
    val enabled: Boolean = false,
    val profileKey: String? = null,
    val lastSuccessAtMillis: Long? = null,
    val lastError: String? = null,
    val pendingCount: Int = 0,
    val conflicts: List<AccountSyncConflictEntity> = emptyList(),
    val insecureHttpTestMode: Boolean = false
)
