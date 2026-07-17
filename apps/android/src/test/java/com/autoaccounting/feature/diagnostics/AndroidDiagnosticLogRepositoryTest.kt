package com.autoaccounting.feature.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
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
