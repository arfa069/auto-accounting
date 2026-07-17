package com.autoaccounting.feature.diagnostics

import java.nio.charset.StandardCharsets

private const val MAX_EVENT_BYTES = 256 * 1024
private const val REDACTED = "[REDACTED]"

private val secretPatterns = listOf(
    Regex("(?i)(authorization\\s*[:=]\\s*)[^\\r\\n,，]+"),
    Regex("(?i)(cookie\\s*[:=]\\s*)[^\\r\\n]+"),
    Regex("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/-]+=*"),
    Regex("(?i)((?:password|passwd|passphrase|backup[_-]?passphrase)\\s*[:=]\\s*)[^\\r\\n,，;；]+"),
    Regex("(?i)((?:access[_-]?token|refresh[_-]?token|id[_-]?token|token|api[\\s_-]?key|client[_-]?secret)\\s*[:=]\\s*)[^\\s,;]+"),
    Regex("((?:密码|口令|备份口令)\\s*[:：=]\\s*)[^\\r\\n，,；;]+"),
    Regex("(令牌\\s*[:：=]\\s*)[^\\s，,；;]+"),
    Regex("(?i)((?:验证码|校验码|动态码|otp)\\s*[:：=]?\\s*)\\d{4,8}"),
    Regex("(?s)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----"),
    Regex("\\bsk-[A-Za-z0-9_-]{12,}\\b")
)

internal fun redactAuthenticationSecrets(value: String): String =
    secretPatterns.fold(value) { sanitized, pattern ->
        pattern.replace(sanitized) { match ->
            val prefix = match.groupValues.getOrElse(1) { "" }
            prefix + REDACTED
        }
    }

internal fun sanitizeDiagnosticEvent(event: DiagnosticEvent): DiagnosticEvent {
    var fields = event.sensitivePayload.fields.mapValues { (_, value) ->
        redactAuthenticationSecrets(value)
    }
    val truncated = event.truncatedFields.toMutableSet()
    var sanitized = event.copy(
        metadata = event.metadata.copy(
            event = redactAuthenticationSecrets(event.metadata.event).take(256),
            traceId = redactAuthenticationSecrets(event.metadata.traceId).take(128),
            sessionId = event.metadata.sessionId
                ?.let(::redactAuthenticationSecrets)
                ?.take(128),
            outcome = event.metadata.outcome
                ?.let(::redactAuthenticationSecrets)
                ?.take(128),
            reason = event.metadata.reason
                ?.let(::redactAuthenticationSecrets)
                ?.take(512)
        ),
        sensitivePayload = DiagnosticSensitivePayload(fields),
        truncatedFields = truncated
    )
    while (DiagnosticEventCodec.encode(sanitized).toByteArray(StandardCharsets.UTF_8).size > MAX_EVENT_BYTES) {
        val largest = fields.maxByOrNull { it.value.toByteArray(StandardCharsets.UTF_8).size }
            ?: break
        if (largest.value.isEmpty()) break
        val bytes = largest.value.toByteArray(StandardCharsets.UTF_8)
        val shortened = bytes.copyOf(bytes.size / 2)
            .toString(StandardCharsets.UTF_8)
            .trimEnd('\uFFFD')
        fields = fields + (largest.key to shortened)
        truncated += largest.key
        sanitized = sanitized.copy(
            sensitivePayload = DiagnosticSensitivePayload(fields),
            truncatedFields = truncated.toSet()
        )
    }
    return sanitized
}
