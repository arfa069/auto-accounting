package com.bks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bks.feature.account.AccountCredentials
import com.bks.feature.account.AccountRepository
import com.bks.feature.account.WechatAuthCallback
import com.bks.feature.account.WechatAuthGateway
import com.bks.feature.billsync.BillSyncSource
import com.bks.feature.categorization.AiCategorizationGateway
import com.bks.feature.categorization.CloudAiSettingsGateway
import com.bks.ui.components.SlidePageTransition
import com.bks.ui.rememberBksAppState
import com.bks.ui.theme.BksTheme
import com.bks.ui.visual.AppWallpaper

data class BksAppBindings(
    val billSyncAccessibilityAccessGranted: Boolean = false,
    val billSyncAccessibilityServiceConnected: Boolean = true,
    val onOpenBillSyncAccessibilitySettings: () -> Unit = {},
    val onLaunchBillSyncSource: (BillSyncSource) -> Boolean = { false },
    val wechatAuthCallback: WechatAuthCallback? = null,
    val onWechatAuthCallbackConsumed: () -> Unit = {}
)

data class BksAppOverrides(
    val accountRepository: AccountRepository? = null,
    val aiCategorizationGateway: AiCategorizationGateway? = null,
    val cloudAiSettingsGateway: CloudAiSettingsGateway? = null,
    val persistAccountSession: ((AccountCredentials) -> Boolean)? = null,
    val wechatAuthGateway: WechatAuthGateway? = null
)

@Composable
fun BksApp(
    bindings: BksAppBindings = BksAppBindings(),
    overrides: BksAppOverrides = BksAppOverrides()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val appState = rememberBksAppState()
    val dependencies = rememberBksAppDependencies(overrides)
    val runtime = rememberBksAppRuntimeState()
    val actions = androidx.compose.runtime.remember(dependencies, runtime, appState, coroutineScope) {
        BksAppActions(
            dependencies = dependencies,
            runtime = runtime,
            appState = appState,
            coroutineScope = coroutineScope
        )
    }
    val presentation = rememberBksAppPresentation(runtime)
    val routeContext = BksRouteContext(
        dependencies = dependencies,
        runtime = runtime,
        presentation = presentation,
        bindings = bindings,
        actions = actions,
        appState = appState,
        coroutineScope = coroutineScope
    )

    BksAppEffects(
        context = BksAppEffectsContext(
            dependencies = dependencies,
            bindings = bindings,
            runtime = runtime,
            actions = actions,
            appState = appState,
            lifecycleOwner = lifecycleOwner,
            presentation = presentation
        )
    )

    BksTheme {
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
                    BksAccountEntry(routeContext)
                } else {
                    BksRouteHost(routeContext)
                }
            }
        }
    }
    BksLedgerSyncAccountSwitchDialog(routeContext)
}
