package com.autoaccounting

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.autoaccounting.data.local.DEFAULT_LEDGER_BOOK_ID
import com.autoaccounting.data.local.DEFAULT_LEDGER_BOOK_NAME
import com.autoaccounting.data.local.LedgerRepositoryState
import com.autoaccounting.feature.account.AccountCredentials
import com.autoaccounting.feature.account.AccountDeletionUiState
import com.autoaccounting.feature.account.AccountRuntimeState
import com.autoaccounting.feature.account.AccountRuntimeStatus
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.account.toSignedInSession
import com.autoaccounting.feature.categorization.AiCategorizationSettings
import com.autoaccounting.feature.categorization.CategorizationRule
import com.autoaccounting.feature.categorization.CloudAiSettingsGatewayResult
import com.autoaccounting.feature.diagnostics.DiagnosticComponent
import com.autoaccounting.feature.diagnostics.DiagnosticEvent
import com.autoaccounting.feature.diagnostics.DiagnosticEventMetadata
import com.autoaccounting.feature.diagnostics.DiagnosticLevel
import com.autoaccounting.feature.diagnostics.DiagnosticSource
import com.autoaccounting.feature.ledger.LedgerBookUiModel
import com.autoaccounting.feature.ledger.LedgerReportUiModel
import com.autoaccounting.feature.ledger.LedgerUiEntry
import com.autoaccounting.feature.ledger.buildLedgerReportUiModel
import com.autoaccounting.feature.ledger.toLedgerUiEntry
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.review.ReviewQueueState
import com.autoaccounting.feature.sync.LedgerSyncScheduler
import com.autoaccounting.feature.sync.LedgerSyncUiState
import com.autoaccounting.ui.AutoAccountingAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class AutoAccountingAppRuntimeState {
    var manualBillImportRequestId by mutableLongStateOf(0L)
    var accountSession by mutableStateOf<AccountSession?>(null)
    var isRestoringAccountSession by mutableStateOf(true)
    var accountEntryReturnSession by mutableStateOf<AccountSession?>(null)
    var accountDeletionState by mutableStateOf(AccountDeletionUiState())
    var accountRuntimeState by mutableStateOf(AccountRuntimeState(AccountRuntimeStatus.LocalMode))
    var continuousMonitoringState by mutableStateOf(ContinuousMonitoringState())
    var aiSettings by mutableStateOf(AiCategorizationSettings())
    var aiSettingsSyncInFlight by mutableStateOf(false)
    var cloudAiSettingsLoadedToken by mutableStateOf<String?>(null)
    var reviewState by mutableStateOf(ReviewQueueState())
    var reviewTransitionInFlight by mutableStateOf(false)
    var ledgerState by mutableStateOf(LedgerRepositoryState())
    var categorizationRules by mutableStateOf(emptyList<CategorizationRule>())
    var ledgerSyncUiState by mutableStateOf(LedgerSyncUiState())
    var showLedgerSyncAccountSwitch by mutableStateOf(false)
    var ledgerSyncAccountSwitchBusy by mutableStateOf(false)
}

@Composable
internal fun rememberAutoAccountingAppRuntimeState(): AutoAccountingAppRuntimeState = remember {
    AutoAccountingAppRuntimeState()
}

internal class AutoAccountingAppActions(
    private val dependencies: AutoAccountingAppDependencies,
    private val runtime: AutoAccountingAppRuntimeState,
    private val appState: AutoAccountingAppState,
    private val coroutineScope: CoroutineScope
) {
    fun moveAccountToLocalMode() {
        LedgerSyncScheduler.cancel(dependencies.context)
        dependencies.account.secureAccountSessionStore.clear()
        dependencies.account.wechatAvatarCache.clear()
        dependencies.account.localModeSessionStore.confirmLocalMode()
        runtime.accountSession = AccountSession.LocalMode
        runtime.accountDeletionState = AccountDeletionUiState()
        runtime.accountRuntimeState = AccountRuntimeState(AccountRuntimeStatus.LocalMode)
    }

    fun restoreAccountSession(credentials: AccountCredentials) {
        runtime.accountSession = credentials.toSignedInSession()
        runtime.accountDeletionState = credentials.deletionState
        runtime.accountRuntimeState = AccountRuntimeState(AccountRuntimeStatus.Validating)
    }

    fun applyVerifiedCredentials(credentials: AccountCredentials) {
        dependencies.account.wechatAvatarCache.prepareUrl(credentials.avatarUrl)
        runtime.accountSession = credentials.toSignedInSession()
        runtime.accountDeletionState = credentials.deletionState
        runtime.accountRuntimeState = AccountRuntimeState(
            if (credentials.deletionState.isPending) {
                AccountRuntimeStatus.DeletionCoolingOff
            } else {
                AccountRuntimeStatus.Verified
            }
        )
    }

    fun persistAccountSession(credentials: AccountCredentials): Boolean =
        dependencies.account.persistSessionOverride?.invoke(credentials)
            ?: dependencies.account.secureAccountSessionStore.save(credentials)

    fun persistReviewState(nextState: ReviewQueueState) {
        if (runtime.reviewTransitionInFlight) {
            coroutineScope.launch {
                appState.snackbarHostState.showSnackbar("上一项操作正在保存，请稍后重试")
            }
            return
        }
        val previousState = runtime.reviewState
        val previousConfirmedIds = previousState.confirmedEntries.mapTo(mutableSetOf()) { it.originPendingId }
        val addsConfirmation = nextState.confirmedEntries.any { it.originPendingId !in previousConfirmedIds }
        val targetLedgerBookId = runtime.ledgerState.activeLedgerBook?.id
        if (addsConfirmation && targetLedgerBookId == null) {
            coroutineScope.launch {
                appState.snackbarHostState.showSnackbar("当前账本尚未加载，请稍后重试")
            }
            return
        }
        runtime.reviewTransitionInFlight = true
        runtime.reviewState = nextState
        coroutineScope.launch {
            val failure = runCatching {
                dependencies.local.reviewQueuePersistence.persistTransition(
                    previous = previousState,
                    next = nextState,
                    targetLedgerBookId = targetLedgerBookId ?: DEFAULT_LEDGER_BOOK_ID
                )
            }.exceptionOrNull()
            if (failure != null) {
                val persistedState = runCatching {
                    dependencies.local.reviewQueuePersistence.observeState().first()
                }.getOrElse {
                    previousState.copy(confirmedEntries = emptyList(), lastAction = null)
                }
                runtime.reviewState = persistedState.copy(
                    undoEventSequence = maxOf(persistedState.undoEventSequence, previousState.undoEventSequence)
                )
            }
            runtime.reviewTransitionInFlight = false
            if (failure != null) {
                appState.snackbarHostState.showSnackbar("保存待确认操作失败，请重试")
            }
        }
    }

    fun persistCategorizationRules(nextRules: List<CategorizationRule>) {
        runtime.categorizationRules = nextRules
        coroutineScope.launch {
            dependencies.local.preferencesRepository.replaceCategorizationRules(nextRules)
        }
    }

    fun persistAiSettings(nextSettings: AiCategorizationSettings) {
        if (runtime.aiSettingsSyncInFlight) return
        val signedIn = runtime.accountSession as? AccountSession.SignedIn
        if (
            signedIn == null ||
            !runtime.accountRuntimeState.cloudWritesAllowed ||
            !runtime.accountDeletionState.cloudWritesAllowed
        ) {
            coroutineScope.launch {
                appState.snackbarHostState.showSnackbar(
                    if (signedIn == null) "登录后才能同步云端 AI 设置" else "账号当前不允许写入云端设置"
                )
            }
            return
        }
        runtime.aiSettingsSyncInFlight = true
        coroutineScope.launch {
            try {
                when (val result = dependencies.cloudAiSettingsGateway.write(signedIn.token, nextSettings)) {
                    is CloudAiSettingsGatewayResult.Success -> {
                        runtime.aiSettings = result.settings
                        runtime.cloudAiSettingsLoadedToken = signedIn.token
                        dependencies.local.preferencesRepository.updateAiSettings(result.settings)
                    }
                    is CloudAiSettingsGatewayResult.Failure ->
                        appState.snackbarHostState.showSnackbar(result.reason.toAiSettingsMessage())
                }
            } finally {
                runtime.aiSettingsSyncInFlight = false
            }
        }
    }

    fun persistContinuousMonitoringState(nextState: ContinuousMonitoringState) {
        val previousState = runtime.continuousMonitoringState
        runtime.continuousMonitoringState = nextState
        if (previousState.enabled != nextState.enabled) {
            dependencies.diagnosticLogs.record(
                DiagnosticEvent(
                    metadata = DiagnosticEventMetadata(
                        level = DiagnosticLevel.Info,
                        component = DiagnosticComponent.Monitoring,
                        event = "automatic_bookkeeping_toggle",
                        source = DiagnosticSource.System,
                        outcome = if (nextState.enabled) "enabled" else "disabled",
                        reason = if (nextState.enabled) "user_enabled" else "user_disabled"
                    )
                )
            )
        }
        coroutineScope.launch {
            dependencies.local.preferencesRepository.updateContinuousMonitoringState(nextState)
        }
    }

    fun clearLocalData() {
        dependencies.account.wechatAvatarCache.clear()
        runtime.reviewState = ReviewQueueState()
        runtime.categorizationRules = emptyList()
        runtime.aiSettings = AiCategorizationSettings()
        runtime.continuousMonitoringState = ContinuousMonitoringState()
        runtime.ledgerState = LedgerRepositoryState()
        coroutineScope.launch {
            try {
                LedgerSyncScheduler.cancel(dependencies.context)
                dependencies.local.ledgerRepository.clearLocalData()
            } finally {
                dependencies.diagnosticLogs.clear(keepEnabledPreference = false)
            }
        }
    }
}

internal data class AutoAccountingAppPresentation(
    val activeLedgerName: String,
    val effectiveAiSettings: AiCategorizationSettings,
    val ledgerEntries: List<LedgerUiEntry>,
    val deletedLedgerEntries: List<LedgerUiEntry>,
    val ledgerBookUiModels: List<LedgerBookUiModel>,
    val reportUiModel: LedgerReportUiModel,
    val continuousMonitoringPermissionHealth: ContinuousMonitoringPermissionHealth
)

@Composable
internal fun rememberAutoAccountingAppPresentation(
    bindings: AutoAccountingAppBindings,
    runtime: AutoAccountingAppRuntimeState
): AutoAccountingAppPresentation {
    val ledgerEntries = remember(runtime.ledgerState.ledgerEntries) {
        runtime.ledgerState.ledgerEntries.map { it.toLedgerUiEntry() }
    }
    val deletedLedgerEntries = remember(runtime.ledgerState.deletedLedgerEntries) {
        runtime.ledgerState.deletedLedgerEntries.map { it.toLedgerUiEntry() }
    }
    val ledgerBookUiModels = remember(runtime.ledgerState.ledgerBooks, runtime.ledgerState.activeLedgerBook) {
        runtime.ledgerState.ledgerBooks.map { ledgerBook ->
            LedgerBookUiModel(
                id = ledgerBook.id,
                name = ledgerBook.name,
                activeEntryCount = ledgerBook.activeEntryCount,
                deletedEntryCount = ledgerBook.deletedEntryCount,
                isActive = ledgerBook.id == runtime.ledgerState.activeLedgerBook?.id
            )
        }
    }
    return AutoAccountingAppPresentation(
        activeLedgerName = runtime.ledgerState.activeLedgerBook?.name ?: DEFAULT_LEDGER_BOOK_NAME,
        effectiveAiSettings = (runtime.accountSession as? AccountSession.SignedIn)
            ?.takeIf { signedIn ->
                signedIn.token == runtime.cloudAiSettingsLoadedToken &&
                    runtime.accountRuntimeState.cloudWritesAllowed &&
                    runtime.accountDeletionState.cloudWritesAllowed
            }
            ?.let { runtime.aiSettings }
            ?: AiCategorizationSettings(),
        ledgerEntries = ledgerEntries,
        deletedLedgerEntries = deletedLedgerEntries,
        ledgerBookUiModels = ledgerBookUiModels,
        reportUiModel = remember(ledgerEntries) { buildLedgerReportUiModel(ledgerEntries) },
        continuousMonitoringPermissionHealth = remember(
            bindings.billSyncAccessibilityAccessGranted,
            bindings.billSyncAccessibilityServiceConnected
        ) {
            ContinuousMonitoringPermissionHealth(
                billSyncAccessibilityGranted = bindings.billSyncAccessibilityAccessGranted,
                billSyncAccessibilityServiceConnected = bindings.billSyncAccessibilityServiceConnected
            )
        }
    )
}
