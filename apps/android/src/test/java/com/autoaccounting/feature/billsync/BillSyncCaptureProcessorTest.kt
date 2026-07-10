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

    @Test
    fun inAppPaymentRecordPersistsAsPendingReviewEntry() = runBlocking {
        val result = processor.process(
            source = BillSyncSource.Alipay,
            pageText = """
                支付信息
                消息盒子
                支付成功
                商户：便利店
                金额 ¥20.00
                付款方式 支付宝余额
                交易时间 2026-07-10 09:12
            """.trimIndent()
        )

        assertEquals(1, result.createdEntries.size)
        val entries = database.pendingEntryDao().listPendingEntries()
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("便利店", entry.merchantTitle)
        assertEquals(2000L, entry.amountMinor)
        assertEquals(CaptureReason.BILL_SYNC, entry.captureReason)
        assertEquals(ConfidenceState.HIGH, entry.confidence)
        assertTrue(entry.evidenceSummary.orEmpty().contains("支付信息"))
    }

    @Test
    fun automaticPaymentResultAppliesDefaultRuleAndStaysPending() = runBlocking {
        LocalPreferencesRepository(database).seedDefaultCategorizationRules()

        val result = processor.processAutomatic(
            source = BillSyncSource.Alipay,
            pageText = "支付成功\n收款方：餐饮\n¥20.00"
        )

        assertEquals(1, result.createdEntries.size)
        val entry = database.pendingEntryDao().listPendingEntries().single()
        assertEquals(CaptureReason.ACCESSIBILITY_AUTO, entry.captureReason)
        assertEquals("food", entry.suggestedCategoryId)
        assertTrue(database.ledgerEntryDao().listLedgerEntries().isEmpty())
    }

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
