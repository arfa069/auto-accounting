package com.autoaccounting

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.autoaccounting.feature.account.AccountManagementScreen
import com.autoaccounting.feature.account.AccountRuntimeState
import com.autoaccounting.feature.account.AccountRuntimeStatus
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.categorization.CategorizationAiUiState
import com.autoaccounting.feature.categorization.CategorizationRulesActions
import com.autoaccounting.feature.categorization.CategorizationRulesScreen
import com.autoaccounting.feature.compliance.ComplianceAndPrivacyScreen
import com.autoaccounting.feature.monitoring.AutomaticBookkeepingScreen
import com.autoaccounting.feature.profile.ProfileDestination
import com.autoaccounting.feature.profile.ProfileOverviewScreen
import com.autoaccounting.feature.settings.DataAndBackupScreen

@Composable
internal fun AutoAccountingProfileRoute(
    context: AutoAccountingRouteContext,
    activeAccountSession: AccountSession,
    innerPadding: PaddingValues,
    destination: ProfileDestination?,
    onNavigateHome: () -> Unit
) {
    when (destination) {
        null -> AutoAccountingProfileOverviewRoute(context, activeAccountSession, innerPadding, onNavigateHome)
        ProfileDestination.AccountManagement -> AutoAccountingAccountManagementRoute(context, activeAccountSession, innerPadding)
        ProfileDestination.AutomaticBookkeeping -> AutoAccountingAutomaticBookkeepingRoute(context, innerPadding)
        ProfileDestination.CategorizationRules -> AutoAccountingCategorizationRoute(context, innerPadding)
        ProfileDestination.DataAndBackup -> AutoAccountingDataAndBackupRoute(context, innerPadding)
        ProfileDestination.ComplianceAndPrivacy -> AutoAccountingComplianceRoute(context, innerPadding)
    }
}

@Composable
private fun AutoAccountingProfileOverviewRoute(
    context: AutoAccountingRouteContext,
    session: AccountSession,
    innerPadding: PaddingValues,
    onNavigateHome: () -> Unit
) {
    ProfileOverviewScreen(
        session = session,
        onDestinationSelected = { context.appState.profileDestination.value = it },
        onNavigateHome = onNavigateHome,
        ledgerSyncEnabled = context.runtime.ledgerSyncUiState.enabled,
        modifier = Modifier.padding(innerPadding)
    )
}

@Composable
private fun AutoAccountingAccountManagementRoute(
    context: AutoAccountingRouteContext,
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
        modifier = Modifier.padding(innerPadding)
    )
}

@Composable
private fun AutoAccountingAutomaticBookkeepingRoute(
    context: AutoAccountingRouteContext,
    innerPadding: PaddingValues
) {
    val bindings = context.bindings
    val runtime = context.runtime
    AutomaticBookkeepingScreen(
        notificationListenerAccessGranted = bindings.notificationListenerAccessGranted,
        onOpenNotificationListenerSettings = bindings.onOpenNotificationListenerSettings,
        billSyncAccessibilityAccessGranted = bindings.billSyncAccessibilityAccessGranted,
        onOpenBillSyncAccessibilitySettings = bindings.onOpenBillSyncAccessibilitySettings,
        resultNotificationPermissionGranted = bindings.resultNotificationPermissionGranted,
        onRequestResultNotificationPermission = bindings.onRequestResultNotificationPermission,
        backgroundReliabilityState = bindings.backgroundReliabilityState,
        onOpenBackgroundRunningSettings = bindings.onOpenBackgroundRunningSettings,
        onOpenAutoStartSettings = bindings.onOpenAutoStartSettings,
        onOpenBatteryOptimizationSettings = bindings.onOpenBatteryOptimizationSettings,
        onOpenBatterySaverSettings = bindings.onOpenBatterySaverSettings,
        continuousMonitoringState = runtime.continuousMonitoringState,
        continuousMonitoringPermissionHealth = context.presentation.continuousMonitoringPermissionHealth,
        onContinuousMonitoringStateChange = context.actions::persistContinuousMonitoringState,
        onBack = { context.appState.profileDestination.value = null },
        modifier = Modifier.padding(innerPadding)
    )
}

@Composable
private fun AutoAccountingCategorizationRoute(
    context: AutoAccountingRouteContext,
    innerPadding: PaddingValues
) {
    val runtime = context.runtime
    CategorizationRulesScreen(
        rules = runtime.categorizationRules,
        onRulesChange = context.actions::persistCategorizationRules,
        modifier = Modifier.padding(innerPadding),
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
private fun AutoAccountingDataAndBackupRoute(
    context: AutoAccountingRouteContext,
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
            runtime.reviewState = com.autoaccounting.feature.review.ReviewQueueState()
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
        modifier = Modifier.padding(innerPadding)
    )
}

@Composable
private fun AutoAccountingComplianceRoute(
    context: AutoAccountingRouteContext,
    innerPadding: PaddingValues
) {
    ComplianceAndPrivacyScreen(
        isDebugBuild = BuildConfig.DEBUG,
        onBack = { context.appState.profileDestination.value = null },
        modifier = Modifier.padding(innerPadding)
    )
}
