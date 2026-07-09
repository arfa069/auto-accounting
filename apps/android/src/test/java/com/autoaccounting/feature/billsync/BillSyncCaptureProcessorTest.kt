package com.autoaccounting.feature.billsync

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.autoaccounting.data.local.AutoAccountingDatabase
import com.autoaccounting.data.local.CaptureReason
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.review.ReviewQueueEntry
import com.autoaccounting.feature.review.ReviewQueuePersistence
import com.autoaccounting.feature.review.ReviewQueueState
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BillSyncCaptureProcessorTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: AutoAccountingDatabase
    private lateinit var persistence: ReviewQueuePersistence
    private lateinit var processor: BillSyncCaptureProcessor

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AutoAccountingDatabase::class.java
        ).allowMainThreadQueries().build()
        val ledgerRepository = LocalLedgerRepository(database)
        persistence = ReviewQueuePersistence(
            repository = ledgerRepository,
            nowProvider = { NOW },
            zoneId = ZoneId.of("UTC")
        )
        processor = BillSyncCaptureProcessor(
            pipeline = BillSyncPipeline(
                captureTimeFormatter = { "2026-07-08 12:30" }
            ),
            reviewQueuePersistence = persistence,
            preferencesRepository = LocalPreferencesRepository(database),
            clock = { NOW }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun billSyncDeduplicatesAndPersistsThroughReviewQueue() = runBlocking {
        val previous = ReviewQueueState(
            pendingEntries = listOf(
                ReviewQueueEntry(
                    id = "notification-1",
                    title = "午餐",
                    amountMinor = 3590,
                    transactionTimeText = "2026-07-08 12:20",
                    sourceLabel = "微信",
                    kindLabel = "支出",
                    captureReasonLabel = "通知捕获",
                    confidence = ConfidenceState.NEEDS_REVIEW,
                    capturedAtEpochMillis = NOW
                )
            ),
            nowEpochMillis = NOW
        )
        persistence.persistTransition(
            ReviewQueueState(nowEpochMillis = NOW),
            previous
        )

        val result = processor.process(
            source = BillSyncSource.WeChat,
            pageText = "2026-07-08 12:20 午餐 支出 ¥35.90 微信零钱"
        )

        assertEquals(1, result.duplicateSkippedCount)
        val entries = database.pendingEntryDao().listPendingEntries()
        assertEquals(1, entries.size)
        assertEquals(CaptureReason.DUPLICATE_MERGE, entries.single().captureReason)
        assertEquals(ConfidenceState.HIGH, entries.single().confidence)
    }

    @Test
    fun parseFailureLeavesQueueAndLedgerUnchanged() = runBlocking {
        val before = database.pendingEntryDao().listPendingEntries()

        val result = processor.process(
            source = BillSyncSource.Alipay,
            pageText = "not a bill page"
        )

        assertTrue(result.errorMessage != null)
        assertEquals(before, database.pendingEntryDao().listPendingEntries())
        assertTrue(database.ledgerEntryDao().listLedgerEntries().isEmpty())
    }

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
