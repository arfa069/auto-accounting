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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.autoaccounting.data.local.AutoAccountingDatabaseProvider
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.FundingAccountEntity
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
import com.autoaccounting.feature.capture.NotificationListenerPermission
import com.autoaccounting.feature.capture.BookkeepingResultNotificationPermission
import com.autoaccounting.feature.ledger.LedgerUiEntry
import com.autoaccounting.feature.ledger.LedgerScreen
import com.autoaccounting.feature.ledger.ReportsScreen
import com.autoaccounting.feature.ledger.toLedgerUiEntry
import com.autoaccounting.feature.monitoring.ContinuousMonitoringAction
import com.autoaccounting.feature.monitoring.AutomaticBookkeepingScreen
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.monitoring.ContinuousMonitoringServiceHealth
import com.autoaccounting.feature.monitoring.SERVICE_HEARTBEAT_INTERVAL_MILLIS
import com.autoaccounting.feature.monitoring.reduceContinuousMonitoringState
import com.autoaccounting.feature.profile.AccountManagementScreen
import com.autoaccounting.feature.profile.ProfileDestination
import com.autoaccounting.feature.profile.ProfileOverviewScreen
import com.autoaccounting.feature.review.ReviewQueuePersistence
import com.autoaccounting.feature.review.ReviewQueueScreen
import com.autoaccounting.feature.review.ReviewQueueState
import com.autoaccounting.feature.settings.LocalDataBackupRepository
import com.autoaccounting.feature.settings.DataAndBackupScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationListenerAccessGranted = mutableStateOf(false)
    private val billSyncAccessibilityAccessGranted = mutableStateOf(false)
    private val billSyncAccessibilityServiceConnected = mutableStateOf(false)
    private val resultNotificationPermissionGranted = mutableStateOf(false)
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
        requestResultNotificationPermission.launch(
            BookkeepingResultNotificationPermission.permission
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
    val tabs = listOf(
        AppTab.Review,
        AppTab.Ledger,
        AppTab.Reports,
        AppTab.Profile
    )
    var selectedTab by remember { mutableStateOf(AppTab.Review) }
    var profileDestination by remember { mutableStateOf<ProfileDestination?>(null) }
    var accountSession by remember(localModeSessionStore) {
        mutableStateOf(localModeSessionStore.restoreSession())
    }
    var accountDeletionState by remember { mutableStateOf(AccountDeletionUiState()) }
    var continuousMonitoringState by remember { mutableStateOf(ContinuousMonitoringState()) }
    var aiSettings by remember { mutableStateOf(AiCategorizationSettings()) }
    var reviewState by remember { mutableStateOf(ReviewQueueState()) }
    var ledgerEntries by remember { mutableStateOf(emptyList<LedgerUiEntry>()) }
    var deletedLedgerEntries by remember { mutableStateOf(emptyList<LedgerUiEntry>()) }
    var ledgerCategories by remember { mutableStateOf(emptyList<CategoryEntity>()) }
    var fundingAccounts by remember { mutableStateOf(emptyList<FundingAccountEntity>()) }
    var categorizationRules by remember { mutableStateOf(emptyList<CategorizationRule>()) }
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
        reviewState = nextState
        coroutineScope.launch {
            reviewQueuePersistence.persistTransition(previousState, nextState)
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
            persistContinuousMonitoringState(refreshedState)
        }
    }

    LaunchedEffect(localLedgerRepository) {
        localLedgerRepository.seedSystemCategories()
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
        localLedgerRepository.purgeExpiredDeletedLedgerEntries()
        localLedgerRepository.ledgerEntries.collect { entries ->
            ledgerEntries = entries.map { it.toLedgerUiEntry() }
        }
    }

    LaunchedEffect(localLedgerRepository) {
        localLedgerRepository.deletedLedgerEntries.collect { entries ->
            deletedLedgerEntries = entries.map { it.toLedgerUiEntry() }
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

    val snackbarHostState = remember { SnackbarHostState() }

    MaterialTheme {
        val activeAccountSession = accountSession
        if (activeAccountSession == null) {
            AccountScreen(
                onSessionChange = { session ->
                    if (session == AccountSession.LocalMode) {
                        localModeSessionStore.confirmLocalMode()
                    }
                    accountSession = session
                }
            )
            return@MaterialTheme
        }

        BackHandler(
            enabled = selectedTab == AppTab.Profile && profileDestination != null
        ) {
            profileDestination = null
        }

        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = {
                                    selectedTab = tab
                                    profileDestination = null
                                },
                                modifier = Modifier.testTag("app-tab-${tab.name}"),
                                icon = { Text(tab.symbol) },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                when (selectedTab) {
                    AppTab.Review -> ReviewQueueScreen(
                        state = reviewState,
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
                        onContinuousMonitoringStateChange = ::persistContinuousMonitoringState
                    )

                    AppTab.Ledger -> LedgerScreen(
                        entries = ledgerEntries,
                        deletedEntries = deletedLedgerEntries,
                        categories = ledgerCategories,
                        fundingAccounts = fundingAccounts,
                        onCreateEntry = { input ->
                            localLedgerRepository.createManualEntry(input)
                        },
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
                        modifier = Modifier.padding(innerPadding)
                    )

                    AppTab.Reports -> ReportsScreen(
                        entries = ledgerEntries,
                        modifier = Modifier.padding(innerPadding)
                    )

                    AppTab.Profile -> when (val destination = profileDestination) {
                        null -> ProfileOverviewScreen(
                            session = activeAccountSession,
                            onDestinationSelected = { profileDestination = it },
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
                                ledgerEntries = emptyList()
                                deletedLedgerEntries = emptyList()
                                ledgerCategories = emptyList()
                                fundingAccounts = emptyList()
                                coroutineScope.launch {
                                    localLedgerRepository.clearLocalData()
                                    localPreferencesRepository.clearLocalData()
                                }
                            },
                            onBack = { profileDestination = null },
                            snackbarHostState = snackbarHostState,
                            modifier = Modifier.padding(innerPadding)
                        )

                        else -> CategorizationRulesScreen(
                            rules = categorizationRules,
                            onRulesChange = ::persistCategorizationRules,
                            modifier = Modifier.padding(innerPadding),
                            showPermissionCenter = true,
                            aiSettings = aiSettings,
                            onAiSettingsChange = ::persistAiSettings,
                            accountSession = activeAccountSession,
                            accountDeletionState = accountDeletionState,
                            onAccountDeletionStateChange = { next ->
                                accountDeletionState = next
                            }
                        )
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
    val symbol: String
) {
    Review(
        label = "待确认",
        symbol = "✓"
    ),
    Ledger(
        label = "账本",
        symbol = "账"
    ),
    Reports(
        label = "报表",
        symbol = "%"
    ),
    Profile(
        label = "我的",
        symbol = "我"
    )
}

private fun List<CategorizationRule>.upsert(rule: CategorizationRule): List<CategorizationRule> {
    return if (any { it.id == rule.id }) {
        map { existing -> if (existing.id == rule.id) rule else existing }
    } else {
        this + rule
    }
}
