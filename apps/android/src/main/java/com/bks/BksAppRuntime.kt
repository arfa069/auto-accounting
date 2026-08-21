package com.bks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bks.data.local.DEFAULT_LEDGER_BOOK_ID
import com.bks.data.local.DEFAULT_LEDGER_BOOK_NAME
import com.bks.data.local.LedgerRepositoryState
import com.bks.feature.account.AccountCredentials
import com.bks.feature.account.AccountDeletionUiState
import com.bks.feature.account.AccountRuntimeState
import com.bks.feature.account.AccountRuntimeStatus
import com.bks.feature.account.AccountSession
import com.bks.feature.account.toSignedInSession
import com.bks.feature.categorization.AiCategorizationSettings
import com.bks.feature.categorization.CategorizationRule
import com.bks.feature.categorization.CloudAiSettingsGatewayResult
import com.bks.feature.diagnostics.DiagnosticComponent
import com.bks.feature.diagnostics.DiagnosticEvent
import com.bks.feature.diagnostics.DiagnosticEventMetadata
import com.bks.feature.diagnostics.DiagnosticLevel
import com.bks.feature.diagnostics.DiagnosticSource
import com.bks.feature.ledger.LedgerBookUiModel
import com.bks.feature.ledger.LedgerReportUiModel
import com.bks.feature.ledger.LedgerUiEntry
import com.bks.feature.ledger.buildLedgerReportUiModel
import com.bks.feature.ledger.toLedgerUiEntry
import com.bks.feature.review.ReviewQueueState
import com.bks.feature.sync.LedgerSyncScheduler
import com.bks.feature.sync.LedgerSyncUiState
import com.bks.ui.BksAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class BksAppRuntimeState {
    var automaticBookkeepingEnabled by mutableStateOf(false)
    var accountSession by mutableStateOf<AccountSession?>(null)
    var isRestoringAccountSession by mutableStateOf(true)
    var accountEntryReturnSession by mutableStateOf<AccountSession?>(null)
    var accountDeletionState by mutableStateOf(AccountDeletionUiState())
    var accountRuntimeState by mutableStateOf(AccountRuntimeState(AccountRuntimeStatus.LocalMode))
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
internal fun rememberBksAppRuntimeState(): BksAppRuntimeState = remember {
    BksAppRuntimeState()
}

internal class BksAppActions(
    private val dependencies: BksAppDependencies,
    private val runtime: BksAppRuntimeState,
    private val appState: BksAppState,
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

    fun setAutomaticBookkeepingEnabled(enabled: Boolean) {
        runtime.automaticBookkeepingEnabled = enabled
        coroutineScope.launch {
            dependencies.local.preferencesRepository.setAutomaticBookkeepingEnabled(enabled)
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

    fun clearLocalData() {
        dependencies.account.wechatAvatarCache.clear()
        runtime.reviewState = ReviewQueueState()
        runtime.categorizationRules = emptyList()
        runtime.aiSettings = AiCategorizationSettings()
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

internal data class BksAppPresentation(
    val activeLedgerName: String,
    val effectiveAiSettings: AiCategorizationSettings,
    val ledgerEntries: List<LedgerUiEntry>,
    val deletedLedgerEntries: List<LedgerUiEntry>,
    val ledgerBookUiModels: List<LedgerBookUiModel>,
    val reportUiModel: LedgerReportUiModel
)

@Composable
internal fun rememberBksAppPresentation(
    runtime: BksAppRuntimeState
): BksAppPresentation {
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
    return BksAppPresentation(
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
        reportUiModel = remember(ledgerEntries) { buildLedgerReportUiModel(ledgerEntries) }
    )
}
