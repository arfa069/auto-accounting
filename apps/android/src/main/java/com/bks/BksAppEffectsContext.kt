package com.bks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.bks.feature.account.AccountRuntimeState
import com.bks.feature.account.AccountRuntimeStatus
import com.bks.feature.account.AccountSession
import com.bks.feature.account.AccountSessionRestoreResult
import com.bks.feature.account.AccountSessionVerificationDecision
import com.bks.feature.account.cloudConfigAccountKey
import com.bks.feature.account.persistRefreshedAccountSession
import com.bks.feature.account.resolveAccountSessionVerification
import com.bks.feature.account.toCredentials
import com.bks.feature.categorization.AiCategorizationSettings
import com.bks.feature.categorization.CloudAiSettingsGatewayResult
import com.bks.feature.diagnostics.DiagnosticComponent
import com.bks.feature.diagnostics.DiagnosticEvent
import com.bks.feature.diagnostics.DiagnosticEventMetadata
import com.bks.feature.diagnostics.DiagnosticLevel
import com.bks.feature.diagnostics.DiagnosticSource
import com.bks.feature.review.ReviewQueueState
import com.bks.feature.sync.LedgerSyncOperationResult
import com.bks.feature.sync.LedgerSyncScheduler
import com.bks.feature.sync.LedgerSyncUiState
import com.bks.ui.BksAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BksAppEffectsContext(
    val dependencies: BksAppDependencies,
    val bindings: BksAppBindings,
    val runtime: BksAppRuntimeState,
    val actions: BksAppActions,
    val appState: BksAppState,
    val lifecycleOwner: LifecycleOwner,
    val presentation: BksAppPresentation
)

@Composable
internal fun BksAppEffects(context: BksAppEffectsContext) {
    BksSyncStateEffects(context)
    BksSyncLifecycleEffects(context)
    BksRestoreEffect(context)
    BksVerifyEffect(context)
    BksLocalPersistenceEffects(context)
    BksCloudSettingsEffects(context)
}

@Composable
private fun BksSyncStateEffects(context: BksAppEffectsContext) {
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
private fun BksSyncLifecycleEffects(context: BksAppEffectsContext) {
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
private fun BksRestoreEffect(context: BksAppEffectsContext) {
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
private fun BksVerifyEffect(context: BksAppEffectsContext) {
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
private fun BksLocalPersistenceEffects(context: BksAppEffectsContext) {
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
            runtime.automaticBookkeepingEnabled = preferences.automaticBookkeepingEnabled
            if (runtime.accountSession !is AccountSession.SignedIn) {
                runtime.aiSettings = preferences.aiSettings
            }
        }
    }
}

@Composable
private fun BksCloudSettingsEffects(context: BksAppEffectsContext) {
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
            val accountKey = signedIn.cloudConfigAccountKey()
            val cached = accountKey?.let {
                dependencies.local.preferencesRepository.getCachedDefaultFundingAccount(it)
            }
            dependencies.local.preferencesRepository.setDefaultFundingAccountSyncId(cached?.syncId)
            val result = if (cached?.pendingUpload == true) {
                dependencies.cloudAiSettingsGateway.writeDefaultFundingAccount(signedIn.token, cached.syncId)
            } else {
                dependencies.cloudAiSettingsGateway.read(signedIn.token)
            }
            when (result) {
                is CloudAiSettingsGatewayResult.Success -> {
                    runtime.aiSettings = result.settings
                    runtime.cloudAiSettingsLoadedToken = signedIn.token
                    dependencies.local.preferencesRepository.updateAiSettings(result.settings)
                    if (accountKey != null && result.supportsDefaultFundingAccount) {
                        dependencies.local.preferencesRepository.cacheDefaultFundingAccount(
                            accountKey = accountKey,
                            syncId = result.defaultFundingAccountSyncId,
                            pendingUpload = false
                        )
                        dependencies.local.preferencesRepository.setDefaultFundingAccountSyncId(
                            result.defaultFundingAccountSyncId
                        )
                    }
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
