package com.autoaccounting

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.autoaccounting.data.local.AutoAccountingDatabase
import com.autoaccounting.data.local.AutoAccountingDatabaseProvider
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.account.AccountRepository
import com.autoaccounting.feature.account.AndroidWechatAuthGateway
import com.autoaccounting.feature.account.HttpAccountRepository
import com.autoaccounting.feature.account.InstallationIdStore
import com.autoaccounting.feature.account.LocalModeSessionStore
import com.autoaccounting.feature.account.SecureAccountSessionStore
import com.autoaccounting.feature.account.WechatAuthGateway
import com.autoaccounting.feature.account.WechatAvatarCache
import com.autoaccounting.feature.account.rememberWechatAvatarCache
import com.autoaccounting.feature.categorization.AiCategorizationGateway
import com.autoaccounting.feature.categorization.CloudAiSettingsGateway
import com.autoaccounting.feature.categorization.HttpAiCategorizationGateway
import com.autoaccounting.feature.categorization.HttpCloudAiSettingsGateway
import com.autoaccounting.feature.diagnostics.DiagnosticLogRepository
import com.autoaccounting.feature.diagnostics.DiagnosticLogs
import com.autoaccounting.feature.review.ReviewQueuePersistence
import com.autoaccounting.feature.settings.LocalDataBackupRepository
import com.autoaccounting.feature.sync.HttpLedgerSyncRepository
import com.autoaccounting.feature.sync.LedgerSyncCoordinator
import com.autoaccounting.feature.sync.LedgerSyncLocalStore

internal data class AutoAccountingLocalDependencies(
    val database: AutoAccountingDatabase,
    val ledgerRepository: LocalLedgerRepository,
    val preferencesRepository: LocalPreferencesRepository,
    val dataBackupRepository: LocalDataBackupRepository,
    val reviewQueuePersistence: ReviewQueuePersistence
)

internal data class AutoAccountingAccountDependencies(
    val accountRepository: AccountRepository,
    val localModeSessionStore: LocalModeSessionStore,
    val secureAccountSessionStore: SecureAccountSessionStore,
    val persistSessionOverride: ((com.autoaccounting.feature.account.AccountCredentials) -> Boolean)?,
    val wechatAuthGateway: WechatAuthGateway?,
    val wechatAvatarCache: WechatAvatarCache,
    val installationIdStore: InstallationIdStore
)

internal data class AutoAccountingSyncDependencies(
    val localStore: LedgerSyncLocalStore,
    val coordinator: LedgerSyncCoordinator,
    val repository: HttpLedgerSyncRepository
)

internal data class AutoAccountingAppDependencies(
    val context: Context,
    val local: AutoAccountingLocalDependencies,
    val account: AutoAccountingAccountDependencies,
    val sync: AutoAccountingSyncDependencies,
    val aiCategorizationGateway: AiCategorizationGateway,
    val cloudAiSettingsGateway: CloudAiSettingsGateway,
    val diagnosticLogs: DiagnosticLogRepository
)

@Composable
internal fun rememberAutoAccountingAppDependencies(
    overrides: AutoAccountingAppOverrides
): AutoAccountingAppDependencies {
    val context = LocalContext.current
    val database = remember { AutoAccountingDatabaseProvider.get(context) }
    val ledgerRepository = remember(database) { LocalLedgerRepository(database) }
    val preferencesRepository = remember(database) { LocalPreferencesRepository(database) }
    val dataBackupRepository = remember(database) { LocalDataBackupRepository(database) }
    val ledgerSyncLocalStore = remember(database) { LedgerSyncLocalStore(database) }
    val localModeSessionStore = remember(context.applicationContext) {
        LocalModeSessionStore(context.applicationContext)
    }
    val secureAccountSessionStore = remember(context.applicationContext) {
        SecureAccountSessionStore(context.applicationContext)
    }
    val installationIdStore = remember(context.applicationContext) {
        InstallationIdStore(context.applicationContext)
    }
    val productionAccountRepository = remember(installationIdStore) {
        HttpAccountRepository(
            backendUrl = BuildConfig.AUTO_ACCOUNTING_BACKEND_URL,
            installationId = installationIdStore::getOrCreate,
            allowHttp = BuildConfig.AUTO_ACCOUNTING_ALLOW_HTTP_LEDGER_SYNC
        )
    }
    val productionAiCategorizationGateway = remember {
        HttpAiCategorizationGateway(
            backendUrl = BuildConfig.AUTO_ACCOUNTING_BACKEND_URL,
            allowHttp = BuildConfig.AUTO_ACCOUNTING_ALLOW_HTTP_LEDGER_SYNC
        )
    }
    val productionCloudAiSettingsGateway = remember {
        HttpCloudAiSettingsGateway(
            backendUrl = BuildConfig.AUTO_ACCOUNTING_BACKEND_URL,
            allowHttp = BuildConfig.AUTO_ACCOUNTING_ALLOW_HTTP_LEDGER_SYNC
        )
    }
    val ledgerSyncRepository = remember {
        HttpLedgerSyncRepository(
            backendUrl = BuildConfig.AUTO_ACCOUNTING_BACKEND_URL,
            allowHttp = BuildConfig.AUTO_ACCOUNTING_ALLOW_HTTP_LEDGER_SYNC
        )
    }
    val ledgerSyncCoordinator = remember(ledgerSyncLocalStore, ledgerSyncRepository, installationIdStore) {
        LedgerSyncCoordinator(
            localStore = ledgerSyncLocalStore,
            repository = ledgerSyncRepository,
            deviceId = installationIdStore::getOrCreate
        )
    }
    val productionWechatAuthGateway = remember(context.applicationContext) {
        BuildConfig.AUTO_ACCOUNTING_WECHAT_APP_ID
            .takeIf(String::isNotBlank)
            ?.let { appId -> AndroidWechatAuthGateway(context.applicationContext, appId) }
    }
    val wechatAvatarCache = rememberWechatAvatarCache()
    val reviewQueuePersistence = remember(ledgerRepository) {
        ReviewQueuePersistence(ledgerRepository)
    }

    return AutoAccountingAppDependencies(
        context = context,
        local = AutoAccountingLocalDependencies(
            database = database,
            ledgerRepository = ledgerRepository,
            preferencesRepository = preferencesRepository,
            dataBackupRepository = dataBackupRepository,
            reviewQueuePersistence = reviewQueuePersistence
        ),
        account = AutoAccountingAccountDependencies(
            accountRepository = overrides.accountRepository ?: productionAccountRepository,
            localModeSessionStore = localModeSessionStore,
            secureAccountSessionStore = secureAccountSessionStore,
            persistSessionOverride = overrides.persistAccountSession,
            wechatAuthGateway = overrides.wechatAuthGateway ?: productionWechatAuthGateway,
            wechatAvatarCache = wechatAvatarCache,
            installationIdStore = installationIdStore
        ),
        sync = AutoAccountingSyncDependencies(
            localStore = ledgerSyncLocalStore,
            coordinator = ledgerSyncCoordinator,
            repository = ledgerSyncRepository
        ),
        aiCategorizationGateway = overrides.aiCategorizationGateway ?: productionAiCategorizationGateway,
        cloudAiSettingsGateway = overrides.cloudAiSettingsGateway ?: productionCloudAiSettingsGateway,
        diagnosticLogs = remember(context.applicationContext) {
            DiagnosticLogs.get(context.applicationContext)
        }
    )
}
