package com.autoaccounting.feature.capture

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.autoaccounting.data.local.AutoAccountingDatabase
import com.autoaccounting.data.local.CaptureReason
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.categorization.CategorizationRule
import com.autoaccounting.feature.review.ReviewQueuePersistence
import java.time.ZoneId
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
class PaymentNotificationCaptureProcessorTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: AutoAccountingDatabase
    private lateinit var processor: PaymentNotificationCaptureProcessor

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AutoAccountingDatabase::class.java
        ).allowMainThreadQueries().build()
        val ledgerRepository = LocalLedgerRepository(database)
        processor = PaymentNotificationCaptureProcessor(
            pipeline = NotificationCapturePipeline(
                captureTimeFormatter = { "2026-07-08 12:21" }
            ),
            reviewQueuePersistence = ReviewQueuePersistence(
                repository = ledgerRepository,
                nowProvider = { NOW },
                zoneId = ZoneId.of("UTC")
            ),
            preferencesRepository = LocalPreferencesRepository(database)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun paymentNotificationPersistsPendingEntryAndDuplicateMerge() = runBlocking {
        LocalPreferencesRepository(database).replaceCategorizationRules(
            listOf(
                CategorizationRule(
                    id = "lunch",
                    merchantContains = "午餐",
                    category = "Lunch"
                )
            )
        )
        processor.process(paymentEvent(postedAtEpochMillis = NOW))
        processor.process(paymentEvent(postedAtEpochMillis = NOW + 30_000))

        val entries = database.pendingEntryDao().listPendingEntries()

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals(CaptureReason.DUPLICATE_MERGE, entry.captureReason)
        assertEquals(ConfidenceState.HIGH, entry.confidence)
        assertEquals("Lunch", entry.suggestedCategoryLabel)
        assertTrue(entry.evidenceSummary.orEmpty().contains("---"))
    }

    @Test
    fun unrelatedNotificationIsRejectedBeforePendingCreation() = runBlocking {
        val result = processor.process(
            PaymentNotificationEvent(
                packageName = "com.example.mail",
                title = "付款成功",
                text = "商户：午餐 金额：¥35.90",
                postedAtEpochMillis = NOW
            )
        )

        assertNull(result)
        assertTrue(database.pendingEntryDao().listPendingEntries().isEmpty())
    }

    private fun paymentEvent(postedAtEpochMillis: Long): PaymentNotificationEvent =
        PaymentNotificationEvent(
            packageName = "com.tencent.mm",
            title = "微信支付",
            text = "付款成功 商户：午餐 金额：¥35.90",
            postedAtEpochMillis = postedAtEpochMillis
        )

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
