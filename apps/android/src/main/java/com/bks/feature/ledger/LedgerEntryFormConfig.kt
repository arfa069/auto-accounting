package com.bks.feature.ledger

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bks.data.local.FlowDirection
import com.bks.data.local.LedgerEntryInput

internal data class LedgerEntryFormConfig(
    val flowDirections: List<FlowDirection>,
    val allowCreateFundingAccount: Boolean,
    val saveLabel: String,
    val onExit: () -> Unit,
    val onSave: suspend (LedgerEntryInput) -> Unit,
    val onDelete: (() -> Unit)?,
    val snackbarHostState: SnackbarHostState,
    val showDefaultFundingAccountHint: Boolean = false,
    val leadingContent: @Composable (
        LedgerEntryFormState,
        (LedgerEntryFormState) -> Unit
    ) -> Unit = { _, _ -> }
)
