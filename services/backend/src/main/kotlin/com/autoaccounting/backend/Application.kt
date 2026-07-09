package com.autoaccounting.backend

import com.autoaccounting.api.HealthResponse
import com.autoaccounting.backend.account.AccountService
import com.autoaccounting.backend.account.accountRoutes
import com.autoaccounting.backend.ai.AiCategorizationService
import com.autoaccounting.backend.ai.aiCategorizationRoutes
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

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
    aiCategorizationService: AiCategorizationService = AiCategorizationService()
) {
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
    }
}
