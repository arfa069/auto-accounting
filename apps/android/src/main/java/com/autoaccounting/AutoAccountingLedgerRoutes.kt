package com.autoaccounting

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.autoaccounting.api.LedgerSyncConflictChoiceContract
import com.autoaccounting.data.local.FundingAccountDeleteResult as DataFundingAccountDeleteResult
import com.autoaccounting.data.local.LedgerBookDeleteResult as DataLedgerBookDeleteResult
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.ledger.FundingAccountDeleteResult as UiFundingAccountDeleteResult
import com.autoaccounting.feature.ledger.LedgerBookDeleteResult as UiLedgerBookDeleteResult
import com.autoaccounting.feature.ledger.LedgerScreen
import com.autoaccounting.feature.ledger.ReportsScreen
import com.autoaccounting.feature.review.ReviewQueueScreen
import com.autoaccounting.feature.sync.LedgerSyncInitialMode
import com.autoaccounting.feature.sync.LedgerSyncOperationResult
import com.autoaccounting.feature.sync.LedgerSyncPreview
import com.autoaccounting.feature.sync.LedgerSyncScheduler

@Composable
internal fun AutoAccountingReviewRoute(
    context: AutoAccountingRouteContext,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    val runtime = context.runtime
    val presentation = context.presentation
    val bindings = context.bindings
    ReviewQueueScreen(
        state = runtime.reviewState,
        targetLedgerName = presentation.activeLedgerName,
        categories = runtime.ledgerState.categories,
        fundingAccounts = runtime.ledgerState.fundingAccounts,
        onStateChange = context.actions::persistReviewState,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding),
        onCategorizationRuleRequested = { rule ->
            context.actions.persistCategorizationRules(runtime.categorizationRules.upsert(rule))
        },
        accountSession = runtime.accountSession,
        aiSettings = presentation.effectiveAiSettings,
        aiCategorizationGateway = context.dependencies.aiCategorizationGateway,
        onOpenBillImport = { runtime.manualBillImportRequestId += 1 },
        openPendingEntryId = bindings.pendingEntryNavigationId,
        openPendingEntryRequestId = bindings.reviewNavigationRequest,
        onNavigateHome = onNavigateHome
    )
}

@Composable
internal fun AutoAccountingLedgerRoute(
    context: AutoAccountingRouteContext,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    val runtime = context.runtime
    val presentation = context.presentation
    val dependencies = context.dependencies
    val appState = context.appState
    LedgerScreen(
        entries = presentation.ledgerEntries,
        entryListState = appState.ledgerEntryListState,
        deletedEntries = presentation.deletedLedgerEntries,
        categories = runtime.ledgerState.categories,
        fundingAccounts = runtime.ledgerState.fundingAccounts,
        ledgerBooks = presentation.ledgerBookUiModels,
        activeLedgerName = presentation.activeLedgerName,
        onUpdateEntry = { id, input -> dependencies.local.ledgerRepository.updateLedgerEntry(id, input) },
        onDeleteEntry = { id -> dependencies.local.ledgerRepository.moveLedgerEntryToDeleted(id) },
        onRestoreEntry = { id -> dependencies.local.ledgerRepository.restoreDeletedLedgerEntry(id) },
        onPermanentlyDeleteEntry = { id -> dependencies.local.ledgerRepository.permanentlyDeleteLedgerEntry(id) },
        onPurgeExpiredEntries = { dependencies.local.ledgerRepository.purgeExpiredDeletedLedgerEntries() },
        onCreateLedger = { name -> dependencies.local.ledgerRepository.createLedgerBook(name) },
        onSelectLedger = { id -> dependencies.local.ledgerRepository.selectLedgerBook(id) },
        onDeleteLedger = { id ->
            when (val result = dependencies.local.ledgerRepository.deleteLedgerBook(id)) {
                DataLedgerBookDeleteResult.Deleted -> UiLedgerBookDeleteResult.Deleted
                DataLedgerBookDeleteResult.LastLedgerBook -> UiLedgerBookDeleteResult.LastLedger
                is DataLedgerBookDeleteResult.NotEmpty -> UiLedgerBookDeleteResult.NotEmpty(
                    activeEntryCount = result.activeEntryCount,
                    deletedEntryCount = result.deletedEntryCount
                )
                DataLedgerBookDeleteResult.NotFound -> error("账本不存在")
            }
        },
        onCreateFundingAccount = { label, paymentSource ->
            dependencies.local.ledgerRepository.createFundingAccount(label, paymentSource)
        },
        onUpdateFundingAccount = { id, label, paymentSource ->
            dependencies.local.ledgerRepository.updateFundingAccount(id, label, paymentSource)
        },
        onDeleteFundingAccount = { id ->
            when (val result = dependencies.local.ledgerRepository.deleteFundingAccount(id)) {
                DataFundingAccountDeleteResult.Deleted -> UiFundingAccountDeleteResult.Deleted
                is DataFundingAccountDeleteResult.Referenced -> UiFundingAccountDeleteResult.Referenced(
                    activeLedgerEntryCount = result.activeLedgerEntryCount,
                    deletedLedgerEntryCount = result.deletedLedgerEntryCount,
                    pendingEntryCount = result.pendingEntryCount,
                    ignoredEntryCount = result.ignoredEntryCount
                )
                DataFundingAccountDeleteResult.NotFound -> error("资金账户不存在")
            }
        },
        onNavigateHome = onNavigateHome,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

@Composable
internal fun AutoAccountingReportsRoute(
    context: AutoAccountingRouteContext,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    ReportsScreen(
        entries = context.presentation.ledgerEntries,
        reportUiModel = context.presentation.reportUiModel,
        categoryRankingListState = context.appState.reportCategoryRankingListState,
        onNavigateHome = onNavigateHome,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

internal suspend fun AutoAccountingRouteContext.previewLedgerSync(): LedgerSyncOperationResult<LedgerSyncPreview> {
    val signedIn = runtime.accountSession as? AccountSession.SignedIn
    return if (signedIn == null) {
        LedgerSyncOperationResult.Failure(null, "请先登录账户", false)
    } else {
        dependencies.sync.coordinator.preview(signedIn.token)
    }
}

internal suspend fun AutoAccountingRouteContext.enableLedgerSync(
    mode: LedgerSyncInitialMode
): LedgerSyncOperationResult<Unit> {
    val signedIn = runtime.accountSession as? AccountSession.SignedIn
    return if (signedIn == null) {
        LedgerSyncOperationResult.Failure(null, "请先登录账户", false)
    } else {
        dependencies.sync.coordinator.enable(signedIn.token, mode).also { result ->
            if (result is LedgerSyncOperationResult.Success) {
                LedgerSyncScheduler.ensurePeriodic(dependencies.context)
            }
        }
    }
}

internal suspend fun AutoAccountingRouteContext.syncLedgerNow(): LedgerSyncOperationResult<Unit> {
    val signedIn = runtime.accountSession as? AccountSession.SignedIn
    return if (signedIn == null) {
        LedgerSyncOperationResult.Failure(null, "请先登录账户", false)
    } else {
        dependencies.sync.coordinator.synchronize(signedIn.token)
    }
}

internal suspend fun AutoAccountingRouteContext.disableLedgerSync() {
    LedgerSyncScheduler.cancel(dependencies.context)
    dependencies.sync.localStore.disableAndUnbind()
}

internal suspend fun AutoAccountingRouteContext.resolveLedgerSyncConflict(
    conflictId: String,
    version: Long,
    choice: LedgerSyncConflictChoiceContract
): LedgerSyncOperationResult<Unit> {
    val signedIn = runtime.accountSession as? AccountSession.SignedIn
    return if (signedIn == null) {
        LedgerSyncOperationResult.Failure(null, "请先登录账户", false)
    } else {
        dependencies.sync.coordinator.resolveConflict(signedIn.token, conflictId, version, choice)
    }
}
