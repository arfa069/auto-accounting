package com.autoaccounting.feature.billsync

import android.content.Context
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.autoaccounting.data.local.AutoAccountingDatabase
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.capture.BookkeepingResultNotifier
import com.autoaccounting.feature.capture.PaymentNotificationCaptureTrigger
import com.autoaccounting.feature.capture.PaymentNotificationCaptureTriggers
import com.autoaccounting.feature.diagnostics.InMemoryDiagnosticRecorder
import com.autoaccounting.feature.monitoring.ContinuousMonitoringPermissionHealth
import com.autoaccounting.feature.monitoring.ContinuousMonitoringState
import com.autoaccounting.feature.monitoring.PaymentScreenCaptureDebouncer
import com.autoaccounting.feature.review.ReviewQueuePersistence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlipayOcrCaptureCoordinatorTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedBlankProbeDoesNotCooldownNextResultSurface() = runTest {
        val host = RecordingHost()
        val coordinator = AlipayOcrCaptureCoordinator(
            scope = this,
            host = host,
            processor = { error("Rejected OCR must not reach the processor") },
            resultNotifier = { error("Rejected OCR must not notify") },
            diagnostics = BillSyncDiagnosticRecorder(InMemoryDiagnosticRecorder()),
            automaticCaptureDebouncer = PaymentScreenCaptureDebouncer()
        )

        assertTrue(
            coordinator.handleSurface(
                packageName = BillSyncSource.Alipay.packageName,
                pageText = "",
                shouldConsiderContinuousMonitoring = true,
                activeRoot = null,
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                eventWindowId = 1
            )
        )
        advanceUntilIdle()
        assertTrue(
            coordinator.handleSurface(
                packageName = BillSyncSource.Alipay.packageName,
                pageText = "支付成功\n支付信息",
                shouldConsiderContinuousMonitoring = true,
                activeRoot = null,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                eventWindowId = 2
            )
        )
        advanceUntilIdle()

        assertEquals(2, host.displayCaptureCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun windowTransitionWithoutNotificationCreatesPendingFromSuccessfulOcr() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AutoAccountingDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val processor = BillSyncCaptureProcessor(
                pipeline = BillSyncPipeline(captureTimeFormatter = { "2026-08-17 12:00" }),
                reviewQueuePersistence = ReviewQueuePersistence(LocalLedgerRepository(database)),
                preferencesRepository = LocalPreferencesRepository(database)
            )
            val host = RecordingHost(
                ocrEvidence = PaymentTextEvidence(
                    "支付成功\n收款方：便利店\n金额 ¥20.00\n交易方式：支付宝余额"
                )
            )
            val coordinator = AlipayOcrCaptureCoordinator(
                scope = this,
                host = host,
                processor = { processor },
                resultNotifier = { BookkeepingResultNotifier(context) },
                diagnostics = BillSyncDiagnosticRecorder(InMemoryDiagnosticRecorder()),
                automaticCaptureDebouncer = PaymentScreenCaptureDebouncer()
            )

            assertTrue(
                coordinator.handleSurface(
                    packageName = BillSyncSource.Alipay.packageName,
                    pageText = "",
                    shouldConsiderContinuousMonitoring = true,
                    activeRoot = null,
                    eventType = AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                    eventWindowId = -1
                )
            )
            advanceUntilIdle()

            val entry = database.pendingEntryDao().listPendingEntries().single()
            assertEquals(2_000L, entry.amountMinor)
            assertEquals("便利店", entry.merchantTitle)
        } finally {
            database.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun notificationAccessibilityAndOcrAreFusedBeforeSinglePendingEntry() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AutoAccountingDatabase::class.java
        ).allowMainThreadQueries().build()
        val trigger = PaymentNotificationCaptureTrigger(
            packageName = BillSyncSource.Alipay.packageName,
            captureId = "notification-fusion",
            amountMinor = 2_000L,
            notificationTimeEpochMillis = 1_787_000_000_000L,
            rawNotificationEvidence = "[通知捕获]\n支付宝支付成功 20.00元"
        )
        try {
            PaymentNotificationCaptureTriggers.publish(trigger)
            val processor = BillSyncCaptureProcessor(
                pipeline = BillSyncPipeline(captureTimeFormatter = { "2026-08-17 12:00" }),
                reviewQueuePersistence = ReviewQueuePersistence(LocalLedgerRepository(database)),
                preferencesRepository = LocalPreferencesRepository(database)
            )
            val coordinator = AlipayOcrCaptureCoordinator(
                scope = this,
                host = RecordingHost(
                    ocrEvidence = PaymentTextEvidence(
                        "支付成功\n收款方：便利店\n金额 ¥20.00\n交易方式：支付宝余额"
                    )
                ),
                processor = { processor },
                resultNotifier = { BookkeepingResultNotifier(context) },
                diagnostics = BillSyncDiagnosticRecorder(InMemoryDiagnosticRecorder()),
                automaticCaptureDebouncer = PaymentScreenCaptureDebouncer()
            )

            assertTrue(
                coordinator.handleSurface(
                    packageName = BillSyncSource.Alipay.packageName,
                    pageText = "支付成功\n收款方：便利店\n金额 ¥20.00",
                    shouldConsiderContinuousMonitoring = true,
                    activeRoot = null,
                    eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    eventWindowId = 1,
                    notificationTrigger = trigger
                )
            )
            advanceUntilIdle()

            val entry = database.pendingEntryDao().listPendingEntries().single()
            assertTrue(entry.evidenceSummary.orEmpty().contains("[通知捕获]"))
            assertTrue(entry.evidenceSummary.orEmpty().contains("[无障碍节点]"))
            assertTrue(entry.evidenceSummary.orEmpty().contains("[ML Kit OCR]"))
            assertFalse(PaymentNotificationCaptureTriggers.tryClaimFallback(trigger.captureId))
        } finally {
            PaymentNotificationCaptureTriggers.complete(trigger.captureId)
            database.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun laterWindowEventIsCapturedWhileFirstOcrIsStillRunning() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            AutoAccountingDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val processor = BillSyncCaptureProcessor(
                pipeline = BillSyncPipeline(captureTimeFormatter = { "2026-08-17 12:00" }),
                reviewQueuePersistence = ReviewQueuePersistence(LocalLedgerRepository(database)),
                preferencesRepository = LocalPreferencesRepository(database)
            )
            val host = RecordingHost(
                ocrEvidenceSequence = listOf(
                    PaymentTextEvidence("支付信息\n收款方：便利店\n金额 ¥20.00"),
                    PaymentTextEvidence(
                        "支付成功\n收款方：便利店\n金额 ¥20.00\n交易方式：支付宝余额"
                    )
                ),
                blockFirstRecognition = true
            )
            val coordinator = AlipayOcrCaptureCoordinator(
                scope = this,
                host = host,
                processor = { processor },
                resultNotifier = { BookkeepingResultNotifier(context) },
                diagnostics = BillSyncDiagnosticRecorder(InMemoryDiagnosticRecorder()),
                automaticCaptureDebouncer = PaymentScreenCaptureDebouncer()
            )

            assertTrue(
                coordinator.handleSurface(
                    packageName = BillSyncSource.Alipay.packageName,
                    pageText = "",
                    shouldConsiderContinuousMonitoring = true,
                    activeRoot = null,
                    eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    eventWindowId = 1
                )
            )
            runCurrent()
            host.firstRecognitionStarted.await()

            assertTrue(
                coordinator.handleSurface(
                    packageName = BillSyncSource.Alipay.packageName,
                    pageText = "",
                    shouldConsiderContinuousMonitoring = true,
                    activeRoot = null,
                    eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    eventWindowId = 1
                )
            )
            advanceTimeBy(500L)
            runCurrent()
            assertEquals(2, host.displayCaptureCount)

            host.releaseFirstRecognition.complete(Unit)
            advanceUntilIdle()

            val entry = database.pendingEntryDao().listPendingEntries().single()
            assertEquals(2_000L, entry.amountMinor)
            assertEquals("便利店", entry.merchantTitle)
        } finally {
            database.close()
        }
    }

    private class RecordingHost(
        private val ocrEvidence: PaymentTextEvidence? = null,
        private val ocrEvidenceSequence: List<PaymentTextEvidence> = emptyList(),
        private val blockFirstRecognition: Boolean = false
    ) : AccessibilityCaptureHost {
        var displayCaptureCount = 0
        val firstRecognitionStarted = CompletableDeferred<Unit>()
        val releaseFirstRecognition = CompletableDeferred<Unit>()
        private var recognitionIndex = 0
        override val currentRoot: AccessibilityNodeInfo? = null
        override val monitoringState = ContinuousMonitoringState(enabled = true)

        override fun currentPermissionHealth() = ContinuousMonitoringPermissionHealth(
            billSyncAccessibilityGranted = true,
            billSyncAccessibilityServiceConnected = true
        )

        override fun isScreenReady() = true
        override fun isApplicationWindow(windowId: Int) = true
        override fun currentWechatWindowEvidence(
            windowId: Int,
            windowIdentity: WechatWindowIdentity?
        ) = WechatWindowEvidence(null, true)

        override suspend fun captureScreenBitmap(windowId: Int, traceId: String?): Bitmap? = null

        override suspend fun captureCurrentDisplayBitmap(traceId: String?): Bitmap? {
            displayCaptureCount += 1
            return (ocrEvidence != null || ocrEvidenceSequence.isNotEmpty())
                .takeIf { it }
                ?.let { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
        }

        override suspend fun recognizeScreen(bitmap: Bitmap) = ""

        override suspend fun recognizeScreenEvidence(bitmap: Bitmap): PaymentTextEvidence {
            if (blockFirstRecognition && recognitionIndex == 0) {
                firstRecognitionStarted.complete(Unit)
                releaseFirstRecognition.await()
            }
            val evidence = ocrEvidenceSequence.getOrNull(recognitionIndex) ?: requireNotNull(ocrEvidence)
            recognitionIndex += 1
            return evidence
        }
    }
}
