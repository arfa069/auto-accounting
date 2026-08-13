package com.autoaccounting.feature.ledger

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.PaymentSource



data class LedgerBookUiModel(
    val id: String,
    val name: String,
    val activeEntryCount: Int = 0,
    val deletedEntryCount: Int = 0,
    val isActive: Boolean = false
) {
    val totalEntryCount: Int
        get() = activeEntryCount + deletedEntryCount
}

sealed interface LedgerBookDeleteResult {
    data object Deleted : LedgerBookDeleteResult
    data object LastLedger : LedgerBookDeleteResult
    data class NotEmpty(
        val activeEntryCount: Int,
        val deletedEntryCount: Int
    ) : LedgerBookDeleteResult
}

sealed interface FundingAccountDeleteResult {
    data object Deleted : FundingAccountDeleteResult
    data class Referenced(
        val activeLedgerEntryCount: Int,
        val deletedLedgerEntryCount: Int,
        val pendingEntryCount: Int,
        val ignoredEntryCount: Int
    ) : FundingAccountDeleteResult
}

internal data class LedgerBookManagementActions(
    val onBack: () -> Unit,
    val onCreateLedger: suspend (String) -> Unit,
    val onSelectLedger: suspend (String) -> Unit,
    val onDeleteLedger: suspend (String) -> LedgerBookDeleteResult
)

internal data class FundingAccountManagementActions(
    val onBack: () -> Unit,
    val onCreateFundingAccount: suspend (String, PaymentSource?) -> Unit,
    val onUpdateFundingAccount: suspend (Long, String, PaymentSource?) -> Unit,
    val onSetDefaultFundingAccount: suspend (Long?) -> Unit,
    val onDeleteFundingAccount: suspend (Long) -> FundingAccountDeleteResult
)

@Composable
internal fun LedgerBookManagementScreen(
    ledgerBooks: List<LedgerBookUiModel>,
    snackbarHostState: SnackbarHostState,
    actions: LedgerBookManagementActions
) {
    LedgerBookManagementContent(
        ledgerBooks = ledgerBooks,
        snackbarHostState = snackbarHostState,
        actions = actions
    )
}

@Composable
internal fun FundingAccountManagementScreen(
    fundingAccounts: List<FundingAccountEntity>,
    defaultFundingAccountSyncId: String? = null,
    snackbarHostState: SnackbarHostState,
    actions: FundingAccountManagementActions
) {
    FundingAccountManagementContent(
        fundingAccounts = fundingAccounts,
        defaultFundingAccountSyncId = defaultFundingAccountSyncId,
        snackbarHostState = snackbarHostState,
        actions = actions
    )
}



internal object LedgerTestTags {
    const val ENTRY_LIST = "ledger-entry-list"
    const val SEARCH_FIELD = "ledger-search-field"
    const val FILTER_BUTTON = "ledger-filter-button"
    const val MORE_MENU = "ledger-more"
    const val MANAGE_LEDGERS = "ledger-manage-ledgers"
    const val MANAGE_FUNDING_ACCOUNTS = "ledger-manage-funding-accounts"
    const val RECENTLY_DELETED = "ledger-recently-deleted"
    const val ADD_LEDGER = "ledger-add"
    const val LEDGER_NAME = "ledger-name"
    const val CONFIRM_ADD_LEDGER = "ledger-confirm-add"
    const val CONFIRM_DELETE_LEDGER = "ledger-confirm-delete"
    const val ADD_FUNDING_ACCOUNT = "funding-account-add"
    const val FUNDING_ACCOUNT_LABEL = "funding-account-label"
    const val FUNDING_ACCOUNT_SOURCE = "funding-account-source"
    const val SAVE_FUNDING_ACCOUNT = "funding-account-save"
    const val CONFIRM_DELETE_FUNDING_ACCOUNT = "funding-account-confirm-delete"

    fun ledgerBook(id: String): String = "ledger-book-$id"
    fun selectLedger(id: String): String = "ledger-select-$id"
    fun deleteLedger(id: String): String = "ledger-delete-$id"
    fun fundingAccount(id: Long): String = "funding-account-$id"
    fun editFundingAccount(id: Long): String = "funding-account-edit-$id"
    fun deleteFundingAccount(id: Long): String = "funding-account-delete-$id"
}
