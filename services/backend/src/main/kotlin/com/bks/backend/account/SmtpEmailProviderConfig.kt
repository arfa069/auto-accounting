package com.bks.backend.account

internal const val SMTP_SECURITY_STARTTLS = "starttls"
internal const val SMTP_SECURITY_SSL = "ssl"
internal const val SMTP_DEFAULT_TIMEOUT_MILLIS = 5_000

internal class SmtpEmailProviderConfig(
    val host: String,
    val port: Int,
    val username: String,
    val fromAddress: String,
    val security: String = SMTP_SECURITY_STARTTLS,
    val timeoutMillis: Int = SMTP_DEFAULT_TIMEOUT_MILLIS
)
