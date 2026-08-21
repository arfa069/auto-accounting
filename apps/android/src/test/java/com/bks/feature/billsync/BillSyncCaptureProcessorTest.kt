package com.bks.feature.billsync

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bks.data.local.BksDatabase
import com.bks.data.local.CaptureReason
import com.bks.data.local.ConfidenceState
import com.bks.data.local.LocalLedgerRepository
import com.bks.data.local.LocalPreferencesRepository
import com.bks.data.local.PaymentSource
import com.bks.feature.review.ReviewQueueAction
import com.bks.feature.review.ReviewQueuePersistence
import com.bks.feature.review.reduceReviewQueue
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private lateinit var database: BksDatabase
    private lateinit var persistence: ReviewQueuePersistence
    private lateinit var processor: BillSyncCaptureProcessor

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BksDatabase::class.java
        ).allowMainThreadQueries().build()
        val repository = LocalLedgerRepository(database, clock = { NOW })
        persistence = ReviewQueuePersistence(repository, nowProvider = { NOW }, zoneId = ZoneId.of("UTC"))
        processor = BillSyncCaptureProcessor(
            pipeline = BillSyncPipeline(captureTimeFormatter = { "2026-08-21 10:00" }),
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
    fun acceptedCapturePersistsReviewOnlyAndConfirmationMovesItToLedger() = runBlocking {
        processor.process("支付成功\n¥35.90\n商户：社区便利店\n交易时间 2026-08-21 09:12")

        val pending = database.pendingEntryDao().listPendingEntries().single()
        assertEquals(PaymentSource.OTHER, pending.source)
        assertEquals(CaptureReason.ACCESSIBILITY_AUTO, pending.captureReason)
        assertEquals(ConfidenceState.NEEDS_REVIEW, pending.confidence)
        assertNull(pending.fundingAccountId)
        assertNull(pending.fundingAccountLabel)
        assertNull(pending.evidenceSummary)
        assertTrue(database.ledgerEntryDao().listLedgerEntries().isEmpty())

        val previous = persistence.observeState().first()
        val confirmed = reduceReviewQueue(previous, ReviewQueueAction.Confirm(pending.id))
        persistence.persistTransition(previous, confirmed)

        assertNull(database.pendingEntryDao().getById(pending.id))
        val ledger = database.ledgerEntryDao().listLedgerEntries().single()
        assertEquals(PaymentSource.OTHER, ledger.paymentSource)
        assertNull(ledger.fundingAccountId)
        assertEquals(pending.id, ledger.originPendingEntryId)
    }

    @Test
    fun repeatedCapturePersistsSeparatePendingEntries() = runBlocking {
        var now = NOW
        val repeatedProcessor = BillSyncCaptureProcessor(
            pipeline = BillSyncPipeline(captureTimeFormatter = { "2026-08-21 10:00" }),
            reviewQueuePersistence = persistence,
            preferencesRepository = LocalPreferencesRepository(database),
            clock = { now }
        )
        val page = "支付成功\n¥4.99\n交易方式\n花呗"

        repeatedProcessor.process(page)
        now += 31_000
        repeatedProcessor.process(page)

        assertEquals(2, database.pendingEntryDao().listPendingEntries().size)
    }

    private companion object {
        const val NOW = 1_755_741_600_000L
    }
}
