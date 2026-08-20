package com.bks.feature.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test
    fun authenticationSecretsAreRedactedButTransactionIdentifiersRemain() {
        val event = diagnosticEvent(
            mapOf(
                DiagnosticSensitiveField.NotificationText to
                    "支付成功 token=secret-token Authorization: Basic basic-secret\n" +
                        "Cookie: sid=hidden; auth=second-cookie-secret",
                DiagnosticSensitiveField.PaymentAccount to "招商银行(1234)",
                DiagnosticSensitiveField.OrderNumber to "ORDER-20260717",
                DiagnosticSensitiveField.ExceptionDetails to
                    "IllegalStateException: API Key=top-secret\n" +
                        "passphrase=correct horse battery staple; next=value\n" +
                        "备份口令：中文秘密 仍是口令；验证码：123456\n" +
                        "-----BEGIN ENCRYPTED PRIVATE KEY-----\nprivate-key-secret\n" +
                        "-----END ENCRYPTED PRIVATE KEY-----\nstack"
            )
        ).let {
            it.copy(metadata = it.metadata.copy(reason = "Authorization: Basic metadata-secret"))
        }

        val sanitized = sanitizeDiagnosticEvent(event)
        val all = sanitized.sensitivePayload.fields.values.joinToString("\n")

        assertFalse(all.contains("secret-token"))
        assertFalse(all.contains("basic-secret"))
        assertFalse(all.contains("second-cookie-secret"))
        assertFalse(all.contains("top-secret"))
        assertFalse(all.contains("correct horse battery staple"))
        assertFalse(all.contains("中文秘密"))
        assertFalse(all.contains("123456"))
        assertFalse(all.contains("private-key-secret"))
        assertFalse(sanitized.metadata.reason.orEmpty().contains("metadata-secret"))
        assertTrue(all.contains("[REDACTED]"))
        assertEquals("招商银行(1234)", sanitized.sensitivePayload.fields[DiagnosticSensitiveField.PaymentAccount])
        assertEquals("ORDER-20260717", sanitized.sensitivePayload.fields[DiagnosticSensitiveField.OrderNumber])
    }

    @Test
    fun wechatOAuthSecretsAndIdentityIdentifiersAreRedacted() {
        val event = diagnosticEvent(
            mapOf(
                DiagnosticSensitiveField.ExceptionDetails to
                    "wechat_code=wx-code-secret wechat-ticket: ticket-secret " +
                        "OpenID=openid-secret Union_ID=unionid-secret " +
                        "access_token=access-secret refresh-token=refresh-secret\n" +
                        "{\"wechat_code\":\"json-code-secret\",\"openid\":\"json-openid-secret\"}"
            )
        )

        val sanitized = sanitizeDiagnosticEvent(event)
            .sensitivePayload.fields.getValue(DiagnosticSensitiveField.ExceptionDetails)

        listOf(
            "wx-code-secret",
            "ticket-secret",
            "openid-secret",
            "unionid-secret",
            "access-secret",
            "refresh-secret",
            "json-code-secret",
            "json-openid-secret"
        ).forEach { secret -> assertFalse(sanitized.contains(secret)) }
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun oversizedEventTruncatesLargestFieldsAndMarksThem() {
        val event = diagnosticEvent(
            mapOf(DiagnosticSensitiveField.OcrText to "商户与订单".repeat(60_000))
        )

        val sanitized = sanitizeDiagnosticEvent(event)

        assertTrue(DiagnosticSensitiveField.OcrText in sanitized.truncatedFields)
        assertTrue(DiagnosticEventCodec.encode(sanitized).toByteArray().size <= 256 * 1024)
    }

    @Test
    fun exceptionDetailsIncludeCauseSuppressedAndStackBeforeRedaction() {
        val cause = IllegalArgumentException("token=cause-secret")
        val error = IllegalStateException("password=root-secret", cause).apply {
            addSuppressed(IllegalStateException("Cookie: suppressed-secret"))
        }

        val sanitized = sanitizeDiagnosticEvent(
            diagnosticEvent(
                mapOf(
                    DiagnosticSensitiveField.ExceptionDetails to
                        error.toDiagnosticExceptionDetails()
                )
            )
        ).sensitivePayload.fields.getValue(DiagnosticSensitiveField.ExceptionDetails)

        assertTrue(sanitized.contains("Caused by"))
        assertTrue(sanitized.contains("Suppressed"))
        assertFalse(sanitized.contains("cause-secret"))
        assertFalse(sanitized.contains("root-secret"))
        assertFalse(sanitized.contains("suppressed-secret"))
    }

    private fun diagnosticEvent(fields: Map<DiagnosticSensitiveField, String>) = DiagnosticEvent(
        metadata = DiagnosticEventMetadata(
            level = DiagnosticLevel.Error,
            component = DiagnosticComponent.Ocr,
            event = "test_event",
            traceId = "trace-test",
            reason = "test"
        ),
        sensitivePayload = DiagnosticSensitivePayload(fields)
    )
}
