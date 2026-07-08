package com.autoaccounting

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.room.Room
import com.autoaccounting.data.local.AutoAccountingDatabase
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.account.AccountDeletionUiState
import com.autoaccounting.feature.account.AccountScreen
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.categorization.AiCategorizationGateway
import com.autoaccounting.feature.categorization.AiCategorizationPayload
import com.autoaccounting.feature.categorization.AiCategorizationResponse
import com.autoaccounting.feature.categorization.AiCategorizationSettings
import com.autoaccounting.feature.categorization.CategorizationRule
import com.autoaccounting.feature.categorization.CategorizationRulesScreen
import com.autoaccounting.feature.categorization.applyCategorizationSuggestion
import com.autoaccounting.feature.capture.NotificationCapturePipeline
import com.autoaccounting.feature.capture.PaymentNotificationCaptureBus
import com.autoaccounting.feature.ledger.LedgerUiEntry
import com.autoaccounting.feature.ledger.LedgerScreen
import com.autoaccounting.feature.ledger.ReportsScreen
import com.autoaccounting.feature.ledger.toLedgerUiEntry
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.review.ReviewQueueAction
import com.autoaccounting.feature.review.ReviewQueuePersistence
import com.autoaccounting.feature.review.ReviewQueueScreen
import com.autoaccounting.feature.review.ReviewQueueState
import com.autoaccounting.feature.review.reduceReviewQueue
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoAccountingApp()
        }
    }
}

@Composable
fun AutoAccountingApp() {
    val context = LocalContext.current
    val database = remember {
        Room.databaseBuilder(
            context.applicationContext,
            AutoAccountingDatabase::class.java,
            "auto-accounting.db"
        )
            .addMigrations(
                AutoAccountingDatabase.MIGRATION_1_2,
                AutoAccountingDatabase.MIGRATION_2_3
            )
            .build()
    }
    val localLedgerRepository = remember(database) {
        LocalLedgerRepository(database)
    }
    val localPreferencesRepository = remember(database) {
        LocalPreferencesRepository(database)
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
    val notificationCapturePipeline = remember {
        NotificationCapturePipeline(
            captureTimeFormatter = ::formatNotificationCaptureTime
        )
    }
    val currentReviewState by rememberUpdatedState(reviewState)
    val currentCategorizationRules by rememberUpdatedState(categorizationRules)

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

    DisposableEffect(notificationCapturePipeline, reviewQueuePersistence) {
        PaymentNotificationCaptureBus.setHandler { event ->
            val entry = notificationCapturePipeline.capture(event)
                ?.applyCategorizationSuggestion(currentCategorizationRules)
                ?: return@setHandler
            persistReviewTransition(
                currentReviewState,
                reduceReviewQueue(
                    currentReviewState,
                    ReviewQueueAction.AddPending(entry)
                )
            )
        }
        onDispose {
            PaymentNotificationCaptureBus.clearHandler()
        }
    }

    MaterialTheme {
        if (accountSession == null) {
            AccountScreen(onSessionChange = { accountSession = it })
            return@MaterialTheme
        }

        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
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
                        categorizationRules = categorizationRules,
                        accountSession = accountSession,
                        aiSettings = if (accountDeletionState.cloudWritesAllowed) {
                            aiSettings
                        } else {
                            AiCategorizationSettings()
                        },
                        aiCategorizationGateway = DemoAiCategorizationGateway,
                        continuousMonitoringState = continuousMonitoringState,
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
                        reviewState = reviewState,
                        onRestoreLocalData = { snapshot ->
                            reviewState = snapshot.reviewState
                            persistCategorizationRules(snapshot.categorizationRules)
                            persistAiSettings(snapshot.aiSettings)
                            persistContinuousMonitoringState(snapshot.continuousMonitoringState)
                        },
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
                        accountSession = accountSession,
                        accountDeletionState = accountDeletionState,
                        onAccountDeletionStateChange = { next ->
                            accountDeletionState = next
                        },
                        continuousMonitoringState = continuousMonitoringState,
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

private fun formatNotificationCaptureTime(epochMillis: Long): String {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
}
