package com.autoaccounting

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autoaccounting.data.local.AutoAccountingDatabaseProvider
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.DEFAULT_LEDGER_BOOK_ID
import com.autoaccounting.data.local.DEFAULT_LEDGER_BOOK_NAME
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.FundingAccountDeleteResult as DataFundingAccountDeleteResult
import com.autoaccounting.data.local.LedgerBookDeleteResult as DataLedgerBookDeleteResult
import com.autoaccounting.data.local.LedgerBookEntity
import com.autoaccounting.data.local.LedgerEntryEntity
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.account.AccountDeletionUiState
import com.autoaccounting.feature.account.AccountScreen
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.account.LocalModeSessionStore
import com.autoaccounting.feature.account.signOutToLocalMode
import com.autoaccounting.feature.billsync.BillSyncPermission
import com.autoaccounting.feature.billsync.BillSyncSource
import com.autoaccounting.feature.billsync.startManualBillSync
import com.autoaccounting.feature.categorization.AiCategorizationGateway
import com.autoaccounting.feature.categorization.AiCategorizationPayload
import com.autoaccounting.feature.categorization.AiCategorizationResponse
import com.autoaccounting.feature.categorization.AiCategorizationSettings
import com.autoaccounting.feature.categorization.CategorizationRule
import com.autoaccounting.feature.categorization.CategorizationRulesScreen
import com.autoaccounting.feature.compliance.ComplianceAndPrivacyScreen
import com.autoaccounting.feature.capture.NotificationListenerPermission
import com.autoaccounting.feature.capture.BookkeepingResultNotificationPermission
import com.autoaccounting.feature.capture.shouldRequestBookkeepingResultNotificationPermission
import com.autoaccounting.feature.home.HomeScreen
import com.autoaccounting.feature.ledger.LedgerUiEntry
import com.autoaccounting.feature.ledger.LedgerScreen
import com.autoaccounting.feature.ledger.LedgerBookUiModel
import com.autoaccounting.feature.ledger.LedgerBookDeleteResult as UiLedgerBookDeleteResult
import com.autoaccounting.feature.ledger.FundingAccountDeleteResult as UiFundingAccountDeleteResult
import com.autoaccounting.feature.ledger.ManualLedgerEntryScreen
import com.autoaccounting.feature.ledger.ReportsScreen
import com.autoaccounting.feature.ledger.toLedgerUiEntry
import com.autoaccounting.feature.monitoring.ContinuousMonitoringAction
import com.autoaccounting.feature.monitoring.AutomaticBookkeepingScreen
import com.autoaccounting.feature.monitoring.BackgroundReliability
import com.autoaccounting.feature.monitoring.BackgroundReliabilityState
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.monitoring.ContinuousMonitoringServiceHealth
import com.autoaccounting.feature.monitoring.SERVICE_HEARTBEAT_INTERVAL_MILLIS
import com.autoaccounting.feature.monitoring.reduceContinuousMonitoringState
import com.autoaccounting.feature.monitoring.launchSettingsIntent
import com.autoaccounting.feature.profile.AccountManagementScreen
import com.autoaccounting.feature.profile.ProfileDestination
import com.autoaccounting.feature.profile.ProfileOverviewScreen
import com.autoaccounting.feature.review.ReviewQueuePersistence
import com.autoaccounting.feature.review.ReviewQueueScreen
import com.autoaccounting.feature.review.ReviewQueueState
import com.autoaccounting.feature.settings.LocalDataBackupRepository
import com.autoaccounting.feature.settings.DataAndBackupScreen
import com.autoaccounting.ui.components.AppBottomNavigationBar
import com.autoaccounting.ui.components.AppBottomNavigationItem
import com.autoaccounting.ui.components.SlidePageTransition
import com.autoaccounting.ui.theme.AutoAccountingTheme
import com.autoaccounting.ui.visual.AppWallpaper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationListenerAccessGranted = mutableStateOf(false)
    private val billSyncAccessibilityAccessGranted = mutableStateOf(false)
    private val billSyncAccessibilityServiceConnected = mutableStateOf(false)
    private val resultNotificationPermissionGranted = mutableStateOf(false)
    private val backgroundReliabilityState = mutableStateOf(BackgroundReliabilityState())
    private val permissionStateLoaded = mutableStateOf(false)
    private val reviewNavigationRequest = mutableStateOf(0L)
    private val pendingEntryNavigationId = mutableStateOf<String?>(null)
    private val requestResultNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        resultNotificationPermissionGranted.value = granted
    }
    private var monitoringServiceHealthListener:
        SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val monitoringServiceHealthHandler = Handler(Looper.getMainLooper())
    private val refreshMonitoringServiceHealth = object : Runnable {
        override fun run() {
            billSyncAccessibilityServiceConnected.value =
                ContinuousMonitoringServiceHealth.isServiceConnected(this@MainActivity)
            monitoringServiceHealthHandler.postDelayed(
                this,
                SERVICE_HEARTBEAT_INTERVAL_MILLIS
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNavigationIntent(intent)
        billSyncAccessibilityServiceConnected.value =
            ContinuousMonitoringServiceHealth.isServiceConnected(this)
        monitoringServiceHealthListener = ContinuousMonitoringServiceHealth.registerListener(this) {
            connected -> billSyncAccessibilityServiceConnected.value = connected
        }
        monitoringServiceHealthHandler.post(refreshMonitoringServiceHealth)
        setContent {
            AutoAccountingApp(
                notificationListenerAccessGranted = notificationListenerAccessGranted.value,
                onOpenNotificationListenerSettings = ::openNotificationListenerSettings,
                billSyncAccessibilityAccessGranted = billSyncAccessibilityAccessGranted.value,
                billSyncAccessibilityServiceConnected = billSyncAccessibilityServiceConnected.value,
                onOpenBillSyncAccessibilitySettings = ::openBillSyncAccessibilitySettings,
                resultNotificationPermissionGranted = resultNotificationPermissionGranted.value,
                onRequestResultNotificationPermission = ::requestResultNotificationPermission,
                backgroundReliabilityState = backgroundReliabilityState.value,
                onOpenBackgroundRunningSettings = ::openBackgroundRunningSettings,
                onOpenAutoStartSettings = ::openAutoStartSettings,
                onOpenBatteryOptimizationSettings = ::openBatteryOptimizationSettings,
                onOpenBatterySaverSettings = ::openBatterySaverSettings,
                onLaunchBillSyncSource = ::launchBillSyncSource,
                permissionStateLoaded = permissionStateLoaded.value,
                reviewNavigationRequest = reviewNavigationRequest.value,
                pendingEntryNavigationId = pendingEntryNavigationId.value
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        notificationListenerAccessGranted.value =
            NotificationListenerPermission.isGranted(this)
        billSyncAccessibilityAccessGranted.value = BillSyncPermission.isGranted(this)
        billSyncAccessibilityServiceConnected.value =
            ContinuousMonitoringServiceHealth.isServiceConnected(this)
        resultNotificationPermissionGranted.value =
            BookkeepingResultNotificationPermission.isGranted(this)
        backgroundReliabilityState.value = BackgroundReliability.read(this)
        permissionStateLoaded.value = true
    }

    override fun onDestroy() {
        monitoringServiceHealthListener?.let { listener ->
            ContinuousMonitoringServiceHealth.unregisterListener(this, listener)
        }
        monitoringServiceHealthListener = null
        monitoringServiceHealthHandler.removeCallbacks(refreshMonitoringServiceHealth)
        super.onDestroy()
    }

    private fun openNotificationListenerSettings() {
        runCatching {
            startActivity(NotificationListenerPermission.settingsIntent())
        }.getOrElse {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openBillSyncAccessibilitySettings() {
        runCatching {
            startActivity(BillSyncPermission.settingsIntent())
        }.getOrElse {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun requestResultNotificationPermission() {
        if (
            !shouldRequestBookkeepingResultNotificationPermission(
                sdkInt = android.os.Build.VERSION.SDK_INT,
                isGranted = BookkeepingResultNotificationPermission.isGranted(this)
            )
        ) return
        requestResultNotificationPermission.launch(
            BookkeepingResultNotificationPermission.permission
        )
    }

    private fun openBackgroundRunningSettings() {
        startFirstAvailable(BackgroundReliability.backgroundRunningIntents(this))
    }

    private fun openAutoStartSettings() {
        startFirstAvailable(BackgroundReliability.autoStartIntents(this))
    }

    private fun openBatteryOptimizationSettings() {
        startFirstAvailable(BackgroundReliability.batteryOptimizationIntents(this))
    }

    private fun openBatterySaverSettings() {
        startFirstAvailable(BackgroundReliability.batterySaverIntents(this))
    }

    private fun startFirstAvailable(intents: List<Intent>) {
        launchSettingsIntent(
            intents = intents,
            fallback = BackgroundReliability.applicationDetailsIntent(this),
            canResolve = { it.resolveActivity(packageManager) != null },
            launch = ::startActivity
        )
    }

    private fun handleNavigationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_REVIEW, false) == true) {
            pendingEntryNavigationId.value = intent.getStringExtra(EXTRA_PENDING_ENTRY_ID)
            reviewNavigationRequest.value += 1
        }
    }

    private fun launchBillSyncSource(source: BillSyncSource): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(source.packageName)
            ?: return false
        return runCatching {
            startActivity(launchIntent)
            true
        }.getOrDefault(false)
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
    pendingEntryNavigationId: String? = null
) {
    val context = LocalContext.current
    val database = remember {
        AutoAccountingDatabaseProvider.get(context)
    }
    val localLedgerRepository = remember(database) {
        LocalLedgerRepository(database)
    }
    val localPreferencesRepository = remember(database) {
        LocalPreferencesRepository(database)
    }
    val localDataBackupRepository = remember(database) {
        LocalDataBackupRepository(database)
    }
    val localModeSessionStore = remember(context.applicationContext) {
        LocalModeSessionStore(context.applicationContext)
    }
    val reviewQueuePersistence = remember(localLedgerRepository) {
        ReviewQueuePersistence(localLedgerRepository)
    }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val tabs = listOf(
        AppTab.Review,
        AppTab.Ledger,
        AppTab.Reports,
        AppTab.Profile
    )
    val bottomNavigationItems = tabs.map { tab ->
        AppBottomNavigationItem(
            key = tab.name,
            label = tab.label,
            iconRes = tab.iconRes
        )
    }
    var selectedTab by remember { mutableStateOf<AppTab?>(null) }
    var manualEntryOpen by remember { mutableStateOf(false) }
    var profileDestination by remember { mutableStateOf<ProfileDestination?>(null) }
    var accountSession by remember(localModeSessionStore) {
        mutableStateOf(localModeSessionStore.restoreSession())
    }
    var accountDeletionState by remember { mutableStateOf(AccountDeletionUiState()) }
    var continuousMonitoringState by remember { mutableStateOf(ContinuousMonitoringState()) }
    var aiSettings by remember { mutableStateOf(AiCategorizationSettings()) }
    var reviewState by remember { mutableStateOf(ReviewQueueState()) }
    var reviewTransitionInFlight by remember { mutableStateOf(false) }
    var ledgerBooks by remember { mutableStateOf(emptyList<LedgerBookEntity>()) }
    var activeLedgerBook by remember { mutableStateOf<LedgerBookEntity?>(null) }
    var allLedgerEntryEntities by remember { mutableStateOf(emptyList<LedgerEntryEntity>()) }
    var ledgerCategories by remember { mutableStateOf(emptyList<CategoryEntity>()) }
    var fundingAccounts by remember { mutableStateOf(emptyList<FundingAccountEntity>()) }
    var categorizationRules by remember { mutableStateOf(emptyList<CategorizationRule>()) }
    val activeLedgerId = activeLedgerBook?.id
    val activeLedgerName = activeLedgerBook?.name ?: DEFAULT_LEDGER_BOOK_NAME
    val ledgerEntries = allLedgerEntryEntities
        .asSequence()
        .filter { it.ledgerBookId == activeLedgerId && it.deletedAtEpochMillis == null }
        .map { it.toLedgerUiEntry() }
        .toList()
    val deletedLedgerEntries = allLedgerEntryEntities
        .asSequence()
        .filter { it.ledgerBookId == activeLedgerId && it.deletedAtEpochMillis != null }
        .map { it.toLedgerUiEntry() }
        .toList()
    val ledgerBookUiModels = ledgerBooks.map { ledgerBook ->
        val entries = allLedgerEntryEntities.filter { it.ledgerBookId == ledgerBook.id }
        LedgerBookUiModel(
            id = ledgerBook.id,
            name = ledgerBook.name,
            activeEntryCount = entries.count { it.deletedAtEpochMillis == null },
            deletedEntryCount = entries.count { it.deletedAtEpochMillis != null },
            isActive = ledgerBook.id == activeLedgerId
        )
    }
    val continuousMonitoringPermissionHealth = ContinuousMonitoringPermissionHealth(
        billSyncAccessibilityGranted = billSyncAccessibilityAccessGranted,
        billSyncAccessibilityServiceConnected = billSyncAccessibilityServiceConnected
    )

    LaunchedEffect(reviewNavigationRequest) {
        if (reviewNavigationRequest > 0) {
            selectedTab = AppTab.Review
        }
    }

    fun persistReviewTransition(previousState: ReviewQueueState, nextState: ReviewQueueState) {
        if (reviewTransitionInFlight) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("上一项操作正在保存，请稍后重试")
            }
            return
        }
        val previousConfirmedIds =
            previousState.confirmedEntries.mapTo(mutableSetOf()) { it.originPendingId }
        val addsConfirmation = nextState.confirmedEntries.any {
            it.originPendingId !in previousConfirmedIds
        }
        val targetLedgerBookId = activeLedgerBook?.id
        if (addsConfirmation && targetLedgerBookId == null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("当前账本尚未加载，请稍后重试")
            }
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
                    previousState.copy(
                        confirmedEntries = emptyList(),
                        lastAction = null
                    )
                }
                reviewState = persistedState.copy(
                    undoEventSequence = maxOf(
                        persistedState.undoEventSequence,
                        previousState.undoEventSequence
                    )
                )
            }
            reviewTransitionInFlight = false
            if (failure != null) {
                snackbarHostState.showSnackbar("保存待确认操作失败，请重试")
            }
        }
    }

    fun persistReviewState(nextState: ReviewQueueState) {
        persistReviewTransition(reviewState, nextState)
    }

    fun persistCategorizationRules(nextRules: List<CategorizationRule>) {
        categorizationRules = nextRules
        coroutineScope.launch {
            localPreferencesRepository.replaceCategorizationRules(nextRules)
        }
    }

    fun persistAiSettings(nextSettings: AiCategorizationSettings) {
        aiSettings = nextSettings
        coroutineScope.launch {
            localPreferencesRepository.updateAiSettings(nextSettings)
        }
    }

    fun persistContinuousMonitoringState(nextState: ContinuousMonitoringState) {
        continuousMonitoringState = nextState
        coroutineScope.launch {
            localPreferencesRepository.updateContinuousMonitoringState(nextState)
        }
    }

    LaunchedEffect(
        permissionStateLoaded,
        continuousMonitoringPermissionHealth,
        continuousMonitoringState.enabled
    ) {
        if (!permissionStateLoaded || !continuousMonitoringState.enabled) {
            return@LaunchedEffect
        }
        val refreshedState = reduceContinuousMonitoringState(
            continuousMonitoringState,
            ContinuousMonitoringAction.RefreshPermissionHealth(
                continuousMonitoringPermissionHealth
            )
        )
        if (refreshedState != continuousMonitoringState) {
            // Permission health is runtime state; only explicit user actions persist enabled.
            continuousMonitoringState = refreshedState
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
        localLedgerRepository.ledgerBooks.collect { books -> ledgerBooks = books }
    }

    LaunchedEffect(localLedgerRepository) {
        localLedgerRepository.activeLedgerBook.collect { ledgerBook ->
            activeLedgerBook = ledgerBook
        }
    }

    LaunchedEffect(localLedgerRepository) {
        localLedgerRepository.allLedgerEntries.collect { entries ->
            allLedgerEntryEntities = entries
        }
    }

    LaunchedEffect(localLedgerRepository) {
        localLedgerRepository.categories.collect { categories ->
            ledgerCategories = categories
        }
    }

    LaunchedEffect(localLedgerRepository) {
        localLedgerRepository.fundingAccounts.collect { accounts ->
            fundingAccounts = accounts
        }
    }

    LaunchedEffect(localPreferencesRepository) {
        localPreferencesRepository.categorizationRules.collect { rules ->
            categorizationRules = rules
        }
    }

    LaunchedEffect(localPreferencesRepository) {
        localPreferencesRepository.userPreferences.collect { preferences ->
            aiSettings = preferences.aiSettings
            continuousMonitoringState = preferences.continuousMonitoringState
        }
    }

    AutoAccountingTheme {
        SlidePageTransition(
            targetState = accountSession,
            modifier = Modifier.fillMaxSize()
        ) { activeAccountSession ->
            if (activeAccountSession == null) {
                AppWallpaper(R.drawable.aa_bg_account) {
                    AccountScreen(
                        onSessionChange = { session ->
                            if (session == AccountSession.LocalMode) {
                                localModeSessionStore.confirmLocalMode()
                            }
                            accountSession = session
                            selectedTab = if (reviewNavigationRequest > 0) AppTab.Review else null
                            profileDestination = null
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

                BackHandler(
                    enabled = selectedTab == AppTab.Profile && profileDestination != null
                ) {
                    profileDestination = null
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent
                ) {
                    Scaffold(
                        containerColor = Color.Transparent,
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        bottomBar = {
                            if (selectedTab == null) {
                                AppBottomNavigationBar(
                                    items = bottomNavigationItems,
                                    selectedKey = null,
                                    onItemSelected = { key ->
                                        if (!manualEntryOpen) {
                                            selectedTab = tabs.first { it.name == key }
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
                        val route = AppRoute(
                            tab = selectedTab,
                            profileDestination = profileDestination.takeIf {
                                selectedTab == AppTab.Profile
                            },
                            manualEntryOpen = manualEntryOpen
                        )
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
                                        categories = ledgerCategories,
                                        fundingAccounts = fundingAccounts,
                                        onExit = { manualEntryOpen = false },
                                        onCreateEntry = { input ->
                                            val targetLedgerBookId = activeLedgerBook?.id
                                                ?: error("当前账本尚未加载")
                                            localLedgerRepository.createManualEntry(
                                                ledgerBookId = targetLedgerBookId,
                                                input = input
                                            )
                                            manualEntryOpen = false
                                            selectedTab = AppTab.Ledger
                                            profileDestination = null
                                        },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(innerPadding)
                                    )
                                } else {
                                    when (targetRoute.tab) {
                                    null -> HomeScreen(
                                        modifier = Modifier.padding(innerPadding)
                                    )

                                    AppTab.Review -> ReviewQueueScreen(
                                        state = reviewState,
                                        targetLedgerName = activeLedgerName,
                                        onStateChange = ::persistReviewState,
                                        modifier = Modifier.padding(innerPadding),
                                        onCategorizationRuleRequested = { rule ->
                                            persistCategorizationRules(categorizationRules.upsert(rule))
                                        },
                                        accountSession = accountSession,
                                        aiSettings = if (accountDeletionState.cloudWritesAllowed) {
                                            aiSettings
                                        } else {
                                            AiCategorizationSettings()
                                        },
                                        aiCategorizationGateway = DemoAiCategorizationGateway,
                                        billSyncAccessibilityAccessGranted = billSyncAccessibilityAccessGranted,
                                        onOpenBillSyncAccessibilitySettings = onOpenBillSyncAccessibilitySettings,
                                        onLaunchBillSyncSource = onLaunchBillSyncSource,
                                        openPendingEntryId = pendingEntryNavigationId,
                                        openPendingEntryRequestId = reviewNavigationRequest,
                                        continuousMonitoringState = continuousMonitoringState,
                                        continuousMonitoringPermissionHealth = continuousMonitoringPermissionHealth,
                                        onContinuousMonitoringStateChange = ::persistContinuousMonitoringState,
                                        onNavigateHome = {
                                            selectedTab = null
                                            profileDestination = null
                                        }
                                    )

                                    AppTab.Ledger -> LedgerScreen(
                                        entries = ledgerEntries,
                                        deletedEntries = deletedLedgerEntries,
                                        categories = ledgerCategories,
                                        fundingAccounts = fundingAccounts,
                                        ledgerBooks = ledgerBookUiModels,
                                        activeLedgerName = activeLedgerName,
                                        onUpdateEntry = { id, input ->
                                            localLedgerRepository.updateLedgerEntry(id, input)
                                        },
                                        onDeleteEntry = { id ->
                                            localLedgerRepository.moveLedgerEntryToDeleted(id)
                                        },
                                        onRestoreEntry = { id ->
                                            localLedgerRepository.restoreDeletedLedgerEntry(id)
                                        },
                                        onPermanentlyDeleteEntry = { id ->
                                            localLedgerRepository.permanentlyDeleteLedgerEntry(id)
                                        },
                                        onPurgeExpiredEntries = {
                                            localLedgerRepository.purgeExpiredDeletedLedgerEntries()
                                        },
                                        onCreateLedger = { name ->
                                            activeLedgerBook =
                                                localLedgerRepository.createLedgerBook(name)
                                        },
                                        onSelectLedger = { id ->
                                            activeLedgerBook =
                                                localLedgerRepository.selectLedgerBook(id)
                                        },
                                        onDeleteLedger = { id ->
                                            when (val result = localLedgerRepository.deleteLedgerBook(id)) {
                                                DataLedgerBookDeleteResult.Deleted ->
                                                    UiLedgerBookDeleteResult.Deleted

                                                DataLedgerBookDeleteResult.LastLedgerBook ->
                                                    UiLedgerBookDeleteResult.LastLedger

                                                is DataLedgerBookDeleteResult.NotEmpty ->
                                                    UiLedgerBookDeleteResult.NotEmpty(
                                                        activeEntryCount = result.activeEntryCount,
                                                        deletedEntryCount = result.deletedEntryCount
                                                    )

                                                DataLedgerBookDeleteResult.NotFound ->
                                                    error("账本不存在")
                                            }
                                        },
                                        onCreateFundingAccount = { label, paymentSource ->
                                            localLedgerRepository.createFundingAccount(
                                                label = label,
                                                paymentSource = paymentSource
                                            )
                                        },
                                        onUpdateFundingAccount = { id, label, paymentSource ->
                                            localLedgerRepository.updateFundingAccount(
                                                fundingAccountId = id,
                                                label = label,
                                                paymentSource = paymentSource
                                            )
                                        },
                                        onDeleteFundingAccount = { id ->
                                            when (
                                                val result =
                                                    localLedgerRepository.deleteFundingAccount(id)
                                            ) {
                                                DataFundingAccountDeleteResult.Deleted ->
                                                    UiFundingAccountDeleteResult.Deleted

                                                is DataFundingAccountDeleteResult.Referenced ->
                                                    UiFundingAccountDeleteResult.Referenced(
                                                        activeLedgerEntryCount =
                                                            result.activeLedgerEntryCount,
                                                        deletedLedgerEntryCount =
                                                            result.deletedLedgerEntryCount,
                                                        pendingEntryCount =
                                                            result.pendingEntryCount,
                                                        ignoredEntryCount =
                                                            result.ignoredEntryCount
                                                    )

                                                DataFundingAccountDeleteResult.NotFound ->
                                                    error("资金账户不存在")
                                            }
                                        },
                                        showDebugMetadata = BuildConfig.DEBUG,
                                        onNavigateHome = {
                                            selectedTab = null
                                            profileDestination = null
                                        },
                                        modifier = Modifier.padding(innerPadding)
                                    )

                                    AppTab.Reports -> ReportsScreen(
                                        entries = ledgerEntries,
                                        onNavigateHome = {
                                            selectedTab = null
                                            profileDestination = null
                                        },
                                        modifier = Modifier.padding(innerPadding)
                                    )

                                    AppTab.Profile -> when (val destination = targetRoute.profileDestination) {
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
                                            deletionState = accountDeletionState,
                                            onSignInOrRegister = {
                                                profileDestination = null
                                                accountSession = null
                                            },
                                            onSignOut = {
                                                accountSession = signOutToLocalMode(localModeSessionStore)
                                                profileDestination = null
                                            },
                                            onDeletionStateChange = { accountDeletionState = it },
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
                                            onStartManualBillSync = { source ->
                                                startManualBillSync(
                                                    source = source,
                                                    launchSource = onLaunchBillSyncSource
                                                )
                                            },
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
                                            onBack = { profileDestination = null },
                                            modifier = Modifier.padding(innerPadding)
                                        )

                                        ProfileDestination.DataAndBackup -> DataAndBackupScreen(
                                            ledgerEntries = ledgerEntries,
                                            currentLedgerName = activeLedgerName,
                                            onExportEncryptedBackup = { passphrase ->
                                                localDataBackupRepository.exportEncryptedBackup(passphrase)
                                            },
                                            onValidateEncryptedBackup = { backup, passphrase ->
                                                localDataBackupRepository.validateEncryptedBackup(backup, passphrase)
                                            },
                                            onImportEncryptedBackup = { backup, passphrase ->
                                                localDataBackupRepository.importEncryptedBackup(backup, passphrase)
                                                reviewState = ReviewQueueState()
                                            },
                                            onDeleteLocalData = {
                                                reviewState = ReviewQueueState()
                                                categorizationRules = emptyList()
                                                aiSettings = AiCategorizationSettings()
                                                continuousMonitoringState = ContinuousMonitoringState()
                                                allLedgerEntryEntities = emptyList()
                                                ledgerBooks = emptyList()
                                                activeLedgerBook = null
                                                ledgerCategories = emptyList()
                                                fundingAccounts = emptyList()
                                                coroutineScope.launch {
                                                    localLedgerRepository.clearLocalData()
                                                }
                                            },
                                            onBack = { profileDestination = null },
                                            snackbarHostState = snackbarHostState,
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

private enum class AppTab(
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
