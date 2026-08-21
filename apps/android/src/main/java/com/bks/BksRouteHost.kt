package com.bks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.bks.feature.account.AccountSession
import com.bks.feature.account.AccountScreen
import com.bks.feature.home.HomeScreen
import com.bks.feature.ledger.ManualLedgerEntryScreen
import com.bks.feature.profile.ProfileDestination
import com.bks.ui.components.AppBottomNavigationBar
import com.bks.ui.components.SlidePageTransition
import com.bks.ui.visual.AppWallpaper

@Composable
internal fun BksAccountEntry(context: BksRouteContext) {
    val runtime = context.runtime
    val appState = context.appState
    val dependencies = context.dependencies
    val actions = context.actions
    val returnSession = runtime.accountEntryReturnSession

    AppWallpaper(R.drawable.aa_bg_account) {
        AccountScreen(
            accountRepository = dependencies.account.accountRepository,
            persistSession = { credentials ->
                val saved = actions.persistAccountSession(credentials)
                if (saved) actions.applyVerifiedCredentials(credentials)
                saved
            },
            clearPersistedSession = dependencies.account.secureAccountSessionStore::clear,
            wechatAuthGateway = dependencies.account.wechatAuthGateway,
            wechatAuthCallback = context.bindings.wechatAuthCallback,
            onWechatAuthCallbackConsumed = context.bindings.onWechatAuthCallbackConsumed,
            avatarCacheOverride = dependencies.account.wechatAvatarCache,
            onSessionChange = { session ->
                if (session == AccountSession.LocalMode) {
                    dependencies.account.localModeSessionStore.confirmLocalMode()
                    runtime.accountRuntimeState =
                        com.bks.feature.account.AccountRuntimeState(
                            com.bks.feature.account.AccountRuntimeStatus.LocalMode
                        )
                }
                runtime.accountSession = session
                runtime.accountEntryReturnSession = null
                appState.selectedTab.value = null
                appState.profileDestination.value = null
            },
            onBack = returnSession?.let {
                {
                    runtime.accountSession = it
                    runtime.accountEntryReturnSession = null
                    appState.selectedTab.value = AppTab.Profile
                    appState.profileDestination.value = ProfileDestination.AccountManagement
                }
            }
        )
    }
}

@Composable
internal fun BksRouteHost(context: BksRouteContext) {
    val runtime = context.runtime
    val presentation = context.presentation
    val bindings = context.bindings
    val dependencies = context.dependencies
    val actions = context.actions
    val appState = context.appState
    val activeAccountSession = runtime.accountSession ?: return
    var selectedTab by appState.selectedTab
    var manualEntryOpen by appState.manualEntryOpen
    var profileDestination by appState.profileDestination

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
                            if (!manualEntryOpen) manualEntryOpen = true
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
            val wallpaperRes = when {
                route.manualEntryOpen -> R.drawable.aa_bg_ledger
                route.tab == null -> R.drawable.aa_bg_account
                route.profileDestination != null -> R.drawable.aa_bg_neutral
                else -> route.tab.backgroundRes
            }
            AppWallpaper(wallpaperRes) {
                SlidePageTransition(
                    targetState = route,
                    modifier = Modifier.fillMaxSize()
                ) { targetRoute ->
                    BksRouteContent(
                        contentContext = BksRouteContentContext(
                            context = context,
                            activeAccountSession = activeAccountSession,
                            innerPadding = innerPadding,
                            onManualEntryClosed = { manualEntryOpen = false },
                            onNavigateHome = {
                                selectedTab = null
                                profileDestination = null
                            }
                        ),
                        targetRoute = targetRoute,
                    )
                }
            }
        }
    }
}

@Composable
private fun BksRouteContent(
    contentContext: BksRouteContentContext,
    targetRoute: AppRoute
) {
    val context = contentContext.context
    if (targetRoute.manualEntryOpen) {
        BksManualEntryRoute(
            context = context,
            innerPadding = contentContext.innerPadding,
            onManualEntryClosed = contentContext.onManualEntryClosed
        )
        return
    }

    BksPrimaryTabRoute(
        context = context,
        targetRoute = targetRoute,
        activeAccountSession = contentContext.activeAccountSession,
        innerPadding = contentContext.innerPadding,
        onNavigateHome = contentContext.onNavigateHome
    )
}

@Composable
private fun BksManualEntryRoute(
    context: BksRouteContext,
    innerPadding: PaddingValues,
    onManualEntryClosed: () -> Unit
) {
    val runtime = context.runtime
    val appState = context.appState
    ManualLedgerEntryScreen(
        categories = runtime.ledgerState.categories,
        fundingAccounts = runtime.ledgerState.fundingAccounts,
        onExit = onManualEntryClosed,
        onCreateEntry = { input ->
            val targetLedgerBookId = runtime.ledgerState.activeLedgerBook?.id
                ?: error("当前账本尚未加载")
            context.dependencies.local.ledgerRepository.createManualEntry(
                ledgerBookId = targetLedgerBookId,
                input = input
            )
            onManualEntryClosed()
            appState.selectedTab.value = AppTab.Ledger
            appState.profileDestination.value = null
        },
        modifier = Modifier.fillMaxSize().padding(innerPadding)
    )
}

@Composable
internal fun BksPrimaryTabRoute(
    context: BksRouteContext,
    targetRoute: AppRoute,
    activeAccountSession: AccountSession,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    when (targetRoute.tab) {
        null -> HomeScreen(modifier = Modifier.padding(innerPadding))
        AppTab.Review -> BksReviewRoute(context, innerPadding, onNavigateHome)
        AppTab.Ledger -> BksLedgerRoute(context, innerPadding, onNavigateHome)
        AppTab.Reports -> BksReportsRoute(context, innerPadding, onNavigateHome)
        AppTab.Profile -> BksProfileRoute(
            context = context,
            activeAccountSession = activeAccountSession,
            innerPadding = innerPadding,
            destination = targetRoute.profileDestination,
            onNavigateHome = onNavigateHome
        )
    }
}
