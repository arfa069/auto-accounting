package com.autoaccounting.backend.account
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailProviderTest {

    @Test
    fun noopEmailProviderAlwaysReturnsSent() {
        val result = NoopEmailProvider.sendCode("test@example.com", "123456", "REGISTER")
        assertEquals(EmailProviderResult.Sent, result)
    }

    @Test
    fun missingEmailProviderReturnsUnconfigured() {
        val result = MissingEmailProvider.sendCode("test@example.com", "123456", "REGISTER")
        assertEquals(EmailProviderResult.Failed(AccountError.EMAIL_PROVIDER_UNCONFIGURED), result)
    }

    @Test
    fun fromEnvironmentReturnsMissingWhenProviderNotSmtp() {
        val provider = SmtpEmailProvider.fromEnvironment(mapOf("AUTO_ACCOUNTING_EMAIL_PROVIDER" to ""))
        assertEquals(EmailProviderResult.Failed(AccountError.EMAIL_PROVIDER_UNCONFIGURED), provider.sendCode("a@b.com", "123", "REG"))
    }

    @Test
    fun smtpProviderAttemptsStartTlsBeforeAuthenticationAndFailsWhenRejected() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            serverSocket.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(socket.getOutputStream(), true)

                writer.println("220 smtp.example.com ESMTP")
                reader.readLine()
                writer.println("250 STARTTLS")
                reader.readLine()
                writer.println("454 4.7.0 TLS not available")
            }
        }

        try {
            val provider = SmtpEmailProvider(
                host = "127.0.0.1",
                port = port,
                username = "testuser",
                passwordSupplier = { "wrongpass" },
                fromAddress = "noreply@example.com"
            )
            val result = provider.sendCode("user@example.com", "654321", "REGISTER")
            assertEquals(EmailProviderResult.Failed(AccountError.EMAIL_SEND_FAILED), result)
        } finally {
            serverSocket.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun smtpProviderRejectsPlaintextSecurityModeWithoutConnecting() {
        val provider = SmtpEmailProvider(
            host = "127.0.0.1",
            port = 1,
            username = "testuser",
            passwordSupplier = { "test-password" },
            fromAddress = "noreply@example.com",
            security = "plain"
        )

        assertEquals(
            EmailProviderResult.Failed(AccountError.EMAIL_SEND_FAILED),
            provider.sendCode("user@example.com", "654321", "REGISTER")
        )
    }

    @Test
    fun smtpProviderReturnsFailedOnTimeout() {
        val provider = SmtpEmailProvider(
            host = "192.0.2.1", // Non-routable IP to trigger timeout
            port = 25,
            username = "",
            passwordSupplier = { "" },
            fromAddress = "noreply@example.com",
            timeoutMillis = 200
        )
        val result = provider.sendCode("user@example.com", "654321", "REGISTER")
        assertEquals(EmailProviderResult.Failed(AccountError.EMAIL_SEND_FAILED), result)
    }
}
