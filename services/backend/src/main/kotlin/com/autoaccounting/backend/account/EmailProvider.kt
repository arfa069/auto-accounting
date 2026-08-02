package com.autoaccounting.backend.account

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

interface EmailProvider {
    fun sendCode(email: String, code: String, purpose: String): EmailProviderResult
}

sealed interface EmailProviderResult {
    data object Sent : EmailProviderResult
    data class Failed(val error: AccountError) : EmailProviderResult
}

object NoopEmailProvider : EmailProvider {
    override fun sendCode(email: String, code: String, purpose: String): EmailProviderResult =
        EmailProviderResult.Sent
}

object MissingEmailProvider : EmailProvider {
    override fun sendCode(email: String, code: String, purpose: String): EmailProviderResult =
        EmailProviderResult.Failed(AccountError.EMAIL_PROVIDER_UNCONFIGURED)
}

class SmtpEmailProvider internal constructor(
    private val config: SmtpEmailProviderConfig,
    private val passwordSupplier: () -> String,
    private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
) : EmailProvider {

    @Suppress("LongParameterList")
    constructor(
        host: String,
        port: Int,
        username: String,
        passwordSupplier: () -> String,
        fromAddress: String,
        security: String = SMTP_SECURITY_STARTTLS,
        timeoutMillis: Int = SMTP_DEFAULT_TIMEOUT_MILLIS,
        sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
    ) : this(
        config = SmtpEmailProviderConfig(
            host = host,
            port = port,
            username = username,
            fromAddress = fromAddress,
            security = security,
            timeoutMillis = timeoutMillis
        ),
        passwordSupplier = passwordSupplier,
        sslSocketFactory = sslSocketFactory
    )

    override fun sendCode(email: String, code: String, purpose: String): EmailProviderResult {
        if (!isValidRequest(email, code)) {
            return EmailProviderResult.Failed(AccountError.EMAIL_SEND_FAILED)
        }

        var socket: Socket? = null
        return try {
            socket = connectSocket()
            var connection = SmtpConnection(socket)
            connection.expectResponse(220)
            connection.command("EHLO localhost", 250)

            if (config.security == SMTP_SECURITY_STARTTLS) {
                connection.command("STARTTLS", 220)
                socket = upgradeToTls(socket)
                connection = SmtpConnection(socket)
                connection.command("EHLO localhost", 250)
            }

            if (config.username.isNotBlank()) {
                connection.command("AUTH LOGIN", 334)
                connection.command(Base64.getEncoder().encodeToString(config.username.toByteArray()), 334)
                connection.command(Base64.getEncoder().encodeToString(passwordSupplier().toByteArray()), 235)
            }

            connection.command("MAIL FROM:<${config.fromAddress}>", 250)
            connection.command("RCPT TO:<$email>", 250)
            connection.command("DATA", 354)
            connection.writeMessage(email, code, purpose)
            connection.expectResponse(250)
            runCatching { connection.command("QUIT", 221) }
            EmailProviderResult.Sent
        } catch (_: Exception) {
            EmailProviderResult.Failed(AccountError.EMAIL_SEND_FAILED)
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun isValidRequest(email: String, code: String): Boolean =
        config.security in setOf(SMTP_SECURITY_STARTTLS, SMTP_SECURITY_SSL) &&
            isSafeHeaderValue(email) &&
            isSafeHeaderValue(config.fromAddress) &&
            CODE_REGEX.matches(code)

    private fun connectSocket(): Socket {
        return if (config.security == SMTP_SECURITY_SSL) {
            val sslSocket = sslSocketFactory.createSocket() as SSLSocket
            sslSocket.connect(InetSocketAddress(config.host, config.port), config.timeoutMillis)
            configureTls(sslSocket)
            sslSocket.startHandshake()
            sslSocket
        } else {
            Socket().apply {
                connect(InetSocketAddress(config.host, config.port), config.timeoutMillis)
                soTimeout = config.timeoutMillis
            }
        }
    }

    private fun upgradeToTls(socket: Socket): SSLSocket {
        val sslSocket = sslSocketFactory.createSocket(socket, config.host, config.port, true) as SSLSocket
        configureTls(sslSocket)
        sslSocket.startHandshake()
        return sslSocket
    }

    private fun configureTls(socket: SSLSocket) {
        socket.soTimeout = config.timeoutMillis
        socket.sslParameters = socket.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
        }
    }

    private inner class SmtpConnection(socket: Socket) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), false)

        fun command(command: String, expectedCode: Int) {
            writer.print(command)
            writer.print("\r\n")
            writer.flush()
            expectResponse(expectedCode)
        }

        fun expectResponse(expectedCode: Int) {
            val firstLine = reader.readLine() ?: error("SMTP connection closed")
            val actualCode = firstLine.take(3).toIntOrNull() ?: error("Invalid SMTP response")
            if (firstLine.getOrNull(3) == '-') {
                val terminalPrefix = "$actualCode "
                while (true) {
                    val line = reader.readLine() ?: error("SMTP connection closed")
                    if (line.startsWith(terminalPrefix)) break
                }
            }
            if (actualCode != expectedCode) error("Unexpected SMTP response")
        }

        fun writeMessage(email: String, code: String, purpose: String) {
            val subject = Base64.getEncoder().encodeToString("自动记账验证码".toByteArray(Charsets.UTF_8))
            val purposeText = when (purpose) {
                "REGISTER" -> "注册账号"
                "RECOVERY" -> "找回密码"
                "IDENTIFIER_LINK" -> "绑定登录方式"
                "WECHAT_LINK" -> "绑定微信"
                "WECHAT_UNLINK" -> "解绑微信"
                else -> "账号验证"
            }
            writer.print("From: ${config.fromAddress}\r\n")
            writer.print("To: $email\r\n")
            writer.print("Subject: =?UTF-8?B?$subject?=\r\n")
            writer.print("Content-Type: text/plain; charset=UTF-8\r\n")
            writer.print("Content-Transfer-Encoding: 8bit\r\n\r\n")
            writer.print("用途：$purposeText\r\n")
            writer.print("验证码：$code\r\n")
            writer.print("验证码 5 分钟内有效，请勿转发。\r\n")
            writer.print(".\r\n")
            writer.flush()
        }
    }

    companion object {
        private val CODE_REGEX = Regex("^\\d{6}$")

        fun fromEnvironment(env: Map<String, String> = System.getenv()): EmailProvider {
            if (!env["AUTO_ACCOUNTING_EMAIL_PROVIDER"].orEmpty().equals("smtp", ignoreCase = true)) {
                return MissingEmailProvider
            }

            val host = env["AUTO_ACCOUNTING_SMTP_HOST"].orEmpty()
            val security = env["AUTO_ACCOUNTING_SMTP_SECURITY"].orEmpty()
                .ifBlank { SMTP_SECURITY_STARTTLS }
                .lowercase()
            if (host.isBlank() || security !in setOf(SMTP_SECURITY_STARTTLS, SMTP_SECURITY_SSL)) {
                return MissingEmailProvider
            }
            val port = env["AUTO_ACCOUNTING_SMTP_PORT"]?.toIntOrNull()
                ?: if (security == SMTP_SECURITY_SSL) 465 else 587
            val username = env["AUTO_ACCOUNTING_SMTP_USERNAME"].orEmpty()
            val password = env["AUTO_ACCOUNTING_SMTP_PASSWORD"].orEmpty()
            val fromAddress = env["AUTO_ACCOUNTING_SMTP_FROM"].orEmpty().ifBlank { username }
            if (fromAddress.isBlank() || !isSafeHeaderValue(fromAddress)) return MissingEmailProvider

            return SmtpEmailProvider(
                config = SmtpEmailProviderConfig(
                    host = host,
                    port = port,
                    username = username,
                    fromAddress = fromAddress,
                    security = security
                ),
                passwordSupplier = { password }
            )
        }

        private fun isSafeHeaderValue(value: String): Boolean =
            value.isNotBlank() && '\r' !in value && '\n' !in value
    }
}
