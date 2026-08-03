package com.autoaccounting.feature.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.autoaccounting.feature.compliance.ComplianceMaterialsScreen
import com.autoaccounting.ui.components.SlidePageTransition
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(
    modifier: Modifier = Modifier,
    initialState: AccountUiState = AccountUiState(),
    accountRepository: AccountRepository,
    persistSession: (AccountCredentials) -> Boolean = { true },
    clearPersistedSession: () -> Boolean = { true },
    wechatAuthGateway: WechatAuthGateway? = null,
    wechatAuthCallback: WechatAuthCallback? = null,
    onWechatAuthCallbackConsumed: () -> Unit = {},
    avatarCacheOverride: WechatAvatarCache? = null,
    onSessionChange: (AccountSession) -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    var state by remember { mutableStateOf(initialState) }
    val showComplianceMaterials = remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val avatarCache = avatarCacheOverride ?: rememberWechatAvatarCache()
    val wechatController = remember(accountRepository, wechatAuthGateway) {
        wechatAuthGateway?.let { gateway ->
            WechatLoginController(
                accountRepository = accountRepository,
                authCoordinator = WechatAuthCoordinator(gateway),
                persistSession = persistSession,
                clearPersistedSession = clearPersistedSession,
                onSignedIn = onSessionChange
            )
        }
    }

    fun dispatch(action: AccountAction) {
        val validated = reduceAccountState(state, action)
        if (!action.isNetworkAction()) {
            state = validated
            validated.session?.let(onSessionChange)
            return
        }
        if (state.operationInProgress || !validated.isReadyFor(action)) {
            state = validated
            return
        }
        state = validated.copy(operationInProgress = true)
        coroutineScope.launch {
            val completed = validated.runNetworkAction(action, accountRepository, persistSession, clearPersistedSession)
                .copy(operationInProgress = false)
            state = completed
            completed.session?.let(onSessionChange)
        }
    }

    AccountBackHandler(
        showComplianceMaterials = showComplianceMaterials,
        wechatController = wechatController,
        flow = state.flow,
        onFlowBack = { flow ->
            dispatch(if (flow == AccountFlow.Recovery) AccountAction.ShowLogin else AccountAction.BackToLanding)
        },
        onBack = onBack
    )

    LaunchedEffect(state.flow) { scrollState.scrollTo(0) }

    LaunchedEffect(wechatAuthCallback, wechatController) {
        if (wechatAuthCallback != null && wechatController != null) {
            wechatController.handleCallback(wechatAuthCallback)
            onWechatAuthCallbackConsumed()
        }
    }

    AccountScreenEffects(
        state = state,
        snackbarHostState = snackbarHostState,
        wechatController = wechatController,
        onDispatch = ::dispatch
    )

    SlidePageTransition(
        targetState = currentAccountPage(
            showComplianceMaterials = showComplianceMaterials.value,
            wechatController = wechatController,
            flow = state.flow
        ),
        modifier = modifier.fillMaxSize()
    ) { targetPage ->
        when (targetPage) {
            AccountPage.Compliance -> ComplianceMaterialsScreen(
                onBack = { showComplianceMaterials.value = false }
            )

            AccountPage.Wechat -> WechatLoginFlowPage(
                controller = requireNotNull(wechatController),
                avatarCache = avatarCache,
                snackbarHostState = snackbarHostState,
                scrollState = scrollState,
                modifier = Modifier.fillMaxSize()
            )

            is AccountPage.Flow -> AccountFlowPageContent(
                args = AccountFlowPageArgs(
                    state = state,
                    onAction = ::dispatch,
                    wechatEnabled = wechatController != null,
                    onWechatClick = { wechatController?.start(state.agreementAccepted) },
                    onComplianceClick = { showComplianceMaterials.value = true }
                ),
                snackbarHostState = snackbarHostState,
                scrollState = scrollState,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun AccountBackHandler(
    showComplianceMaterials: MutableState<Boolean>,
    wechatController: WechatLoginController?,
    flow: AccountFlow,
    onFlowBack: (AccountFlow) -> Unit,
    onBack: (() -> Unit)?
) {
    val complianceOpen = showComplianceMaterials.value
    val wechatPageActive = wechatController?.state?.page?.let { it != WechatLoginPage.Idle } == true
    BackHandler(
        enabled = complianceOpen || wechatPageActive ||
            flow != AccountFlow.Landing || onBack != null
    ) {
        when {
            complianceOpen -> showComplianceMaterials.value = false
            wechatPageActive -> wechatController?.back()
            flow != AccountFlow.Landing -> onFlowBack(flow)
            else -> onBack?.invoke()
        }
    }
}

@Composable
private fun AccountScreenEffects(
    state: AccountUiState,
    snackbarHostState: SnackbarHostState,
    wechatController: WechatLoginController?,
    onDispatch: (AccountAction) -> Unit
) {
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(wechatController?.state?.errorMessage) {
        wechatController?.state?.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(state.smsCountdownSeconds) {
        if (state.smsCountdownSeconds > 0) {
            delay(1_000)
            onDispatch(AccountAction.TickSmsCountdown)
        }
    }
}
