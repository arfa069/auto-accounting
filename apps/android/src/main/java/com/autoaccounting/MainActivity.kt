package com.autoaccounting

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
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
import com.autoaccounting.data.local.AutoAccountingDatabaseProvider
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.account.AccountDeletionUiState
import com.autoaccounting.feature.account.AccountScreen
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.billsync.BillSyncPermission
import com.autoaccounting.feature.billsync.BillSyncSource
import com.autoaccounting.feature.categorization.AiCategorizationGateway
import com.autoaccounting.feature.categorization.AiCategorizationPayload
import com.autoaccounting.feature.categorization.AiCategorizationResponse
import com.autoaccounting.feature.categorization.AiCategorizationSettings
import com.autoaccounting.feature.categorization.CategorizationRule
import com.autoaccounting.feature.categorization.CategorizationRulesScreen
import com.autoaccounting.feature.capture.NotificationListenerPermission
import com.autoaccounting.feature.ledger.LedgerUiEntry
import com.autoaccounting.feature.ledger.LedgerScreen
import com.autoaccounting.feature.ledger.ReportsScreen
import com.autoaccounting.feature.ledger.toLedgerUiEntry
import com.autoaccounting.feature.monitoring.ContinuousMonitoringAction
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.monitoring.reduceContinuousMonitoringState
import com.autoaccounting.feature.review.ReviewQueuePersistence
import com.autoaccounting.feature.review.ReviewQueueScreen
import com.autoaccounting.feature.review.ReviewQueueState
import com.autoaccounting.feature.settings.LocalDataBackupRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val notificationListenerAccessGranted = mutableStateOf(false)
    private val billSyncAccessibilityAccessGranted = mutableStateOf(false)
    private val permissionStateLoaded = mutableStateOf(false)

    private var pendingImportCallback: ((Uri) -> Unit)? = null

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { pendingImportCallback?.invoke(it) }
        pendingImportCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoAccountingApp(
                notificationListenerAccessGranted = notificationListenerAccessGranted.value,
                onOpenNotificationListenerSettings = ::openNotificationListenerSettings,
                billSyncAccessibilityAccessGranted = billSyncAccessibilityAccessGranted.value,
                onOpenBillSyncAccessibilitySettings = ::openBillSyncAccessibilitySettings,
                onLaunchBillSyncSource = ::launchBillSyncSource,
                permissionStateLoaded = permissionStateLoaded.value,
                onSaveBackupToDownloads = ::saveBackupToDownloads,
                onPickBackupFile = ::pickBackupFile,
                onReadBackupFile = ::readBackupFile
            )
        }
    }

    override fun onResume() {
        super.onResume()
        notificationListenerAccessGranted.value =
            NotificationListenerPermission.isGranted(this)
        billSyncAccessibilityAccessGranted.value = BillSyncPermission.isGranted(this)
        permissionStateLoaded.value = true
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

    private fun launchBillSyncSource(source: BillSyncSource): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(source.packageName)
            ?: return false
        return runCatching {
            startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    /**
     * Save encrypted backup content directly to the public Downloads directory
     * using MediaStore. Returns the display filename on success.
     */
    private fun saveBackupToDownloads(backupContent: String): String {
        val timestamp = SimpleDateFormat(
            "yyyy-MM-dd-HH-mm", Locale.US
        ).format(Date())
        val filename = "$timestamp-ac-backup.bak"

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS
            )
        }

        val resolver = contentResolver
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: throw IllegalStateException("Failed to create Downloads entry")

        resolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(backupContent.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Failed to write backup file")

        return filename
    }

    /**
     * Launch SAF file picker for selecting a .bak backup file.
     * The callback will be invoked with the selected Uri.
     */
    private fun pickBackupFile(callback: (Uri) -> Unit) {
        pendingImportCallback = callback
        openDocumentLauncher.launch(arrayOf("application/octet-stream", "*/*"))
    }

    /**
     * Read backup file content from a SAF Uri.
     */
    private fun readBackupFile(uri: Uri): String {
        return contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IllegalStateException("Failed to read backup file")
    }
}

@Composable
fun AutoAccountingApp(
    notificationListenerAccessGranted: Boolean = false,
    onOpenNotificationListenerSettings: () -> Unit = {},
    billSyncAccessibilityAccessGranted: Boolean = false,
    onOpenBillSyncAccessibilitySettings: () -> Unit = {},
    onLaunchBillSyncSource: (BillSyncSource) -> Boolean = { false },
    permissionStateLoaded: Boolean = false,
    onSaveBackupToDownloads: (String) -> String = { "" },
    onPickBackupFile: ((android.net.Uri) -> Unit) -> Unit = {},
    onReadBackupFile: (android.net.Uri) -> String = { "" }
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
    var accountSession by remember { mutableStateOf<AccountSession?>(null) }
    var accountDeletionState by remember { mutableStateOf(AccountDeletionUiState()) }
    var continuousMonitoringState by remember { mutableStateOf(ContinuousMonitoringState()) }
    var aiSettings by remember { mutableStateOf(AiCategorizationSettings()) }
    var reviewState by remember { mutableStateOf(ReviewQueueState()) }
    var ledgerEntries by remember { mutableStateOf(emptyList<LedgerUiEntry>()) }
    var categorizationRules by remember { mutableStateOf(emptyList<CategorizationRule>()) }
    val continuousMonitoringPermissionHealth = ContinuousMonitoringPermissionHealth(
        notificationListenerGranted = notificationListenerAccessGranted,
        billSyncAccessibilityGranted = billSyncAccessibilityAccessGranted
    )

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
        localLedgerRepository.ledgerEntries.collect { entries ->
            ledgerEntries = entries.map { it.toLedgerUiEntry() }
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
        if (accountSession == null) {
            AccountScreen(onSessionChange = { accountSession = it })
            return@MaterialTheme
        }

        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
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
                        continuousMonitoringState = continuousMonitoringState,
                        continuousMonitoringPermissionHealth = continuousMonitoringPermissionHealth,
                        onContinuousMonitoringStateChange = ::persistContinuousMonitoringState
                    )

                    AppTab.Ledger -> LedgerScreen(
                        entries = ledgerEntries,
                        modifier = Modifier.padding(innerPadding)
                    )

                    AppTab.Reports -> ReportsScreen(
                        entries = ledgerEntries,
                        modifier = Modifier.padding(innerPadding)
                    )

                    AppTab.Profile -> CategorizationRulesScreen(
                        rules = categorizationRules,
                        onRulesChange = ::persistCategorizationRules,
                        modifier = Modifier.padding(innerPadding),
                        showPermissionCenter = true,
                        aiSettings = aiSettings,
                        onAiSettingsChange = ::persistAiSettings,
                        ledgerEntries = ledgerEntries,
                        onExportEncryptedBackup = { passphrase ->
                            localDataBackupRepository.exportEncryptedBackup(passphrase)
                        },
                        onImportEncryptedBackup = { backup, passphrase ->
                            localDataBackupRepository.importEncryptedBackup(
                                backup,
                                passphrase
                            )
                            reviewState = ReviewQueueState()
                        },
                        snackbarHostState = snackbarHostState,
                        onSaveBackupToDownloads = onSaveBackupToDownloads,
                        onPickBackupFile = onPickBackupFile,
                        onReadBackupFile = onReadBackupFile,
                        onDeleteLocalData = {
                            reviewState = ReviewQueueState()
                            categorizationRules = emptyList()
                            aiSettings = AiCategorizationSettings()
                            continuousMonitoringState = ContinuousMonitoringState()
                            ledgerEntries = emptyList()
                            coroutineScope.launch {
                                localLedgerRepository.clearLocalData()
                                localPreferencesRepository.clearLocalData()
                            }
                        },
                        notificationListenerAccessGranted = notificationListenerAccessGranted,
                        onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                        billSyncAccessibilityAccessGranted = billSyncAccessibilityAccessGranted,
                        onOpenBillSyncAccessibilitySettings = onOpenBillSyncAccessibilitySettings,
                        accountSession = accountSession,
                        accountDeletionState = accountDeletionState,
                        onAccountDeletionStateChange = { next ->
                            accountDeletionState = next
                        },
                        continuousMonitoringState = continuousMonitoringState,
                        continuousMonitoringPermissionHealth = continuousMonitoringPermissionHealth,
                        onContinuousMonitoringStateChange = ::persistContinuousMonitoringState
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
