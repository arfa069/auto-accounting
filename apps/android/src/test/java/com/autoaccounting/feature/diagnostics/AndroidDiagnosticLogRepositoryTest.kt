package com.autoaccounting.feature.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidDiagnosticLogRepositoryTest {
    private lateinit var context: Context

    @Before
    fun clearPreferences() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("diagnostic_log_preferences", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun releaseDefaultsOffAndRequiresExplicitConfirmation() {
        val repository = repository(isDebugBuild = false)

        assertFalse(repository.enabled.value)
        assertFalse(repository.setEnabled(true, userConfirmed = false))
        assertFalse(repository.enabled.value)
        assertTrue(repository.setEnabled(true, userConfirmed = true))
        assertTrue(repository.enabled.value)
    }

    @Test
    fun disablingKeepsHistoryAndClearCanResetReleasePreference() = runBlocking {
        val repository = repository(isDebugBuild = false)
        repository.setEnabled(true, userConfirmed = true)
        repository.recordNow(event("first"))
        repository.setEnabled(false)
        repository.recordNow(event("ignored"))
        repository.refresh()

        assertEquals(1, repository.events.value.size)

        repository.setEnabled(true, userConfirmed = true)
        repository.clear(keepEnabledPreference = true)
        assertTrue(repository.enabled.value)
        assertEquals(0, repository.stats.value.eventCount)

        repository.clear(keepEnabledPreference = false)
        assertFalse(repository.enabled.value)
    }

    @Test
    fun repeatedReasonIsCoalescedWithSuppressedCountAndNoSensitivePayload() = runBlocking {
        val repository = repository(isDebugBuild = true)
        repository.recordNow(event("repeat"))
        repository.recordNow(event("repeat"))
        repository.refresh()

        val summary = repository.events.value.first { it.metadata.suppressedCount == 1 }
        assertTrue(summary.sensitivePayload.fields.isEmpty())
    }

    @Test
    fun expiredCoalesceWindowFlushesSuppressedSummaryBeforeStartingNextWindow() = runBlocking {
        var now = 1_000L
        val repository = repository(isDebugBuild = true, clock = { now })
        repository.recordNow(event("repeat"))
        repository.recordNow(event("repeat"))

        now += 5_001L
        repository.recordNow(event("repeat"))
        repository.refresh()

        assertEquals(3, repository.events.value.size)
        assertEquals(1, repository.events.value.count { it.metadata.suppressedCount == 1 })
    }

    @Test
    fun initialHistoryLoadDoesNotClearActiveCoalesceWindow() = runBlocking {
        val executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue()
        )
        val blockerStarted = CountDownLatch(1)
        val releaseInitialLoad = CountDownLatch(1)
        executor.execute {
            blockerStarted.countDown()
            releaseInitialLoad.await(5, TimeUnit.SECONDS)
        }

        executor.asCoroutineDispatcher().use { storageDispatcher ->
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))
            var now = 1_000L
            val repository = AndroidDiagnosticLogRepository(
                context = context,
                store = DiagnosticEncryptedStore(
                    directory = Files.createTempDirectory("diagnostic-initial-load").toFile(),
                    cipher = JvmDiagnosticEventCipher()
                ),
                isDebugBuild = true,
                buildDefaultEnabled = true,
                clock = { now },
                storageDispatcher = storageDispatcher
            )
            withTimeout(5_000L) {
                while (executor.queue.isEmpty()) yield()
            }

            repository.recordNow(event("repeat"))
            releaseInitialLoad.countDown()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            repository.recordNow(event("repeat"))

            now += 5_001L
            repository.recordNow(event("repeat"))
            repository.refresh()

            assertEquals(3, repository.events.value.size)
            assertEquals(1, repository.events.value.count { it.metadata.suppressedCount == 1 })
        }
    }

    @Test
    fun appendingDoesNotDecryptAllHistoricalEvents() = runBlocking {
        val cipher = CountingDiagnosticEventCipher()
        val store = DiagnosticEncryptedStore(
            directory = Files.createTempDirectory("diagnostic-incremental-append").toFile(),
            cipher = cipher
        )
        store.append(event("existing"))
        val repository = AndroidDiagnosticLogRepository(
            context = context,
            store = store,
            isDebugBuild = true,
            buildDefaultEnabled = true,
            clock = { 1_000L }
        )
        withTimeout(5_000L) {
            repository.stats.first { it.eventCount == 1 }
        }
        cipher.decryptCalls.set(0)

        repository.recordNow(event("new"))

        assertEquals(0, cipher.decryptCalls.get())
        assertEquals(2, repository.stats.value.eventCount)
    }

    @Test
    fun refreshDecryptsOnStorageDispatcherInsteadOfCallerThread() = runBlocking {
        val cipher = CountingDiagnosticEventCipher()
        val store = DiagnosticEncryptedStore(
            directory = Files.createTempDirectory("diagnostic-refresh-dispatcher").toFile(),
            cipher = cipher
        )
        store.append(event("existing"))
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "diagnostic-storage-test")
        }.asCoroutineDispatcher().use { storageDispatcher ->
            val repository = AndroidDiagnosticLogRepository(
                context = context,
                store = store,
                isDebugBuild = true,
                buildDefaultEnabled = true,
                clock = { 1_000L },
                storageDispatcher = storageDispatcher
            )
            withTimeout(5_000L) {
                repository.stats.first { it.eventCount == 1 }
            }
            cipher.decryptThreadNames.clear()

            repository.refresh()

            assertEquals(1, cipher.decryptThreadNames.size)
            assertTrue(cipher.decryptThreadNames.single().startsWith("diagnostic-storage-test"))
        }
    }

    private fun repository(
        isDebugBuild: Boolean,
        clock: () -> Long = { 1_000L }
    ): AndroidDiagnosticLogRepository =
        AndroidDiagnosticLogRepository(
            context = context,
            store = DiagnosticEncryptedStore(
                directory = Files.createTempDirectory("diagnostic-repository").toFile(),
                cipher = JvmDiagnosticEventCipher()
            ),
            isDebugBuild = isDebugBuild,
            buildDefaultEnabled = isDebugBuild,
            clock = clock
        )

    private fun event(reason: String) = DiagnosticEvent(
        metadata = DiagnosticEventMetadata(
            timestampEpochMillis = 1_000L,
            level = DiagnosticLevel.Info,
            component = DiagnosticComponent.AccessibilityService,
            event = "accessibility_event",
            traceId = "trace-$reason",
            source = DiagnosticSource.WeChat,
            reason = reason
        ),
        sensitivePayload = DiagnosticSensitivePayload(
            mapOf(DiagnosticSensitiveField.PageText to "payment page")
        )
    )
}

private class CountingDiagnosticEventCipher : DiagnosticEventCipher {
    private val delegate = JvmDiagnosticEventCipher()
    val decryptCalls = AtomicInteger(0)
    val decryptThreadNames = CopyOnWriteArrayList<String>()

    override fun encrypt(plainText: ByteArray): ByteArray = delegate.encrypt(plainText)

    override fun decrypt(payload: ByteArray): ByteArray {
        decryptCalls.incrementAndGet()
        decryptThreadNames += Thread.currentThread().name
        return delegate.decrypt(payload)
    }

    override fun deleteKey() = delegate.deleteKey()
}
