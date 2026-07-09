package com.autoaccounting.backend

import com.autoaccounting.api.HealthResponse
import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.accountRoutes
import com.autoaccounting.backend.ai.AiCategorizationService
import com.autoaccounting.backend.ai.aiCategorizationRoutes
import com.autoaccounting.backend.config.CloudConfigService
import com.autoaccounting.backend.config.cloudConfigRoutes
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun main() {
    embeddedServer(
        factory = Netty,
        port = 8080,
        host = "0.0.0.0",
        module = Application::module
    ).start(wait = true)
}

fun Application.module(
    accountService: AccountService = AccountService.fromEnvironment(),
    aiCategorizationService: AiCategorizationService = AiCategorizationService(),
    cloudConfigService: CloudConfigService = CloudConfigService(accountService = accountService)
) {
    val deletionJob = AccountDeletionJob(
        accountService = accountService,
        aiCategorizationService = aiCategorizationService,
        cloudConfigService = cloudConfigService
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
        accountRoutes(accountService)
        aiCategorizationRoutes(aiCategorizationService, accountService)
        cloudConfigRoutes(cloudConfigService, accountService)
    }
}
