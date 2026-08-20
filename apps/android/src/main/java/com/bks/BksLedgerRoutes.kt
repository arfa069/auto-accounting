package com.bks

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bks.api.LedgerSyncConflictChoiceContract
import com.bks.data.local.FundingAccountDeleteResult as DataFundingAccountDeleteResult
import com.bks.data.local.LedgerBookDeleteResult as DataLedgerBookDeleteResult
import com.bks.feature.account.AccountSession
import com.bks.feature.account.cloudConfigAccountKey
import com.bks.feature.ledger.FundingAccountDeleteResult as UiFundingAccountDeleteResult
import com.bks.feature.ledger.LedgerBookDeleteResult as UiLedgerBookDeleteResult
import com.bks.feature.ledger.LedgerScreen
import com.bks.feature.ledger.ReportsScreen
import com.bks.feature.review.ReviewQueueScreen
import com.bks.feature.sync.LedgerSyncInitialMode
import com.bks.feature.sync.LedgerSyncOperationResult
import com.bks.feature.sync.LedgerSyncPreview
import com.bks.feature.sync.LedgerSyncScheduler

@Composable
internal fun BksReviewRoute(
    context: BksRouteContext,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    val runtime = context.runtime
    val presentation = context.presentation
    ReviewQueueScreen(
        state = runtime.reviewState,
        targetLedgerName = presentation.activeLedgerName,
        categories = runtime.ledgerState.categories,
        fundingAccounts = runtime.ledgerState.fundingAccounts,
        defaultFundingAccountSyncId = runtime.ledgerState.defaultFundingAccountSyncId,
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
        onNavigateHome = onNavigateHome
    )
}

@Composable
@Suppress("CyclomaticComplexMethod")
internal fun BksLedgerRoute(
    context: BksRouteContext,
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
        defaultFundingAccountSyncId = runtime.ledgerState.defaultFundingAccountSyncId,
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
        onSetDefaultFundingAccount = { id ->
            dependencies.local.ledgerRepository.setDefaultFundingAccount(id)
            val signedIn = runtime.accountSession as? AccountSession.SignedIn
            if (
                signedIn != null &&
                runtime.accountRuntimeState.cloudWritesAllowed &&
                runtime.accountDeletionState.cloudWritesAllowed
            ) {
                val syncId = id?.let { accountId ->
                    runtime.ledgerState.fundingAccounts.firstOrNull { it.id == accountId }?.syncId
                }
                val accountKey = signedIn.cloudConfigAccountKey()
                if (accountKey != null) {
                    dependencies.local.preferencesRepository.cacheDefaultFundingAccount(
                        accountKey = accountKey,
                        syncId = syncId,
                        pendingUpload = true
                    )
                }
                when (val result = dependencies.cloudAiSettingsGateway.writeDefaultFundingAccount(signedIn.token, syncId)) {
                    is com.bks.feature.categorization.CloudAiSettingsGatewayResult.Success -> {
                        if (accountKey != null && result.supportsDefaultFundingAccount) {
                            dependencies.local.preferencesRepository.cacheDefaultFundingAccount(
                                accountKey = accountKey,
                                syncId = result.defaultFundingAccountSyncId,
                                pendingUpload = false
                            )
                        }
                    }
                    is com.bks.feature.categorization.CloudAiSettingsGatewayResult.Failure ->
                        context.appState.snackbarHostState.showSnackbar("默认账户已保存到本机，云端同步失败")
                }
            }
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
internal fun BksReportsRoute(
    context: BksRouteContext,
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

internal suspend fun BksRouteContext.previewLedgerSync(): LedgerSyncOperationResult<LedgerSyncPreview> {
    val signedIn = runtime.accountSession as? AccountSession.SignedIn
    return if (signedIn == null) {
        LedgerSyncOperationResult.Failure(null, "请先登录账户", false)
    } else {
        dependencies.sync.coordinator.preview(signedIn.token)
    }
}

internal suspend fun BksRouteContext.enableLedgerSync(
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

internal suspend fun BksRouteContext.syncLedgerNow(): LedgerSyncOperationResult<Unit> {
    val signedIn = runtime.accountSession as? AccountSession.SignedIn
    return if (signedIn == null) {
        LedgerSyncOperationResult.Failure(null, "请先登录账户", false)
    } else {
        dependencies.sync.coordinator.synchronize(signedIn.token)
    }
}

internal suspend fun BksRouteContext.disableLedgerSync() {
    LedgerSyncScheduler.cancel(dependencies.context)
    dependencies.sync.localStore.disableAndUnbind()
}

internal suspend fun BksRouteContext.resolveLedgerSyncConflict(
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
