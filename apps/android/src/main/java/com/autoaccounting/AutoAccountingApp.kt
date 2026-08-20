package com.autoaccounting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.autoaccounting.feature.account.AccountCredentials
import com.autoaccounting.feature.account.AccountRepository
import com.autoaccounting.feature.account.WechatAuthCallback
import com.autoaccounting.feature.account.WechatAuthGateway
import com.autoaccounting.feature.billsync.BillSyncSource
import com.autoaccounting.feature.categorization.AiCategorizationGateway
import com.autoaccounting.feature.categorization.CloudAiSettingsGateway
import com.autoaccounting.ui.components.SlidePageTransition
import com.autoaccounting.ui.rememberAutoAccountingAppState
import com.autoaccounting.ui.theme.AutoAccountingTheme
import com.autoaccounting.ui.visual.AppWallpaper

data class AutoAccountingAppBindings(
    val billSyncAccessibilityAccessGranted: Boolean = false,
    val billSyncAccessibilityServiceConnected: Boolean = true,
    val onOpenBillSyncAccessibilitySettings: () -> Unit = {},
    val onLaunchBillSyncSource: (BillSyncSource) -> Boolean = { false },
    val wechatAuthCallback: WechatAuthCallback? = null,
    val onWechatAuthCallbackConsumed: () -> Unit = {}
)

data class AutoAccountingAppOverrides(
    val accountRepository: AccountRepository? = null,
    val aiCategorizationGateway: AiCategorizationGateway? = null,
    val cloudAiSettingsGateway: CloudAiSettingsGateway? = null,
    val persistAccountSession: ((AccountCredentials) -> Boolean)? = null,
    val wechatAuthGateway: WechatAuthGateway? = null
)

@Composable
fun AutoAccountingApp(
    bindings: AutoAccountingAppBindings = AutoAccountingAppBindings(),
    overrides: AutoAccountingAppOverrides = AutoAccountingAppOverrides()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val appState = rememberAutoAccountingAppState()
    val dependencies = rememberAutoAccountingAppDependencies(overrides)
    val runtime = rememberAutoAccountingAppRuntimeState()
    val actions = androidx.compose.runtime.remember(dependencies, runtime, appState, coroutineScope) {
        AutoAccountingAppActions(
            dependencies = dependencies,
            runtime = runtime,
            appState = appState,
            coroutineScope = coroutineScope
        )
    }
    val presentation = rememberAutoAccountingAppPresentation(runtime)
    val routeContext = AutoAccountingRouteContext(
        dependencies = dependencies,
        runtime = runtime,
        presentation = presentation,
        bindings = bindings,
        actions = actions,
        appState = appState,
        coroutineScope = coroutineScope
    )

    AutoAccountingAppEffects(
        context = AutoAccountingAppEffectsContext(
            dependencies = dependencies,
            bindings = bindings,
            runtime = runtime,
            actions = actions,
            appState = appState,
            lifecycleOwner = lifecycleOwner,
            presentation = presentation
        )
    )

    AutoAccountingTheme {
        if (runtime.isRestoringAccountSession) {
            AppWallpaper(R.drawable.aa_bg_account) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.testTag("account-session-restoring"))
                }
            }
        } else {
            SlidePageTransition(
                targetState = runtime.accountSession,
                modifier = Modifier.fillMaxSize()
            ) { activeAccountSession ->
                if (activeAccountSession == null) {
                    AutoAccountingAccountEntry(routeContext)
                } else {
                    AutoAccountingRouteHost(routeContext)
                }
            }
        }
    }
    AutoAccountingLedgerSyncAccountSwitchDialog(routeContext)
}
