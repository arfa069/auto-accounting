package com.bks

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bks.feature.account.AccountManagementScreen
import com.bks.feature.account.AccountRuntimeState
import com.bks.feature.account.AccountRuntimeStatus
import com.bks.feature.account.AccountSession
import com.bks.feature.categorization.CategorizationAiUiState
import com.bks.feature.categorization.CategorizationRulesActions
import com.bks.feature.categorization.CategorizationRulesScreen
import com.bks.feature.compliance.ComplianceAndPrivacyScreen
import com.bks.feature.monitoring.AutomaticBookkeepingScreen
import com.bks.feature.profile.ProfileDestination
import com.bks.feature.profile.ProfileOverviewScreen
import com.bks.feature.settings.DataAndBackupScreen

@Composable
internal fun BksProfileRoute(
    context: BksRouteContext,
    activeAccountSession: AccountSession,
    innerPadding: PaddingValues,
    destination: ProfileDestination?,
    onNavigateHome: () -> Unit
) {
    when (destination) {
        null -> BksProfileOverviewRoute(context, activeAccountSession, innerPadding, onNavigateHome)
        ProfileDestination.AccountManagement -> BksAccountManagementRoute(context, activeAccountSession, innerPadding)
        ProfileDestination.AutomaticBookkeeping -> BksAutomaticBookkeepingRoute(context, innerPadding)
        ProfileDestination.CategorizationRules -> BksCategorizationRoute(context, innerPadding)
        ProfileDestination.DataAndBackup -> BksDataAndBackupRoute(context, innerPadding)
        ProfileDestination.ComplianceAndPrivacy -> BksComplianceRoute(context, innerPadding)
    }
}

@Composable
private fun BksProfileOverviewRoute(
    context: BksRouteContext,
    session: AccountSession,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    ProfileOverviewScreen(
        session = session,
        onDestinationSelected = { context.appState.profileDestination.value = it },
        onNavigateHome = onNavigateHome,
        ledgerSyncEnabled = context.runtime.ledgerSyncUiState.enabled,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

@Composable
private fun BksAutomaticBookkeepingRoute(
    context: BksRouteContext,
    innerPadding: PaddingValues
) {
    AutomaticBookkeepingScreen(
        enabled = context.runtime.automaticBookkeepingEnabled,
        accessibilityAccessGranted = context.bindings.billSyncAccessibilityAccessGranted,
        accessibilityServiceConnected = context.bindings.billSyncAccessibilityServiceConnected,
        onEnabledChange = context.actions::setAutomaticBookkeepingEnabled,
        onOpenAccessibilitySettings = context.bindings.onOpenBillSyncAccessibilitySettings,
        onBack = { context.appState.profileDestination.value = null },
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

@Composable
private fun BksAccountManagementRoute(
    context: BksRouteContext,
    session: AccountSession,
    innerPadding: PaddingValues
) {
    val runtime = context.runtime
    val bindings = context.bindings
    val dependencies = context.dependencies
    val actions = context.actions
    val appState = context.appState
    AccountManagementScreen(
        session = session,
        runtimeState = runtime.accountRuntimeState,
        deletionState = runtime.accountDeletionState,
        accountRepository = dependencies.account.accountRepository,
        onSignInOrRegister = {
            runtime.accountEntryReturnSession = session
            appState.profileDestination.value = null
            runtime.accountSession = null
            runtime.accountRuntimeState = AccountRuntimeState(AccountRuntimeStatus.LocalMode)
        },
        onSessionVerified = actions::applyVerifiedCredentials,
        onInvalidSession = {
            actions.moveAccountToLocalMode()
            appState.profileDestination.value = null
        },
        persistSession = { credentials ->
            val saved = actions.persistAccountSession(credentials)
            if (saved) actions.applyVerifiedCredentials(credentials)
            saved
        },
        clearPersistedSession = dependencies.account.secureAccountSessionStore::clear,
        wechatAuthGateway = dependencies.account.wechatAuthGateway,
        wechatAuthCallback = bindings.wechatAuthCallback,
        onWechatAuthCallbackConsumed = bindings.onWechatAuthCallbackConsumed,
        avatarCacheOverride = dependencies.account.wechatAvatarCache,
        onSignedOut = {
            actions.moveAccountToLocalMode()
            appState.profileDestination.value = null
        },
        onDeletionStateChange = { deletionState ->
            runtime.accountDeletionState = deletionState
            runtime.accountRuntimeState = AccountRuntimeState(
                if (deletionState.isPending) {
                    AccountRuntimeStatus.DeletionCoolingOff
                } else {
                    AccountRuntimeStatus.Verified
                }
            )
        },
        onBack = { appState.profileDestination.value = null },
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

@Composable
private fun BksCategorizationRoute(
    context: BksRouteContext,
    innerPadding: PaddingValues
) {
    val runtime = context.runtime
    CategorizationRulesScreen(
        rules = runtime.categorizationRules,
        onRulesChange = context.actions::persistCategorizationRules,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding),
        aiUiState = CategorizationAiUiState(
            settings = context.presentation.effectiveAiSettings,
            signedIn = runtime.accountSession is AccountSession.SignedIn,
            cloudWritesPaused = runtime.accountDeletionState.isPending ||
                !runtime.accountRuntimeState.cloudWritesAllowed,
            settingsSyncInFlight = runtime.aiSettingsSyncInFlight
        ),
        actions = CategorizationRulesActions(
            onAiSettingsChange = context.actions::persistAiSettings,
            onBack = { context.appState.profileDestination.value = null }
        ),
    )
}

@Composable
private fun BksDataAndBackupRoute(
    context: BksRouteContext,
    innerPadding: PaddingValues
) {
    val runtime = context.runtime
    val dependencies = context.dependencies
    DataAndBackupScreen(
        ledgerEntries = context.presentation.ledgerEntries,
        currentLedgerName = context.presentation.activeLedgerName,
        onExportEncryptedBackup = { passphrase ->
            dependencies.local.dataBackupRepository.exportEncryptedBackup(passphrase)
        },
        onValidateEncryptedBackup = { backup, passphrase ->
            dependencies.local.dataBackupRepository.validateEncryptedBackup(backup, passphrase)
        },
        onImportEncryptedBackup = { backup, passphrase ->
            dependencies.local.dataBackupRepository.importEncryptedBackup(backup, passphrase)
            runtime.reviewState = com.bks.feature.review.ReviewQueueState()
        },
        onDeleteLocalData = context.actions::clearLocalData,
        onBack = { context.appState.profileDestination.value = null },
        snackbarHostState = context.appState.snackbarHostState,
        ledgerSyncState = runtime.ledgerSyncUiState.copy(
            signedIn = runtime.accountSession is AccountSession.SignedIn
        ),
        onPreviewLedgerSync = { context.previewLedgerSync() },
        onEnableLedgerSync = { mode -> context.enableLedgerSync(mode) },
        onSyncNow = { context.syncLedgerNow() },
        onDisableLedgerSync = context::disableLedgerSync,
        onResolveLedgerSyncConflict = { conflictId, version, choice ->
            context.resolveLedgerSyncConflict(conflictId, version, choice)
        },
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}

@Composable
private fun BksComplianceRoute(
    context: BksRouteContext,
    innerPadding: PaddingValues
) {
    ComplianceAndPrivacyScreen(
        isDebugBuild = BuildConfig.DEBUG,
        onBack = { context.appState.profileDestination.value = null },
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    )
}
