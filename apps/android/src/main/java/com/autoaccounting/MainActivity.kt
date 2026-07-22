package com.autoaccounting

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.autoaccounting.data.local.AutoAccountingDatabaseProvider
import com.autoaccounting.data.local.DEFAULT_LEDGER_BOOK_ID
import com.autoaccounting.data.local.DEFAULT_LEDGER_BOOK_NAME
import com.autoaccounting.data.local.FundingAccountDeleteResult as DataFundingAccountDeleteResult
import com.autoaccounting.data.local.LedgerBookDeleteResult as DataLedgerBookDeleteResult
import com.autoaccounting.data.local.LedgerRepositoryState
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.account.AccountCredentials
import com.autoaccounting.feature.account.AccountDeletionUiState
import com.autoaccounting.feature.account.AccountManagementScreen
import com.autoaccounting.feature.account.AccountRepository
import com.autoaccounting.feature.account.AccountRuntimeState
import com.autoaccounting.feature.account.AccountRuntimeStatus
import com.autoaccounting.feature.account.AccountScreen
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.account.AccountSessionRestoreResult
import com.autoaccounting.feature.account.AccountSessionVerificationDecision
import com.autoaccounting.feature.account.HttpAccountRepository
import com.autoaccounting.feature.account.InstallationIdStore
import com.autoaccounting.feature.account.LocalModeSessionStore
import com.autoaccounting.feature.account.SecureAccountSessionStore
import com.autoaccounting.feature.account.resolveAccountSessionVerification
import com.autoaccounting.feature.billsync.BillSyncSource
import com.autoaccounting.feature.billsync.ManualBillImportHost
import com.autoaccounting.feature.categorization.AiCategorizationGateway
import com.autoaccounting.feature.categorization.AiCategorizationPayload
import com.autoaccounting.feature.categorization.AiCategorizationResponse
import com.autoaccounting.feature.categorization.AiCategorizationSettings
import com.autoaccounting.feature.categorization.CategorizationRule
import com.autoaccounting.feature.categorization.CategorizationRulesScreen
import com.autoaccounting.feature.compliance.ComplianceAndPrivacyScreen
import com.autoaccounting.feature.diagnostics.DiagnosticComponent
import com.autoaccounting.feature.diagnostics.DiagnosticEvent
import com.autoaccounting.feature.diagnostics.DiagnosticEventMetadata
import com.autoaccounting.feature.diagnostics.DiagnosticLevel
import com.autoaccounting.feature.diagnostics.DiagnosticLogs
import com.autoaccounting.feature.diagnostics.DiagnosticSource
import com.autoaccounting.feature.home.HomeScreen
import com.autoaccounting.feature.ledger.FundingAccountDeleteResult as UiFundingAccountDeleteResult
import com.autoaccounting.feature.ledger.LedgerBookDeleteResult as UiLedgerBookDeleteResult
import com.autoaccounting.feature.ledger.LedgerBookUiModel
import com.autoaccounting.feature.ledger.LedgerScreen
import com.autoaccounting.feature.ledger.ManualLedgerEntryScreen
import com.autoaccounting.feature.ledger.ReportsScreen
import com.autoaccounting.feature.ledger.buildLedgerReportUiModel
import com.autoaccounting.feature.ledger.toLedgerUiEntry
import com.autoaccounting.feature.monitoring.AutomaticBookkeepingScreen
import com.autoaccounting.feature.monitoring.BackgroundReliabilityState
import com.autoaccounting.feature.monitoring.ContinuousMonitoringAction
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.monitoring.MonitoringStateCoordinator
import com.autoaccounting.feature.monitoring.reduceContinuousMonitoringState
import com.autoaccounting.feature.profile.ProfileDestination
import com.autoaccounting.feature.profile.ProfileOverviewScreen
import com.autoaccounting.feature.review.ReviewQueuePersistence
import com.autoaccounting.feature.review.ReviewQueueScreen
import com.autoaccounting.feature.review.ReviewQueueState
import com.autoaccounting.feature.settings.DataAndBackupScreen
import com.autoaccounting.feature.settings.LocalDataBackupRepository
import com.autoaccounting.ui.components.AppBottomNavigationBar
import com.autoaccounting.ui.components.SlidePageTransition
import com.autoaccounting.ui.rememberAutoAccountingAppState
import com.autoaccounting.ui.requestHighRefreshRate
import com.autoaccounting.ui.theme.AutoAccountingTheme
import com.autoaccounting.ui.visual.AppWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val coordinator by lazy { MonitoringStateCoordinator(this) }
    private val reviewNavigationRequest = mutableStateOf(0L)
    private val pendingEntryNavigationId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNavigationIntent(intent)
        coordinator.onCreate()
        setContent {
            AutoAccountingApp(
                notificationListenerAccessGranted = coordinator.notificationListenerAccessGranted.value,
                onOpenNotificationListenerSettings = coordinator::openNotificationListenerSettings,
                billSyncAccessibilityAccessGranted = coordinator.billSyncAccessibilityAccessGranted.value,
                billSyncAccessibilityServiceConnected = coordinator.billSyncAccessibilityServiceConnected.value,
                onOpenBillSyncAccessibilitySettings = coordinator::openBillSyncAccessibilitySettings,
                resultNotificationPermissionGranted = coordinator.resultNotificationPermissionGranted.value,
                onRequestResultNotificationPermission = coordinator::requestResultNotificationPermission,
                backgroundReliabilityState = coordinator.backgroundReliabilityState.value,
                onOpenBackgroundRunningSettings = coordinator::openBackgroundRunningSettings,
                onOpenAutoStartSettings = coordinator::openAutoStartSettings,
                onOpenBatteryOptimizationSettings = coordinator::openBatteryOptimizationSettings,
                onOpenBatterySaverSettings = coordinator::openBatterySaverSettings,
                onLaunchBillSyncSource = coordinator::launchBillSyncSource,
                permissionStateLoaded = coordinator.permissionStateLoaded.value,
                reviewNavigationRequest = reviewNavigationRequest.value,
                pendingEntryNavigationId = pendingEntryNavigationId.value
            )
        }
        requestHighRefreshRate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        coordinator.onResume()
    }

    override fun onDestroy() {
        coordinator.onDestroy()
        super.onDestroy()
    }

    private fun handleNavigationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_REVIEW, false) == true) {
            pendingEntryNavigationId.value = intent.getStringExtra(EXTRA_PENDING_ENTRY_ID)
            reviewNavigationRequest.value += 1
        }
    }

    companion object {
        const val EXTRA_OPEN_REVIEW = "com.autoaccounting.extra.OPEN_REVIEW"
        const val EXTRA_PENDING_ENTRY_ID = "com.autoaccounting.extra.PENDING_ENTRY_ID"
    }
}

@Composable
fun AutoAccountingApp(
    notificationListenerAccessGranted: Boolean = false,
    onOpenNotificationListenerSettings: () -> Unit = {},
    billSyncAccessibilityAccessGranted: Boolean = false,
    billSyncAccessibilityServiceConnected: Boolean = true,
    onOpenBillSyncAccessibilitySettings: () -> Unit = {},
    resultNotificationPermissionGranted: Boolean = false,
    onRequestResultNotificationPermission: () -> Unit = {},
    backgroundReliabilityState: BackgroundReliabilityState = BackgroundReliabilityState(),
    onOpenBackgroundRunningSettings: () -> Unit = {},
    onOpenAutoStartSettings: () -> Unit = {},
    onOpenBatteryOptimizationSettings: () -> Unit = {},
    onOpenBatterySaverSettings: () -> Unit = {},
    onLaunchBillSyncSource: (BillSyncSource) -> Boolean = { false },
    permissionStateLoaded: Boolean = false,
    reviewNavigationRequest: Long = 0,
    pendingEntryNavigationId: String? = null,
    accountRepositoryOverride: AccountRepository? = null,
    persistAccountSessionOverride: ((AccountCredentials) -> Boolean)? = null
) {
    val context = LocalContext.current
    val database = remember { AutoAccountingDatabaseProvider.get(context) }
    val localLedgerRepository = remember(database) { LocalLedgerRepository(database) }
    val localPreferencesRepository = remember(database) { LocalPreferencesRepository(database) }
    val localDataBackupRepository = remember(database) { LocalDataBackupRepository(database) }
    val localModeSessionStore = remember(context.applicationContext) { LocalModeSessionStore(context.applicationContext) }
    val secureAccountSessionStore = remember(context.applicationContext) { SecureAccountSessionStore(context.applicationContext) }
    val installationIdStore = remember(context.applicationContext) { InstallationIdStore(context.applicationContext) }
    val productionAccountRepository = remember(installationIdStore) {
        HttpAccountRepository(
            backendUrl = BuildConfig.AUTO_ACCOUNTING_BACKEND_URL,
            installationId = installationIdStore::getOrCreate
        )
    }
    val accountRepository = accountRepositoryOverride ?: productionAccountRepository
    val diagnosticLogs = remember(context.applicationContext) { DiagnosticLogs.get(context.applicationContext) }
    val reviewQueuePersistence = remember(localLedgerRepository) { ReviewQueuePersistence(localLedgerRepository) }
    val coroutineScope = rememberCoroutineScope()
    val appState = rememberAutoAccountingAppState()

    var selectedTab by remember { mutableStateOf<AppTab?>(null) }
    var manualEntryOpen by remember { mutableStateOf(false) }
    var manualBillImportRequestId by remember { mutableLongStateOf(0L) }
    var profileDestination by remember { mutableStateOf<ProfileDestination?>(null) }
    var accountSession by remember { mutableStateOf<AccountSession?>(null) }
    var isRestoringAccountSession by remember { mutableStateOf(true) }
    var accountEntryReturnSession by remember { mutableStateOf<AccountSession?>(null) }
    var accountDeletionState by remember { mutableStateOf(AccountDeletionUiState()) }
    var accountRuntimeState by remember { mutableStateOf(AccountRuntimeState(AccountRuntimeStatus.LocalMode)) }
    var continuousMonitoringState by remember { mutableStateOf(ContinuousMonitoringState()) }
    var aiSettings by remember { mutableStateOf(AiCategorizationSettings()) }
    var reviewState by remember { mutableStateOf(ReviewQueueState()) }
    var reviewTransitionInFlight by remember { mutableStateOf(false) }
    var ledgerState by remember { mutableStateOf(LedgerRepositoryState()) }
    var categorizationRules by remember { mutableStateOf(emptyList<CategorizationRule>()) }

    val activeLedgerName = ledgerState.activeLedgerBook?.name ?: DEFAULT_LEDGER_BOOK_NAME
    val ledgerEntries = remember(ledgerState.ledgerEntries) {
        ledgerState.ledgerEntries.map { it.toLedgerUiEntry() }
    }
    val deletedLedgerEntries = remember(ledgerState.deletedLedgerEntries) {
        ledgerState.deletedLedgerEntries.map { it.toLedgerUiEntry() }
    }
    val ledgerBookUiModels = remember(ledgerState.ledgerBooks, ledgerState.activeLedgerBook) {
        ledgerState.ledgerBooks.map { ledgerBook ->
            LedgerBookUiModel(
                id = ledgerBook.id,
                name = ledgerBook.name,
                activeEntryCount = ledgerBook.activeEntryCount,
                deletedEntryCount = ledgerBook.deletedEntryCount,
                isActive = ledgerBook.id == ledgerState.activeLedgerBook?.id
            )
        }
    }
    val reportUiModel = remember(ledgerEntries) { buildLedgerReportUiModel(ledgerEntries) }
    val continuousMonitoringPermissionHealth = remember(
        billSyncAccessibilityAccessGranted,
        billSyncAccessibilityServiceConnected
    ) {
        ContinuousMonitoringPermissionHealth(
            billSyncAccessibilityGranted = billSyncAccessibilityAccessGranted,
            billSyncAccessibilityServiceConnected = billSyncAccessibilityServiceConnected
        )
    }

    fun moveAccountToLocalMode() {
        secureAccountSessionStore.clear()
        localModeSessionStore.confirmLocalMode()
        accountSession = AccountSession.LocalMode
        accountDeletionState = AccountDeletionUiState()
        accountRuntimeState = AccountRuntimeState(AccountRuntimeStatus.LocalMode)
    }

    fun applyVerifiedCredentials(credentials: AccountCredentials) {
        accountSession = AccountSession.SignedIn(credentials.phone, credentials.token)
        accountDeletionState = credentials.deletionState
        accountRuntimeState = AccountRuntimeState(
            if (credentials.deletionState.isPending) {
                AccountRuntimeStatus.DeletionCoolingOff
            } else {
                AccountRuntimeStatus.Verified
            }
        )
    }

    LaunchedEffect(secureAccountSessionStore, localModeSessionStore) {
        when (val restored = withContext(Dispatchers.IO) { secureAccountSessionStore.restore() }) {
            is AccountSessionRestoreResult.Restored -> {
                accountSession = AccountSession.SignedIn(
                    phone = restored.credentials.phone,
                    token = restored.credentials.token
                )
                accountDeletionState = restored.credentials.deletionState
                accountRuntimeState = AccountRuntimeState(AccountRuntimeStatus.Validating)
            }
            AccountSessionRestoreResult.Corrupted -> {
                withContext(Dispatchers.IO) { localModeSessionStore.confirmLocalMode() }
                accountSession = AccountSession.LocalMode
            }
            AccountSessionRestoreResult.Empty -> {
                accountSession = withContext(Dispatchers.IO) { localModeSessionStore.restoreSession() }
            }
        }
        isRestoringAccountSession = false
    }

    LaunchedEffect(accountSession, accountRuntimeState.status) {
        val signedIn = accountSession as? AccountSession.SignedIn
        if (signedIn == null || accountRuntimeState.status != AccountRuntimeStatus.Validating) {
            return@LaunchedEffect
        }
        when (
            val decision = resolveAccountSessionVerification(
                accountRepository.verifySession(
                    AccountCredentials(phone = signedIn.phone, token = signedIn.token)
                )
            )
        ) {
            is AccountSessionVerificationDecision.Verified -> applyVerifiedCredentials(decision.credentials)
            AccountSessionVerificationDecision.ClearInvalidSession -> {
                moveAccountToLocalMode()
                appState.snackbarHostState.showSnackbar("登录状态已失效，已切换到本地模式")
            }
            AccountSessionVerificationDecision.KeepOfflineSession ->
                accountRuntimeState = AccountRuntimeState(AccountRuntimeStatus.OfflineUnverified)
        }
    }

    LaunchedEffect(reviewNavigationRequest) {
        if (reviewNavigationRequest > 0) {
            selectedTab = AppTab.Review
        }
    }

    fun persistReviewTransition(previousState: ReviewQueueState, nextState: ReviewQueueState) {
        if (reviewTransitionInFlight) {
            coroutineScope.launch { appState.snackbarHostState.showSnackbar("上一项操作正在保存，请稍后重试") }
            return
        }
        val previousConfirmedIds = previousState.confirmedEntries.mapTo(mutableSetOf()) { it.originPendingId }
        val addsConfirmation = nextState.confirmedEntries.any { it.originPendingId !in previousConfirmedIds }
        val targetLedgerBookId = ledgerState.activeLedgerBook?.id
        if (addsConfirmation && targetLedgerBookId == null) {
            coroutineScope.launch { appState.snackbarHostState.showSnackbar("当前账本尚未加载，请稍后重试") }
            return
        }
        reviewTransitionInFlight = true
        reviewState = nextState
        coroutineScope.launch {
            val failure = runCatching {
                reviewQueuePersistence.persistTransition(
                    previous = previousState,
                    next = nextState,
                    targetLedgerBookId = targetLedgerBookId ?: DEFAULT_LEDGER_BOOK_ID
                )
            }.exceptionOrNull()
            if (failure != null) {
                val persistedState = runCatching {
                    reviewQueuePersistence.observeState().first()
                }.getOrElse {
                    previousState.copy(confirmedEntries = emptyList(), lastAction = null)
                }
                reviewState = persistedState.copy(
                    undoEventSequence = maxOf(persistedState.undoEventSequence, previousState.undoEventSequence)
                )
            }
            reviewTransitionInFlight = false
            if (failure != null) {
                appState.snackbarHostState.showSnackbar("保存待确认操作失败，请重试")
            }
        }
    }

    fun persistReviewState(nextState: ReviewQueueState) {
        persistReviewTransition(reviewState, nextState)
    }

    fun persistCategorizationRules(nextRules: List<CategorizationRule>) {
        categorizationRules = nextRules
        coroutineScope.launch { localPreferencesRepository.replaceCategorizationRules(nextRules) }
    }

    fun persistAiSettings(nextSettings: AiCategorizationSettings) {
        aiSettings = nextSettings
        coroutineScope.launch { localPreferencesRepository.updateAiSettings(nextSettings) }
    }

    fun persistContinuousMonitoringState(nextState: ContinuousMonitoringState) {
        val previousState = continuousMonitoringState
        continuousMonitoringState = nextState
        if (previousState.enabled != nextState.enabled) {
            diagnosticLogs.record(
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
        coroutineScope.launch { localPreferencesRepository.updateContinuousMonitoringState(nextState) }
    }

    LaunchedEffect(
        permissionStateLoaded,
        continuousMonitoringPermissionHealth,
        continuousMonitoringState.enabled
    ) {
        if (!permissionStateLoaded || !continuousMonitoringState.enabled) return@LaunchedEffect
        val refreshedState = reduceContinuousMonitoringState(
            continuousMonitoringState,
            ContinuousMonitoringAction.RefreshPermissionHealth(continuousMonitoringPermissionHealth)
        )
        if (refreshedState != continuousMonitoringState) {
            continuousMonitoringState = refreshedState
            diagnosticLogs.record(
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

    LaunchedEffect(localLedgerRepository) {
        localLedgerRepository.ensureDefaultLedgerBook()
        localLedgerRepository.seedSystemCategories()
        localLedgerRepository.purgeExpiredDeletedLedgerEntries()
    }

    LaunchedEffect(reviewQueuePersistence) {
        reviewQueuePersistence.observeState().collect { persistedState ->
            reviewState = persistedState.copy(
                confirmedEntries = reviewState.confirmedEntries,
                lastAction = reviewState.lastAction,
                undoEventSequence = reviewState.undoEventSequence
            )
        }
    }

    LaunchedEffect(localLedgerRepository) {
        localLedgerRepository.state.collect { state -> ledgerState = state }
    }

    LaunchedEffect(localPreferencesRepository) {
        localPreferencesRepository.categorizationRules.collect { rules -> categorizationRules = rules }
    }

    LaunchedEffect(localPreferencesRepository) {
        localPreferencesRepository.userPreferences.collect { preferences ->
            aiSettings = preferences.aiSettings
            continuousMonitoringState = preferences.continuousMonitoringState
        }
    }

    AutoAccountingTheme {
        if (isRestoringAccountSession) {
            AppWallpaper(R.drawable.aa_bg_account) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.testTag("account-session-restoring"))
                }
            }
        } else SlidePageTransition(
            targetState = accountSession,
            modifier = Modifier.fillMaxSize()
        ) { activeAccountSession ->
            if (activeAccountSession == null) {
                AppWallpaper(R.drawable.aa_bg_account) {
                    AccountScreen(
                        accountRepository = accountRepository,
                        persistSession = { credentials ->
                            val saved = persistAccountSessionOverride?.invoke(credentials)
                                ?: secureAccountSessionStore.save(credentials)
                            if (saved) applyVerifiedCredentials(credentials)
                            saved
                        },
                        onSessionChange = { session ->
                            if (session == AccountSession.LocalMode) {
                                localModeSessionStore.confirmLocalMode()
                                accountRuntimeState = AccountRuntimeState(AccountRuntimeStatus.LocalMode)
                            }
                            accountSession = session
                            accountEntryReturnSession = null
                            selectedTab = if (reviewNavigationRequest > 0) AppTab.Review else null
                            profileDestination = null
                        },
                        onBack = accountEntryReturnSession?.let { returnSession ->
                            {
                                accountSession = returnSession
                                accountEntryReturnSession = null
                                selectedTab = AppTab.Profile
                                profileDestination = ProfileDestination.AccountManagement
                            }
                        }
                    )
                }
            } else {
                BackHandler(
                    enabled = selectedTab != null &&
                        (selectedTab != AppTab.Profile || profileDestination == null) &&
                        !manualEntryOpen
                ) {
                    selectedTab = null
                }

                BackHandler(enabled = selectedTab == AppTab.Profile && profileDestination != null) {
                    profileDestination = null
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent
                ) {
                    Scaffold(
                        containerColor = Color.Transparent,
                        snackbarHost = { SnackbarHost(appState.snackbarHostState) },
                        bottomBar = {
                            if (selectedTab == null && !manualEntryOpen) {
                                AppBottomNavigationBar(
                                    items = appState.bottomNavigationItems,
                                    selectedKey = null,
                                    onItemSelected = { key ->
                                        if (!manualEntryOpen) {
                                            selectedTab = appState.tabs.first { it.name == key }
                                            profileDestination = null
                                        }
                                    },
                                    onAddEntry = {
                                        if (!manualEntryOpen) {
                                            manualEntryOpen = true
                                        }
                                    },
                                    enabled = !manualEntryOpen
                                )
                            }
                        }
                    ) { innerPadding ->
                        val route = remember(selectedTab, profileDestination, manualEntryOpen) {
                            AppRoute(
                                tab = selectedTab,
                                profileDestination = profileDestination.takeIf { selectedTab == AppTab.Profile },
                                manualEntryOpen = manualEntryOpen
                            )
                        }
                        SlidePageTransition(
                            targetState = route,
                            modifier = Modifier.fillMaxSize()
                        ) { targetRoute ->
                            val wallpaperRes = when {
                                targetRoute.manualEntryOpen -> R.drawable.aa_bg_ledger
                                targetRoute.tab == null -> R.drawable.aa_bg_account
                                targetRoute.profileDestination != null -> R.drawable.aa_bg_neutral
                                else -> targetRoute.tab.backgroundRes
                            }
                            AppWallpaper(wallpaperRes) {
                                if (targetRoute.manualEntryOpen) {
                                    ManualLedgerEntryScreen(
                                        categories = ledgerState.categories,
                                        fundingAccounts = ledgerState.fundingAccounts,
                                        onExit = { manualEntryOpen = false },
                                        onCreateEntry = { input ->
                                            val targetLedgerBookId = ledgerState.activeLedgerBook?.id
                                                ?: error("当前账本尚未加载")
                                            localLedgerRepository.createManualEntry(
                                                ledgerBookId = targetLedgerBookId,
                                                input = input
                                            )
                                            manualEntryOpen = false
                                            selectedTab = AppTab.Ledger
                                            profileDestination = null
                                        },
                                        modifier = Modifier.fillMaxSize().padding(innerPadding)
                                    )
                                } else {
                                    when (targetRoute.tab) {
                                        null -> HomeScreen(modifier = Modifier.padding(innerPadding))
                                        AppTab.Review -> ReviewQueueScreen(
                                            state = reviewState,
                                            targetLedgerName = activeLedgerName,
                                            categories = ledgerState.categories,
                                            fundingAccounts = ledgerState.fundingAccounts,
                                            onStateChange = ::persistReviewState,
                                            modifier = Modifier.padding(innerPadding),
                                            onCategorizationRuleRequested = { rule ->
                                                persistCategorizationRules(categorizationRules.upsert(rule))
                                            },
                                            accountSession = accountSession,
                                            aiSettings = if (accountRuntimeState.cloudWritesAllowed && accountDeletionState.cloudWritesAllowed) {
                                                aiSettings
                                            } else {
                                                AiCategorizationSettings()
                                            },
                                            aiCategorizationGateway = DemoAiCategorizationGateway,
                                            onOpenBillImport = { manualBillImportRequestId += 1 },
                                            openPendingEntryId = pendingEntryNavigationId,
                                            openPendingEntryRequestId = reviewNavigationRequest,
                                            onNavigateHome = {
                                                selectedTab = null
                                                profileDestination = null
                                            }
                                        )

                                        AppTab.Ledger -> LedgerScreen(
                                            entries = ledgerEntries,
                                            entryListState = appState.ledgerEntryListState,
                                            deletedEntries = deletedLedgerEntries,
                                            categories = ledgerState.categories,
                                            fundingAccounts = ledgerState.fundingAccounts,
                                            ledgerBooks = ledgerBookUiModels,
                                            activeLedgerName = activeLedgerName,
                                            onUpdateEntry = { id, input -> localLedgerRepository.updateLedgerEntry(id, input) },
                                            onDeleteEntry = { id -> localLedgerRepository.moveLedgerEntryToDeleted(id) },
                                            onRestoreEntry = { id -> localLedgerRepository.restoreDeletedLedgerEntry(id) },
                                            onPermanentlyDeleteEntry = { id -> localLedgerRepository.permanentlyDeleteLedgerEntry(id) },
                                            onPurgeExpiredEntries = { localLedgerRepository.purgeExpiredDeletedLedgerEntries() },
                                            onCreateLedger = { name -> localLedgerRepository.createLedgerBook(name) },
                                            onSelectLedger = { id -> localLedgerRepository.selectLedgerBook(id) },
                                            onDeleteLedger = { id ->
                                                when (val result = localLedgerRepository.deleteLedgerBook(id)) {
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
                                                localLedgerRepository.createFundingAccount(label = label, paymentSource = paymentSource)
                                            },
                                            onUpdateFundingAccount = { id, label, paymentSource ->
                                                localLedgerRepository.updateFundingAccount(fundingAccountId = id, label = label, paymentSource = paymentSource)
                                            },
                                            onDeleteFundingAccount = { id ->
                                                when (val result = localLedgerRepository.deleteFundingAccount(id)) {
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
                                            onNavigateHome = {
                                                selectedTab = null
                                                profileDestination = null
                                            },
                                            modifier = Modifier.padding(innerPadding)
                                        )

                                        AppTab.Reports -> ReportsScreen(
                                            entries = ledgerEntries,
                                            reportUiModel = reportUiModel,
                                            categoryRankingListState = appState.reportCategoryRankingListState,
                                            onNavigateHome = {
                                                selectedTab = null
                                                profileDestination = null
                                            },
                                            modifier = Modifier.padding(innerPadding)
                                        )

                                        AppTab.Profile -> when (targetRoute.profileDestination) {
                                            null -> ProfileOverviewScreen(
                                                session = activeAccountSession,
                                                onDestinationSelected = { profileDestination = it },
                                                onNavigateHome = {
                                                    selectedTab = null
                                                    profileDestination = null
                                                },
                                                modifier = Modifier.padding(innerPadding)
                                            )

                                            ProfileDestination.AccountManagement -> AccountManagementScreen(
                                                session = activeAccountSession,
                                                runtimeState = accountRuntimeState,
                                                deletionState = accountDeletionState,
                                                accountRepository = accountRepository,
                                                onSignInOrRegister = {
                                                    accountEntryReturnSession = activeAccountSession
                                                    profileDestination = null
                                                    accountSession = null
                                                    accountRuntimeState = AccountRuntimeState(AccountRuntimeStatus.LocalMode)
                                                },
                                                onSessionVerified = ::applyVerifiedCredentials,
                                                onInvalidSession = {
                                                    moveAccountToLocalMode()
                                                    profileDestination = null
                                                },
                                                clearPersistedSession = secureAccountSessionStore::clear,
                                                onSignedOut = {
                                                    localModeSessionStore.confirmLocalMode()
                                                    accountSession = AccountSession.LocalMode
                                                    accountDeletionState = AccountDeletionUiState()
                                                    accountRuntimeState = AccountRuntimeState(AccountRuntimeStatus.LocalMode)
                                                    profileDestination = null
                                                },
                                                onDeletionStateChange = { deletionState ->
                                                    accountDeletionState = deletionState
                                                    accountRuntimeState = AccountRuntimeState(
                                                        if (deletionState.isPending) AccountRuntimeStatus.DeletionCoolingOff else AccountRuntimeStatus.Verified
                                                    )
                                                },
                                                onBack = { profileDestination = null },
                                                modifier = Modifier.padding(innerPadding)
                                            )

                                            ProfileDestination.AutomaticBookkeeping -> AutomaticBookkeepingScreen(
                                                notificationListenerAccessGranted = notificationListenerAccessGranted,
                                                onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                                                billSyncAccessibilityAccessGranted = billSyncAccessibilityAccessGranted,
                                                onOpenBillSyncAccessibilitySettings = onOpenBillSyncAccessibilitySettings,
                                                resultNotificationPermissionGranted = resultNotificationPermissionGranted,
                                                onRequestResultNotificationPermission = onRequestResultNotificationPermission,
                                                backgroundReliabilityState = backgroundReliabilityState,
                                                onOpenBackgroundRunningSettings = onOpenBackgroundRunningSettings,
                                                onOpenAutoStartSettings = onOpenAutoStartSettings,
                                                onOpenBatteryOptimizationSettings = onOpenBatteryOptimizationSettings,
                                                onOpenBatterySaverSettings = onOpenBatterySaverSettings,
                                                continuousMonitoringState = continuousMonitoringState,
                                                continuousMonitoringPermissionHealth = continuousMonitoringPermissionHealth,
                                                onContinuousMonitoringStateChange = ::persistContinuousMonitoringState,
                                                onBack = { profileDestination = null },
                                                modifier = Modifier.padding(innerPadding)
                                            )

                                            ProfileDestination.CategorizationRules -> CategorizationRulesScreen(
                                                rules = categorizationRules,
                                                onRulesChange = ::persistCategorizationRules,
                                                aiSettings = aiSettings,
                                                onAiSettingsChange = ::persistAiSettings,
                                                accountSession = activeAccountSession,
                                                accountDeletionState = accountDeletionState,
                                                accountRuntimeState = accountRuntimeState,
                                                onBack = { profileDestination = null },
                                                modifier = Modifier.padding(innerPadding)
                                            )

                                            ProfileDestination.DataAndBackup -> DataAndBackupScreen(
                                                ledgerEntries = ledgerEntries,
                                                currentLedgerName = activeLedgerName,
                                                onExportEncryptedBackup = { passphrase -> localDataBackupRepository.exportEncryptedBackup(passphrase) },
                                                onValidateEncryptedBackup = { backup, passphrase -> localDataBackupRepository.validateEncryptedBackup(backup, passphrase) },
                                                onImportEncryptedBackup = { backup, passphrase ->
                                                    localDataBackupRepository.importEncryptedBackup(backup, passphrase)
                                                    reviewState = ReviewQueueState()
                                                },
                                                onDeleteLocalData = {
                                                    reviewState = ReviewQueueState()
                                                    categorizationRules = emptyList()
                                                    aiSettings = AiCategorizationSettings()
                                                    continuousMonitoringState = ContinuousMonitoringState()
                                                    ledgerState = LedgerRepositoryState()
                                                    coroutineScope.launch {
                                                        try {
                                                            localLedgerRepository.clearLocalData()
                                                        } finally {
                                                            diagnosticLogs.clear(keepEnabledPreference = false)
                                                        }
                                                    }
                                                },
                                                onBack = { profileDestination = null },
                                                snackbarHostState = appState.snackbarHostState,
                                                modifier = Modifier.padding(innerPadding)
                                            )

                                            ProfileDestination.ComplianceAndPrivacy -> ComplianceAndPrivacyScreen(
                                                isDebugBuild = BuildConfig.DEBUG,
                                                onBack = { profileDestination = null },
                                                modifier = Modifier.padding(innerPadding)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ManualBillImportHost(
                        openRequestId = manualBillImportRequestId,
                        accessibilityAccessGranted = billSyncAccessibilityAccessGranted,
                        accessibilityServiceConnected = billSyncAccessibilityServiceConnected,
                        onOpenAccessibilitySettings = onOpenBillSyncAccessibilitySettings,
                        onLaunchSource = onLaunchBillSyncSource,
                        onNavigateToReview = {
                            selectedTab = AppTab.Review
                            profileDestination = null
                        },
                        continuousMonitoringState = continuousMonitoringState,
                        continuousMonitoringPermissionHealth = continuousMonitoringPermissionHealth,
                        onContinuousMonitoringStateChange = ::persistContinuousMonitoringState,
                        diagnosticRecorder = diagnosticLogs
                    )
                }
            }
        }
    }
}

private object DemoAiCategorizationGateway : AiCategorizationGateway {
    override fun suggestCategory(
        token: String,
        payload: AiCategorizationPayload
    ): AiCategorizationResponse {
        val category = when {
            payload.merchantTitle.contains("地铁") -> "交通"
            payload.merchantTitle.contains("餐") || payload.merchantTitle.contains("咖啡") -> "餐饮"
            else -> "未分类"
        }
        return AiCategorizationResponse(
            category = category,
            confidenceLabel = "中",
            explanation = "通过后端 AI 代理返回的分类建议"
        )
    }
}

internal enum class AppTab(
    val label: String,
    val iconRes: Int,
    val backgroundRes: Int
) {
    Review(
        label = "待确认",
        iconRes = R.drawable.aa_nav_review_outlined,
        backgroundRes = R.drawable.aa_bg_review
    ),
    Ledger(
        label = "账本",
        iconRes = R.drawable.aa_nav_ledger_outlined,
        backgroundRes = R.drawable.aa_bg_ledger
    ),
    Reports(
        label = "报表",
        iconRes = R.drawable.aa_nav_reports_outlined,
        backgroundRes = R.drawable.aa_bg_reports
    ),
    Profile(
        label = "我的",
        iconRes = R.drawable.aa_nav_profile_outlined,
        backgroundRes = R.drawable.aa_bg_profile
    )
}

private data class AppRoute(
    val tab: AppTab?,
    val profileDestination: ProfileDestination?,
    val manualEntryOpen: Boolean
)

private fun List<CategorizationRule>.upsert(rule: CategorizationRule): List<CategorizationRule> {
    return if (any { it.id == rule.id }) {
        map { existing -> if (existing.id == rule.id) rule else existing }
    } else {
        this + rule
    }
}
