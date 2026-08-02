package com.autoaccounting

import androidx.compose.foundation.layout.PaddingValues
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.ui.AutoAccountingAppState
import kotlinx.coroutines.CoroutineScope

internal data class AutoAccountingRouteContext(
    val dependencies: AutoAccountingAppDependencies,
    val runtime: AutoAccountingAppRuntimeState,
    val presentation: AutoAccountingAppPresentation,
    val bindings: AutoAccountingAppBindings,
    val actions: AutoAccountingAppActions,
    val appState: AutoAccountingAppState,
    val coroutineScope: CoroutineScope
)

internal data class AutoAccountingRouteContentContext(
    val context: AutoAccountingRouteContext,
    val activeAccountSession: AccountSession,
    val innerPadding: PaddingValues,
    val onManualEntryClosed: () -> Unit,
    val onNavigateHome: () -> Unit
)
