package com.autoaccounting.feature.ledger

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import com.autoaccounting.ui.components.Button
import com.autoaccounting.ui.components.EmptyStatePanel
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.autoaccounting.ui.components.HomeReturnButton
import androidx.compose.material3.MaterialTheme
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.OutlinedTextField
import com.autoaccounting.ui.components.SlidePageTransition
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import com.autoaccounting.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autoaccounting.R
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.EntryOrigin
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.LedgerEntryInput
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.ui.visual.CategoryArtwork
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlinx.coroutines.launch

@Composable
@Suppress("LongParameterList", "LongMethod")
fun LedgerScreen(
    entries: List<LedgerUiEntry>,
    entryListState: LazyListState = rememberLazyListState(),
    deletedEntries: List<LedgerUiEntry> = emptyList(),
    categories: List<CategoryEntity> = emptyList(),
    fundingAccounts: List<FundingAccountEntity> = emptyList(),
    ledgerBooks: List<LedgerBookUiModel> = emptyList(),
    activeLedgerName: String = "本地账本",
    onUpdateEntry: suspend (String, LedgerEntryInput) -> Unit = { _, _ -> },
    onDeleteEntry: suspend (String) -> Unit = {},
    onRestoreEntry: suspend (String) -> Unit = {},
    onPermanentlyDeleteEntry: suspend (String) -> Unit = {},
    onPurgeExpiredEntries: suspend () -> Unit = {},
    onCreateLedger: suspend (String) -> Unit = {},
    onSelectLedger: suspend (String) -> Unit = {},
    onDeleteLedger: suspend (String) -> LedgerBookDeleteResult = {
        LedgerBookDeleteResult.Deleted
    },
    onCreateFundingAccount: suspend (String, PaymentSource?) -> Unit = { _, _ -> },
    onUpdateFundingAccount: suspend (Long, String, PaymentSource?) -> Unit = { _, _, _ -> },
    onDeleteFundingAccount: suspend (Long) -> FundingAccountDeleteResult = {
        FundingAccountDeleteResult.Deleted
    },
    defaultFundingAccountSyncId: String? = null,
    onSetDefaultFundingAccount: suspend (Long?) -> Unit = {},
    onNavigateHome: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var view by remember { mutableStateOf(LedgerView.LIST) }
    var selectedEntryId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val selectedEntry = remember(entries, selectedEntryId) {
        entries.firstOrNull { it.id == selectedEntryId }
    }
    val page = remember(view, selectedEntryId) { LedgerPage(view, selectedEntryId) }

    BackHandler(
        enabled = view == LedgerView.DELETED ||
            view == LedgerView.LEDGER_BOOKS ||
            view == LedgerView.FUNDING_ACCOUNTS
    ) {
        view = LedgerView.LIST
        selectedEntryId = null
    }

    LaunchedEffect(view) {
        if (view == LedgerView.DELETED) {
            onPurgeExpiredEntries()
        }
    }

    LaunchedEffect(view, selectedEntryId, selectedEntry) {
        if (
            view == LedgerView.EDIT &&
            selectedEntryId != null &&
            selectedEntry == null
        ) {
            view = LedgerView.LIST
            selectedEntryId = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SlidePageTransition(
            targetState = page,
            modifier = Modifier.fillMaxSize(),
            animateOutgoingContent = false
        ) { targetPage ->
            val targetEntry = remember(entries, targetPage.selectedEntryId) {
                entries.firstOrNull { it.id == targetPage.selectedEntryId }
            }
            when (targetPage.view) {
                LedgerView.LIST -> LedgerList(
                    entries = entries,
                    entryListState = entryListState,
                    activeLedgerName = activeLedgerName,
                    onEntryClick = {
                        selectedEntryId = it
                        view = LedgerView.EDIT
                    },
                    onLedgerBooksClick = { view = LedgerView.LEDGER_BOOKS },
                    onFundingAccountsClick = { view = LedgerView.FUNDING_ACCOUNTS },
                    onRecentlyDeletedClick = { view = LedgerView.DELETED },
                    onNavigateHome = onNavigateHome
                )

                LedgerView.EDIT -> targetEntry?.let { entry ->
                    val initialFormState = remember(entry) { LedgerEntryFormState.from(entry) }
                    LedgerEntryForm(
                        title = "编辑账目",
                        initial = initialFormState,
                        categories = categories,
                        fundingAccounts = fundingAccounts,
                        config = LedgerEntryFormConfig(
                            flowDirections = listOf(FlowDirection.OUTFLOW, FlowDirection.INFLOW),
                            allowCreateFundingAccount = false,
                            saveLabel = "保存修改",
                            onExit = {
                                selectedEntryId = null
                                view = LedgerView.LIST
                            },
                            onSave = { input ->
                                onUpdateEntry(entry.id, input)
                                selectedEntryId = null
                                view = LedgerView.LIST
                            },
                            onDelete = {
                                scope.launch {
                                    runCatching { onDeleteEntry(entry.id) }
                                        .onSuccess {
                                            selectedEntryId = null
                                            view = LedgerView.LIST
                                            val result = snackbarHostState.showSnackbar(
                                                message = "已移入最近删除",
                                                actionLabel = "撤销"
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                onRestoreEntry(entry.id)
                                            }
                                        }
                                        .onFailure { snackbarHostState.showSnackbar(it.userMessage()) }
                                }
                            },
                            snackbarHostState = snackbarHostState
                        )
                    )
                }

                LedgerView.DELETED -> RecentlyDeletedScreen(
                    entries = deletedEntries,
                    onBack = { view = LedgerView.LIST },
                    onRestore = { id ->
                        scope.launch {
                            runCatching { onRestoreEntry(id) }
                                .onFailure { snackbarHostState.showSnackbar(it.userMessage()) }
                        }
                    },
                    onPermanentlyDelete = { id ->
                        scope.launch {
                            runCatching { onPermanentlyDeleteEntry(id) }
                                .onFailure { snackbarHostState.showSnackbar(it.userMessage()) }
                        }
                    }
                )

                LedgerView.LEDGER_BOOKS -> LedgerBookManagementScreen(
                    ledgerBooks = ledgerBooks,
                    snackbarHostState = snackbarHostState,
                    actions = LedgerBookManagementActions(
                        onBack = { view = LedgerView.LIST },
                        onCreateLedger = onCreateLedger,
                        onSelectLedger = onSelectLedger,
                        onDeleteLedger = onDeleteLedger
                    )
                )

                LedgerView.FUNDING_ACCOUNTS -> FundingAccountManagementScreen(
                    fundingAccounts = fundingAccounts,
                    defaultFundingAccountSyncId = defaultFundingAccountSyncId,
                    snackbarHostState = snackbarHostState,
                    actions = FundingAccountManagementActions(
                        onBack = { view = LedgerView.LIST },
                        onCreateFundingAccount = onCreateFundingAccount,
                        onUpdateFundingAccount = onUpdateFundingAccount,
                        onDeleteFundingAccount = onDeleteFundingAccount,
                        onSetDefaultFundingAccount = onSetDefaultFundingAccount
                    )
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
