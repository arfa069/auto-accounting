package com.bks.backend

import com.bks.api.HealthResponse
import com.bks.backend.account.AccountService
import com.bks.backend.account.accountRoutes
import com.bks.backend.ai.AiCategorizationService
import com.bks.backend.ai.aiCategorizationRoutes
import com.bks.backend.config.CloudConfigService
import com.bks.backend.config.cloudConfigRoutes
import com.bks.backend.sync.LedgerSyncService
import com.bks.backend.sync.ledgerSyncRoutes
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun main() {
    val backendEnvironment = BackendEnvironment.load()
    val serverConfig = BackendServerConfig.fromEnvironment(backendEnvironment)
    embeddedServer(
        factory = Netty,
        environment = applicationEnvironment(),
        configure = {
            connector {
                port = serverConfig.port
                host = serverConfig.host
            }
            callGroupSize = maxOf(8, Runtime.getRuntime().availableProcessors())
        },
        module = { module(env = backendEnvironment) }
    ).start(wait = true)
}

fun Application.module(
    env: Map<String, String>? = null,
    accountService: AccountService? = null,
    aiCategorizationService: AiCategorizationService? = null,
    cloudConfigService: CloudConfigService? = null,
    ledgerSyncService: LedgerSyncService? = null
) {
    val resolvedEnv = env ?: System.getenv()
    val serverConfig = BackendServerConfig.fromEnvironment(resolvedEnv)
    if (serverConfig.trustProxyHeaders) {
        install(XForwardedHeaders)
    }
    val resolvedAccountService = accountService ?: AccountService.fromEnvironment(resolvedEnv)
    val shouldUseEnvironmentDefaults = env != null || accountService == null
    val resolvedAiCategorizationService = aiCategorizationService ?: if (shouldUseEnvironmentDefaults) {
        AiCategorizationService.fromEnvironment(resolvedEnv)
    } else {
        AiCategorizationService()
    }
    val resolvedCloudConfigService = cloudConfigService ?: if (shouldUseEnvironmentDefaults) {
        CloudConfigService.fromEnvironment(resolvedAccountService, resolvedEnv)
    } else {
        CloudConfigService(accountService = resolvedAccountService)
    }
    val resolvedLedgerSyncService = ledgerSyncService ?: if (shouldUseEnvironmentDefaults) {
        LedgerSyncService.fromEnvironment(resolvedAccountService, resolvedEnv)
    } else {
        LedgerSyncService(accountService = resolvedAccountService)
    }
    val deletionJob = AccountDeletionJob(
        accountService = resolvedAccountService,
        aiCategorizationService = resolvedAiCategorizationService,
        cloudConfigService = resolvedCloudConfigService,
        ledgerSyncService = resolvedLedgerSyncService
    )
    launch {
        while (isActive) {
            try {
                deletionJob.runDueDeletion()
            } catch (e: Exception) {
                log.error("Failed to run account deletion job", e)
            }
            delay(3600_000) // Run every hour
        }
    }
    routing {
        get("/health") {
            val response = HealthResponse(status = "ok")
            call.respondText(
                text = """{"status":"${response.status}"}""",
                contentType = ContentType.Application.Json
            )
        }
        accountRoutes(resolvedAccountService)
        aiCategorizationRoutes(
            resolvedAiCategorizationService,
            resolvedAccountService,
            resolvedCloudConfigService
        )
        cloudConfigRoutes(resolvedCloudConfigService, resolvedAccountService)
        ledgerSyncRoutes(resolvedLedgerSyncService, resolvedAccountService)
    }
}
