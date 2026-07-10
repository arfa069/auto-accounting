package com.autoaccounting.feature.review

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.autoaccounting.data.local.AutoAccountingDatabase
import com.autoaccounting.data.local.CaptureReason
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.billsync.BillPageParser
import com.autoaccounting.feature.billsync.BillSyncCaptureProcessor
import com.autoaccounting.feature.billsync.BillSyncPipeline
import com.autoaccounting.feature.billsync.BillSyncSource
import com.autoaccounting.feature.capture.NotificationCapturePipeline
import com.autoaccounting.feature.capture.PaymentNotificationCaptureProcessor
import com.autoaccounting.feature.capture.PaymentNotificationEvent
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrossSourceCaptureConcurrencyTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: AutoAccountingDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AutoAccountingDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun simultaneousNotificationAndAccessibilityCapturePersistOneMergedEntry() = runBlocking {
        val repository = LocalLedgerRepository(database)
        val persistence = ReviewQueuePersistence(
            repository = repository,
            nowProvider = { NOW },
            zoneId = ZoneId.of("UTC")
        )
        val preferences = LocalPreferencesRepository(database)
        val coordinator = ReviewQueueCaptureCoordinator()
        val notificationProcessor = PaymentNotificationCaptureProcessor(
            pipeline = NotificationCapturePipeline(
                captureTimeFormatter = { TRANSACTION_TIME }
            ),
            reviewQueuePersistence = persistence,
            preferencesRepository = preferences,
            captureCoordinator = coordinator
        )
        val accessibilityProcessor = BillSyncCaptureProcessor(
            pipeline = BillSyncPipeline(
                parser = BillPageParser(),
                captureTimeFormatter = { TRANSACTION_TIME }
            ),
            reviewQueuePersistence = persistence,
            preferencesRepository = preferences,
            clock = { NOW },
            captureCoordinator = coordinator
        )

        listOf(
            async(Dispatchers.Default) {
                notificationProcessor.process(
                    PaymentNotificationEvent(
                        packageName = BillSyncSource.WeChat.packageName,
                        title = "微信支付",
                        text = "付款成功 商户：测试商户 金额：¥12.34",
                        postedAtEpochMillis = NOW
                    )
                )
            },
            async(Dispatchers.Default) {
                accessibilityProcessor.processAutomatic(
                    source = BillSyncSource.WeChat,
                    pageText = "支付成功\n测试商户\n¥12.34",
                    retainRawEvidence = false
                )
            }
        ).awaitAll()

        val entries = database.pendingEntryDao().listPendingEntries()
        assertEquals(1, entries.size)
        assertEquals(CaptureReason.DUPLICATE_MERGE, entries.single().captureReason)
    }

    private companion object {
        const val NOW = 1_783_468_800_000L
        const val TRANSACTION_TIME = "2026-07-08 12:20"
    }
}
