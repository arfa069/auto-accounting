package com.autoaccounting.feature.diagnostics

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticEncryptedStoreTest {
    @Test
    fun encryptedSegmentsContainNoSensitivePlaintextAndRoundTrip() {
        val directory = Files.createTempDirectory("diagnostic-store").toFile()
        val store = DiagnosticEncryptedStore(directory, JvmDiagnosticEventCipher())
        val event = event("支付宝商户ABC 订单号ORDER-42 账号6222")

        store.append(event)

        val diskText = directory.listFiles().orEmpty().joinToString { it.readText() }
        assertFalse(diskText.contains("支付宝商户ABC"))
        assertFalse(diskText.contains("ORDER-42"))
        assertFalse(diskText.contains("6222"))
        assertEquals(event, store.readAll().single())
    }

    @Test
    fun corruptAndPartialLinesAreSkippedWithoutLosingValidEvents() {
        val directory = Files.createTempDirectory("diagnostic-corrupt").toFile()
        val store = DiagnosticEncryptedStore(directory, JvmDiagnosticEventCipher())
        store.append(event("first valid event"))
        directory.listFiles().orEmpty().single().appendText("AADLOG1:not-base64\npartial")

        val restored = store.readAll()

        assertEquals(1, restored.size)
        assertEquals("first valid event", restored.single().sensitivePayload.fields[DiagnosticSensitiveField.OcrText])
    }

    @Test
    fun missingKeyMakesOldEventsUnreadableAndNewKeyCanStartFreshSegment() {
        val directory = Files.createTempDirectory("diagnostic-missing-key").toFile()
        val cipher = JvmDiagnosticEventCipher()
        val store = DiagnosticEncryptedStore(directory, cipher)
        store.append(event("encrypted with old key"))

        cipher.deleteKey()

        assertTrue(store.readAll().isEmpty())
        store.append(event("encrypted with replacement key"))
        assertEquals(
            listOf("encrypted with replacement key"),
            store.readAll().mapNotNull {
                it.sensitivePayload.fields[DiagnosticSensitiveField.OcrText]
            }
        )
    }

    @Test
    fun totalLimitRotatesOldestSegments() {
        val directory = Files.createTempDirectory("diagnostic-rotate").toFile()
        val store = DiagnosticEncryptedStore(
            directory = directory,
            cipher = JvmDiagnosticEventCipher(),
            maxSegmentBytes = 700,
            maxTotalBytes = 1_500,
            clock = generateSequence(1L) { it + 1 }.iterator()::next
        )

        repeat(12) { store.append(event("event-$it-" + "x".repeat(280))) }

        assertTrue(store.encryptedBytes() <= 1_500)
        assertFalse(store.readAll().any {
            it.sensitivePayload.fields[DiagnosticSensitiveField.OcrText]?.startsWith("event-0-") == true
        })
    }

    private fun event(text: String) = DiagnosticEvent(
        metadata = DiagnosticEventMetadata(
            timestampEpochMillis = 123L,
            level = DiagnosticLevel.Info,
            component = DiagnosticComponent.Ocr,
            event = "ocr_output",
            traceId = "trace-123",
            source = DiagnosticSource.WeChat,
            reason = "accepted"
        ),
        sensitivePayload = DiagnosticSensitivePayload(
            mapOf(DiagnosticSensitiveField.OcrText to text)
        )
    )
}
