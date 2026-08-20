package com.bks

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.bks.data.local.BksDatabase
import com.bks.data.local.BksDatabaseProvider
import com.bks.data.local.LocalLedgerRepository
import com.bks.data.local.LocalPreferencesRepository
import com.bks.feature.account.AccountRepository
import com.bks.feature.account.AndroidWechatAuthGateway
import com.bks.feature.account.HttpAccountRepository
import com.bks.feature.account.InstallationIdStore
import com.bks.feature.account.LocalModeSessionStore
import com.bks.feature.account.SecureAccountSessionStore
import com.bks.feature.account.WechatAuthGateway
import com.bks.feature.account.WechatAvatarCache
import com.bks.feature.account.rememberWechatAvatarCache
import com.bks.feature.categorization.AiCategorizationGateway
import com.bks.feature.categorization.CloudAiSettingsGateway
import com.bks.feature.categorization.HttpAiCategorizationGateway
import com.bks.feature.categorization.HttpCloudAiSettingsGateway
import com.bks.feature.diagnostics.DiagnosticLogRepository
import com.bks.feature.diagnostics.DiagnosticLogs
import com.bks.feature.review.ReviewQueuePersistence
import com.bks.feature.settings.LocalDataBackupRepository
import com.bks.feature.sync.HttpLedgerSyncRepository
import com.bks.feature.sync.LedgerSyncCoordinator
import com.bks.feature.sync.LedgerSyncLocalStore

internal data class BksLocalDependencies(
    val database: BksDatabase,
    val ledgerRepository: LocalLedgerRepository,
    val preferencesRepository: LocalPreferencesRepository,
    val dataBackupRepository: LocalDataBackupRepository,
    val reviewQueuePersistence: ReviewQueuePersistence
)

internal data class BksAccountDependencies(
    val accountRepository: AccountRepository,
    val localModeSessionStore: LocalModeSessionStore,
    val secureAccountSessionStore: SecureAccountSessionStore,
    val persistSessionOverride: ((com.bks.feature.account.AccountCredentials) -> Boolean)?,
    val wechatAuthGateway: WechatAuthGateway?,
    val wechatAvatarCache: WechatAvatarCache,
    val installationIdStore: InstallationIdStore
)

internal data class BksSyncDependencies(
    val localStore: LedgerSyncLocalStore,
    val coordinator: LedgerSyncCoordinator,
    val repository: HttpLedgerSyncRepository
)

internal data class BksAppDependencies(
    val context: Context,
    val local: BksLocalDependencies,
    val account: BksAccountDependencies,
    val sync: BksSyncDependencies,
    val aiCategorizationGateway: AiCategorizationGateway,
    val cloudAiSettingsGateway: CloudAiSettingsGateway,
    val diagnosticLogs: DiagnosticLogRepository
)

@Composable
internal fun rememberBksAppDependencies(
    overrides: BksAppOverrides
): BksAppDependencies {
    val context = LocalContext.current
    val database = remember { BksDatabaseProvider.get(context) }
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
            backendUrl = BuildConfig.BKS_BACKEND_URL,
            installationId = installationIdStore::getOrCreate,
            allowHttp = BuildConfig.BKS_ALLOW_HTTP_LEDGER_SYNC
        )
    }
    val productionAiCategorizationGateway = remember {
        HttpAiCategorizationGateway(
            backendUrl = BuildConfig.BKS_BACKEND_URL,
            allowHttp = BuildConfig.BKS_ALLOW_HTTP_LEDGER_SYNC
        )
    }
    val productionCloudAiSettingsGateway = remember {
        HttpCloudAiSettingsGateway(
            backendUrl = BuildConfig.BKS_BACKEND_URL,
            allowHttp = BuildConfig.BKS_ALLOW_HTTP_LEDGER_SYNC
        )
    }
    val ledgerSyncRepository = remember {
        HttpLedgerSyncRepository(
            backendUrl = BuildConfig.BKS_BACKEND_URL,
            allowHttp = BuildConfig.BKS_ALLOW_HTTP_LEDGER_SYNC
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
        BuildConfig.BKS_WECHAT_APP_ID
            .takeIf(String::isNotBlank)
            ?.let { appId -> AndroidWechatAuthGateway(context.applicationContext, appId) }
    }
    val wechatAvatarCache = rememberWechatAvatarCache()
    val reviewQueuePersistence = remember(ledgerRepository) {
        ReviewQueuePersistence(ledgerRepository)
    }

    return BksAppDependencies(
        context = context,
        local = BksLocalDependencies(
            database = database,
            ledgerRepository = ledgerRepository,
            preferencesRepository = preferencesRepository,
            dataBackupRepository = dataBackupRepository,
            reviewQueuePersistence = reviewQueuePersistence
        ),
        account = BksAccountDependencies(
            accountRepository = overrides.accountRepository ?: productionAccountRepository,
            localModeSessionStore = localModeSessionStore,
            secureAccountSessionStore = secureAccountSessionStore,
            persistSessionOverride = overrides.persistAccountSession,
            wechatAuthGateway = overrides.wechatAuthGateway ?: productionWechatAuthGateway,
            wechatAvatarCache = wechatAvatarCache,
            installationIdStore = installationIdStore
        ),
        sync = BksSyncDependencies(
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
