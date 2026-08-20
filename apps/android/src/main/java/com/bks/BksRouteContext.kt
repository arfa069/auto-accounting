package com.bks

import androidx.compose.foundation.layout.PaddingValues
import com.bks.feature.account.AccountSession
import com.bks.ui.BksAppState
import kotlinx.coroutines.CoroutineScope

internal data class BksRouteContext(
    val dependencies: BksAppDependencies,
    val runtime: BksAppRuntimeState,
    val presentation: BksAppPresentation,
    val bindings: BksAppBindings,
    val actions: BksAppActions,
    val appState: BksAppState,
    val coroutineScope: CoroutineScope
)

internal data class BksRouteContentContext(
    val context: BksRouteContext,
    val activeAccountSession: AccountSession,
    val innerPadding: PaddingValues,
    val onManualEntryClosed: () -> Unit,
    val onNavigateHome: () -> Unit
)
