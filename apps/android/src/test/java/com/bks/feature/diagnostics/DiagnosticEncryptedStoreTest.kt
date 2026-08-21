package com.bks.feature.diagnostics

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
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
        assertEquals("first valid event", restored.single().sensitivePayload.fields[DiagnosticSensitiveField.ExceptionDetails])
    }

    @Test
    fun readLatestOnlyDecryptsRequestedEvents() {
        val directory = Files.createTempDirectory("diagnostic-latest").toFile()
        val cipher = StoreCountingDiagnosticEventCipher()
        val store = DiagnosticEncryptedStore(directory, cipher)
        repeat(3) { store.append(event("event-$it")) }
        cipher.decryptCalls.set(0)

        val latest = store.readLatest(1)

        assertEquals(1, latest.size)
        assertEquals("event-2", latest.single().sensitivePayload.fields[DiagnosticSensitiveField.ExceptionDetails])
        assertEquals(1, cipher.decryptCalls.get())
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
                it.sensitivePayload.fields[DiagnosticSensitiveField.ExceptionDetails]
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
            it.sensitivePayload.fields[DiagnosticSensitiveField.ExceptionDetails]?.startsWith("event-0-") == true
        })
    }

    private fun event(text: String) = DiagnosticEvent(
        metadata = DiagnosticEventMetadata(
            timestampEpochMillis = 123L,
            level = DiagnosticLevel.Info,
            component = DiagnosticComponent.Application,
            event = "application_event",
            traceId = "trace-123",
            source = DiagnosticSource.System,
            reason = "accepted"
        ),
        sensitivePayload = DiagnosticSensitivePayload(
            mapOf(DiagnosticSensitiveField.ExceptionDetails to text)
        )
    )
}

private class StoreCountingDiagnosticEventCipher : DiagnosticEventCipher {
    private val delegate = JvmDiagnosticEventCipher()
    val decryptCalls = AtomicInteger(0)

    override fun encrypt(plainText: ByteArray): ByteArray = delegate.encrypt(plainText)

    override fun decrypt(payload: ByteArray): ByteArray {
        decryptCalls.incrementAndGet()
        return delegate.decrypt(payload)
    }

    override fun deleteKey() = delegate.deleteKey()
}
