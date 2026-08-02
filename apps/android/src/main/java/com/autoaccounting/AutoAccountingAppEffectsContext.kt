package com.autoaccounting

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.autoaccounting.feature.account.AccountRuntimeState
import com.autoaccounting.feature.account.AccountRuntimeStatus
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.account.AccountSessionRestoreResult
import com.autoaccounting.feature.account.AccountSessionVerificationDecision
import com.autoaccounting.feature.account.persistRefreshedAccountSession
import com.autoaccounting.feature.account.resolveAccountSessionVerification
import com.autoaccounting.feature.account.toCredentials
import com.autoaccounting.feature.categorization.AiCategorizationSettings
import com.autoaccounting.feature.categorization.CloudAiSettingsGatewayResult
import com.autoaccounting.feature.diagnostics.DiagnosticComponent
import com.autoaccounting.feature.diagnostics.DiagnosticEvent
import com.autoaccounting.feature.diagnostics.DiagnosticEventMetadata
import com.autoaccounting.feature.diagnostics.DiagnosticLevel
import com.autoaccounting.feature.diagnostics.DiagnosticSource
import com.autoaccounting.feature.monitoring.ContinuousMonitoringAction
import com.autoaccounting.feature.review.ReviewQueueState
import com.autoaccounting.feature.sync.LedgerSyncOperationResult
import com.autoaccounting.feature.sync.LedgerSyncScheduler
import com.autoaccounting.feature.sync.LedgerSyncUiState
import com.autoaccounting.ui.AutoAccountingAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AutoAccountingAppEffectsContext(
    val dependencies: AutoAccountingAppDependencies,
    val bindings: AutoAccountingAppBindings,
    val runtime: AutoAccountingAppRuntimeState,
    val actions: AutoAccountingAppActions,
    val appState: AutoAccountingAppState,
    val lifecycleOwner: LifecycleOwner,
    val presentation: AutoAccountingAppPresentation
)

@Composable
internal fun AutoAccountingAppEffects(context: AutoAccountingAppEffectsContext) {
    AutoAccountingSyncStateEffects(context)
    AutoAccountingSyncLifecycleEffects(context)
    AutoAccountingRestoreEffect(context)
    AutoAccountingVerifyEffect(context)
    AutoAccountingNavigationEffects(context)
    AutoAccountingLocalPersistenceEffects(context)
    AutoAccountingCloudSettingsEffects(context)
}

@Composable
private fun AutoAccountingSyncStateEffects(context: AutoAccountingAppEffectsContext) {
    val dependencies = context.dependencies
    val runtime = context.runtime
    val appContext = dependencies.context
    DisposableEffect(
        dependencies.local.database,
        appContext,
        runtime.ledgerSyncUiState.enabled,
        runtime.accountSession,
        runtime.accountRuntimeState.status
    ) {
        val observer = object : androidx.room.InvalidationTracker.Observer(
            "categories",
            "funding_accounts",
            "ledger_books",
            "ledger_entries",
            "categorization_rules"
        ) {
            override fun onInvalidated(tables: Set<String>) {
                if (
                    runtime.ledgerSyncUiState.enabled &&
                    runtime.accountSession is AccountSession.SignedIn &&
                    runtime.accountRuntimeState.status == AccountRuntimeStatus.Verified
                ) {
                    LedgerSyncScheduler.enqueueNow(appContext)
                }
            }
        }
        dependencies.local.database.invalidationTracker.addObserver(observer)
        onDispose { dependencies.local.database.invalidationTracker.removeObserver(observer) }
    }

    LaunchedEffect(dependencies.sync.localStore, runtime.accountSession) {
        combine(
            dependencies.sync.localStore.state,
            dependencies.sync.localStore.outboxCount,
            dependencies.sync.localStore.conflicts
        ) { state, outboxCount, conflicts ->
            LedgerSyncUiState(
                signedIn = runtime.accountSession is AccountSession.SignedIn,
                enabled = state?.enabled == true,
                profileKey = state?.profileKey,
                lastSuccessAtMillis = state?.lastSuccessAtMillis,
                lastError = state?.lastError,
                pendingCount = outboxCount,
                conflicts = conflicts,
                insecureHttpTestMode = dependencies.sync.repository.insecureHttpTestMode
            )
        }.collect { runtime.ledgerSyncUiState = it }
    }
}

@Composable
private fun AutoAccountingSyncLifecycleEffects(context: AutoAccountingAppEffectsContext) {
    val dependencies = context.dependencies
    val runtime = context.runtime
    val appContext = dependencies.context
    LaunchedEffect(runtime.accountSession, runtime.accountRuntimeState.status, runtime.ledgerSyncUiState.enabled) {
        val signedIn = runtime.accountSession as? AccountSession.SignedIn
        if (
            signedIn != null &&
            runtime.accountRuntimeState.status == AccountRuntimeStatus.Verified &&
            runtime.ledgerSyncUiState.enabled
        ) {
            when (val preview = dependencies.sync.coordinator.preview(signedIn.token)) {
                is LedgerSyncOperationResult.Success -> {
                    if (
                        runtime.ledgerSyncUiState.profileKey != null &&
                        runtime.ledgerSyncUiState.profileKey != preview.value.profileKey
                    ) {
                        LedgerSyncScheduler.cancel(appContext)
                        runtime.showLedgerSyncAccountSwitch = true
                    } else {
                        LedgerSyncScheduler.ensurePeriodic(appContext)
                        LedgerSyncScheduler.enqueueNow(appContext)
                    }
                }
                is LedgerSyncOperationResult.Failure -> Unit
            }
        }
    }

    DisposableEffect(
        context.lifecycleOwner,
        runtime.accountSession,
        runtime.accountRuntimeState.status,
        runtime.ledgerSyncUiState.enabled
    ) {
        val observer = LifecycleEventObserver { _, event ->
            if (
                shouldEnqueueLedgerSync(
                    event,
                    runtime.accountSession,
                    runtime.accountRuntimeState,
                    runtime.ledgerSyncUiState
                )
            ) {
                LedgerSyncScheduler.enqueueNow(appContext)
            }
        }
        context.lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { context.lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun AutoAccountingRestoreEffect(context: AutoAccountingAppEffectsContext) {
    val dependencies = context.dependencies
    val runtime = context.runtime
    LaunchedEffect(dependencies.account.secureAccountSessionStore, dependencies.account.localModeSessionStore) {
        when (
            val restored = withContext(Dispatchers.IO) {
                dependencies.account.secureAccountSessionStore.restore()
            }
        ) {
            is AccountSessionRestoreResult.Restored -> context.actions.restoreAccountSession(restored.credentials)
            AccountSessionRestoreResult.Corrupted -> {
                withContext(Dispatchers.IO) {
                    dependencies.account.localModeSessionStore.confirmLocalMode()
                }
                runtime.accountSession = AccountSession.LocalMode
            }
            AccountSessionRestoreResult.Empty -> {
                runtime.accountSession = withContext(Dispatchers.IO) {
                    dependencies.account.localModeSessionStore.restoreSession()
                }
            }
        }
        runtime.isRestoringAccountSession = false
    }
}

@Composable
private fun AutoAccountingVerifyEffect(context: AutoAccountingAppEffectsContext) {
    val dependencies = context.dependencies
    val runtime = context.runtime
    val appState = context.appState
    LaunchedEffect(runtime.accountSession, runtime.accountRuntimeState.status) {
        val signedIn = runtime.accountSession as? AccountSession.SignedIn
        if (signedIn == null || runtime.accountRuntimeState.status != AccountRuntimeStatus.Validating) {
            return@LaunchedEffect
        }
        when (
            val decision = resolveAccountSessionVerification(
                dependencies.account.accountRepository.verifySession(
                    signedIn.toCredentials(runtime.accountDeletionState)
                )
            )
        ) {
            is AccountSessionVerificationDecision.Verified -> {
                val persisted = persistRefreshedAccountSession(
                    credentials = decision.credentials,
                    persistSession = context.actions::persistAccountSession,
                    onSessionVerified = context.actions::applyVerifiedCredentials
                )
                if (!persisted) {
                    runtime.accountRuntimeState = AccountRuntimeState(AccountRuntimeStatus.OfflineUnverified)
                    appState.snackbarHostState.showSnackbar("账号已验证，但最新资料未能保存到本机")
                }
            }
            AccountSessionVerificationDecision.ClearInvalidSession -> {
                context.actions.moveAccountToLocalMode()
                appState.snackbarHostState.showSnackbar("登录状态已失效，已切换到本地模式")
            }
            AccountSessionVerificationDecision.KeepOfflineSession ->
                runtime.accountRuntimeState = AccountRuntimeState(AccountRuntimeStatus.OfflineUnverified)
        }
    }
}

@Composable
private fun AutoAccountingNavigationEffects(context: AutoAccountingAppEffectsContext) {
    val runtime = context.runtime
    val bindings = context.bindings
    val appState = context.appState
    LaunchedEffect(bindings.reviewNavigationRequest) {
        if (bindings.reviewNavigationRequest > 0) {
            appState.selectedTab.value = AppTab.Review
        }
    }
    LaunchedEffect(
        bindings.permissionStateLoaded,
        context.presentation.continuousMonitoringPermissionHealth,
        runtime.continuousMonitoringState.enabled
    ) {
        if (!bindings.permissionStateLoaded || !runtime.continuousMonitoringState.enabled) return@LaunchedEffect
        val refreshedState = com.autoaccounting.feature.monitoring.reduceContinuousMonitoringState(
            runtime.continuousMonitoringState,
            ContinuousMonitoringAction.RefreshPermissionHealth(
                context.presentation.continuousMonitoringPermissionHealth
            )
        )
        if (refreshedState != runtime.continuousMonitoringState) {
            runtime.continuousMonitoringState = refreshedState
            context.dependencies.diagnosticLogs.record(
                DiagnosticEvent(
                    metadata = DiagnosticEventMetadata(
                        level = if (refreshedState.blockReason == null) DiagnosticLevel.Info else DiagnosticLevel.Warning,
                        component = DiagnosticComponent.Monitoring,
                        event = "automatic_bookkeeping_permission_health",
                        source = DiagnosticSource.System,
                        outcome = if (refreshedState.blockReason == null) "healthy" else "blocked",
                        reason = refreshedState.blockReason?.name ?: "permissions_healthy"
                    )
                )
            )
        }
    }
}

@Composable
private fun AutoAccountingLocalPersistenceEffects(context: AutoAccountingAppEffectsContext) {
    val dependencies = context.dependencies
    val runtime = context.runtime
    LaunchedEffect(dependencies.local.ledgerRepository) {
        dependencies.local.ledgerRepository.ensureDefaultLedgerBook()
        dependencies.local.ledgerRepository.seedSystemCategories()
        dependencies.local.ledgerRepository.purgeExpiredDeletedLedgerEntries()
    }
    LaunchedEffect(dependencies.local.reviewQueuePersistence) {
        dependencies.local.reviewQueuePersistence.observeState().collect { persistedState ->
            runtime.reviewState = persistedState.copy(
                confirmedEntries = runtime.reviewState.confirmedEntries,
                lastAction = runtime.reviewState.lastAction,
                undoEventSequence = runtime.reviewState.undoEventSequence
            )
        }
    }
    LaunchedEffect(dependencies.local.ledgerRepository) {
        dependencies.local.ledgerRepository.state.collect { state -> runtime.ledgerState = state }
    }
    LaunchedEffect(dependencies.local.preferencesRepository) {
        dependencies.local.preferencesRepository.categorizationRules.collect {
            runtime.categorizationRules = it
        }
    }
    LaunchedEffect(dependencies.local.preferencesRepository, runtime.accountSession) {
        dependencies.local.preferencesRepository.userPreferences.collect { preferences ->
            if (runtime.accountSession !is AccountSession.SignedIn) {
                runtime.aiSettings = preferences.aiSettings
            }
            runtime.continuousMonitoringState = preferences.continuousMonitoringState
        }
    }
}

@Composable
private fun AutoAccountingCloudSettingsEffects(context: AutoAccountingAppEffectsContext) {
    val dependencies = context.dependencies
    val runtime = context.runtime
    LaunchedEffect(
        runtime.accountSession,
        runtime.accountRuntimeState.status,
        runtime.accountDeletionState.isPending,
        dependencies.cloudAiSettingsGateway
    ) {
        val signedIn = runtime.accountSession as? AccountSession.SignedIn
        if (
            signedIn == null ||
            !runtime.accountRuntimeState.cloudWritesAllowed ||
            !runtime.accountDeletionState.cloudWritesAllowed
        ) {
            runtime.aiSettings = AiCategorizationSettings()
            runtime.cloudAiSettingsLoadedToken = null
            runtime.aiSettingsSyncInFlight = false
            return@LaunchedEffect
        }
        runtime.aiSettings = AiCategorizationSettings()
        runtime.cloudAiSettingsLoadedToken = null
        runtime.aiSettingsSyncInFlight = true
        try {
            when (val result = dependencies.cloudAiSettingsGateway.read(signedIn.token)) {
                is CloudAiSettingsGatewayResult.Success -> {
                    runtime.aiSettings = result.settings
                    runtime.cloudAiSettingsLoadedToken = signedIn.token
                    dependencies.local.preferencesRepository.updateAiSettings(result.settings)
                }
                is CloudAiSettingsGatewayResult.Failure -> Unit
            }
        } finally {
            runtime.aiSettingsSyncInFlight = false
        }
    }
}

private fun shouldEnqueueLedgerSync(
    event: Lifecycle.Event,
    accountSession: AccountSession?,
    accountRuntimeState: AccountRuntimeState,
    ledgerSyncUiState: LedgerSyncUiState
): Boolean {
    if (event != Lifecycle.Event.ON_RESUME) return false
    if (accountSession !is AccountSession.SignedIn) return false
    if (accountRuntimeState.status != AccountRuntimeStatus.Verified) return false
    return ledgerSyncUiState.enabled
}
