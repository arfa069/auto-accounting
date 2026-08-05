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
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.feature.categorization.CategorizationRule
import com.autoaccounting.feature.review.ReviewQueueAction
import com.autoaccounting.feature.review.ReviewQueuePersistence
import com.autoaccounting.feature.review.reduceReviewQueue
import com.autoaccounting.feature.diagnostics.DiagnosticSensitiveField
import com.autoaccounting.feature.diagnostics.InMemoryDiagnosticRecorder
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    private lateinit var persistence: ReviewQueuePersistence
    private lateinit var processor: PaymentNotificationCaptureProcessor
    private lateinit var diagnostics: InMemoryDiagnosticRecorder
    private lateinit var alipayTransitContextStore: SharedPreferencesAlipayTransitContextStore

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
        diagnostics = InMemoryDiagnosticRecorder()
        alipayTransitContextStore = SharedPreferencesAlipayTransitContextStore(
            ApplicationProvider.getApplicationContext(),
            "notification-processor-transit-${System.nanoTime()}"
        )
        alipayTransitContextStore.clear()
        processor = PaymentNotificationCaptureProcessor(
            pipeline = NotificationCapturePipeline(
                captureTimeFormatter = { "2026-07-08 12:21" }
            ),
            reviewQueuePersistence = persistence,
            preferencesRepository = LocalPreferencesRepository(database),
            diagnosticRecorder = diagnostics,
            alipayTransitContextStore = alipayTransitContextStore
        )
    }

    @After
    fun tearDown() {
        alipayTransitContextStore.clear()
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
    fun processWithResultReturnsPendingCreatedWithEntryKeyAndCategory() = runBlocking {
        LocalPreferencesRepository(database).replaceCategorizationRules(
            listOf(
                CategorizationRule(
                    id = "lunch",
                    merchantContains = "午餐",
                    category = "Lunch"
                )
            )
        )

        val result = processor.processWithResult(paymentEvent(postedAtEpochMillis = NOW))

        val entry = database.pendingEntryDao().listPendingEntries().single()
        val notification = result?.notification as? BookkeepingResultNotification.PendingCreated
        assertNotNull(notification)
        assertEquals(entry.id, notification!!.key)
        assertEquals(1, notification.count)
        assertEquals("Lunch", notification.category)
        assertEquals("Lunch", entry.suggestedCategoryLabel)
    }

    @Test
    fun processWithResultReturnsNullNotificationForHighConfidencePendingMerge() = runBlocking {
        processor.process(paymentEvent(postedAtEpochMillis = NOW))

        val result = processor.processWithResult(paymentEvent(postedAtEpochMillis = NOW + 30_000))

        assertNotNull(result)
        assertNull(result?.notification)
        val entry = database.pendingEntryDao().listPendingEntries().single()
        assertEquals(CaptureReason.DUPLICATE_MERGE, entry.captureReason)
        assertEquals(ConfidenceState.HIGH, entry.confidence)
    }

    @Test
    fun processWithResultReturnsUnchangedStateForLedgerHighConfidenceDuplicate() = runBlocking {
        processor.process(sentRedPacketEvent(postedAtEpochMillis = NOW))
        val pendingState = persistence.observeState().first()
        persistence.persistTransition(
            pendingState,
            reduceReviewQueue(
                pendingState,
                ReviewQueueAction.Confirm(pendingState.pendingEntries.single().id)
            )
        )

        val stateBefore = persistence.observeState().first()
        val result = processor.processWithResult(sentRedPacketEvent(postedAtEpochMillis = NOW))

        assertNotNull(result)
        assertNull(result?.notification)
        assertEquals(stateBefore, result?.state)
        assertTrue(database.pendingEntryDao().listPendingEntries().isEmpty())
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
        assertTrue(diagnostics.events.single().sensitivePayload.fields.isEmpty())
    }

    @Test
    fun ordinaryWechatChatNeverStoresNotificationBodyInDiagnostics() = runBlocking {
        processor.process(
            PaymentNotificationEvent(
                packageName = "com.tencent.mm",
                title = "张三",
                text = "晚上一起吃饭吗？",
                postedAtEpochMillis = NOW
            )
        )

        assertTrue(diagnostics.events.single().sensitivePayload.fields.isEmpty())
        assertTrue(
            diagnostics.events.none {
                it.sensitivePayload.fields[DiagnosticSensitiveField.NotificationText]
                    ?.contains("晚上一起吃饭吗") == true
            }
        )
    }

    @Test
    fun distinctSentRedPacketNotificationsWithSameAmountStaySeparate() = runBlocking {
        processor.process(sentRedPacketEvent(postedAtEpochMillis = NOW))
        processor.process(sentRedPacketEvent(postedAtEpochMillis = NOW + 30_000))

        val entries = database.pendingEntryDao().listPendingEntries()

        assertEquals(2, entries.size)
        assertTrue(entries.all { it.captureReason == CaptureReason.NOTIFICATION })

        processor.process(sentRedPacketEvent(postedAtEpochMillis = NOW + 30_000))

        assertEquals(2, database.pendingEntryDao().listPendingEntries().size)
    }

    @Test
    fun newSentRedPacketNotificationIsNotMergedIntoEarlierLedgerEntry() = runBlocking {
        processor.process(sentRedPacketEvent(postedAtEpochMillis = NOW))
        val pendingState = persistence.observeState().first()
        val confirmedState = reduceReviewQueue(
            pendingState,
            ReviewQueueAction.Confirm(pendingState.pendingEntries.single().id)
        )
        persistence.persistTransition(pendingState, confirmedState)

        processor.process(sentRedPacketEvent(postedAtEpochMillis = NOW))
        assertTrue(database.pendingEntryDao().listPendingEntries().isEmpty())

        processor.process(sentRedPacketEvent(postedAtEpochMillis = NOW + 30_000))

        val pendingEntries = database.pendingEntryDao().listPendingEntries()
        assertEquals(1, pendingEntries.size)
        assertTrue(pendingEntries.single().id.contains((NOW + 30_000).toString()))
    }

    @Test
    fun newReceivedRedPacketNotificationIsNotMergedIntoEarlierLedgerEntry() = runBlocking {
        processor.process(receivedRedPacketEvent(postedAtEpochMillis = NOW))
        val pendingState = persistence.observeState().first()
        val confirmedState = reduceReviewQueue(
            pendingState,
            ReviewQueueAction.Confirm(pendingState.pendingEntries.single().id)
        )
        persistence.persistTransition(pendingState, confirmedState)

        processor.process(receivedRedPacketEvent(postedAtEpochMillis = NOW))
        assertTrue(database.pendingEntryDao().listPendingEntries().isEmpty())

        processor.process(receivedRedPacketEvent(postedAtEpochMillis = NOW + 30_000))

        val pendingEntries = database.pendingEntryDao().listPendingEntries()
        assertEquals(1, pendingEntries.size)
        assertEquals("张三", pendingEntries.single().merchantTitle)
        assertEquals(TransactionKind.INCOME, pendingEntries.single().transactionKind)
        assertTrue(pendingEntries.single().id.contains((NOW + 30_000).toString()))
    }

    @Test
    fun genericAlipayExpenseWithinFiveMinutesUsesMetroContextBeforeCategorization() = runBlocking {
        LocalPreferencesRepository(database).replaceCategorizationRules(
            listOf(
                CategorizationRule(
                    id = "metro",
                    titleContains = "地铁",
                    transactionKind = "支出",
                    category = "交通"
                )
            )
        )
        alipayTransitContextStore.record(NOW)

        processor.process(alipayExpenseEvent(NOW + 5 * 60_000 - 1))

        val entry = database.pendingEntryDao().listPendingEntries().single()
        assertEquals("地铁乘车", entry.merchantTitle)
        assertEquals(3590L, entry.amountMinor)
        assertEquals("交通", entry.suggestedCategoryLabel)
        assertTrue(entry.parsedFieldsText.orEmpty().contains("场景证据=支付宝乘车已出站"))
        assertFalse(alipayTransitContextStore.consumeForNotification(NOW + 5 * 60_000 - 1))
    }

    @Test
    fun genericAlipayExpenseAtFiveMinutesKeepsUnknownMerchant() = runBlocking {
        alipayTransitContextStore.record(NOW)

        processor.process(alipayExpenseEvent(NOW + 5 * 60_000))

        assertEquals(
            "未知来源",
            database.pendingEntryDao().listPendingEntries().single().merchantTitle
        )
    }

    @Test
    fun specificAlipayMerchantDoesNotConsumeMetroContext() = runBlocking {
        alipayTransitContextStore.record(NOW)

        processor.process(
            alipayExpenseEvent(NOW + 60_000).copy(
                text = "付款成功 商户：便利店 金额：¥35.90"
            )
        )

        assertEquals(
            "便利店",
            database.pendingEntryDao().listPendingEntries().single().merchantTitle
        )
        assertTrue(alipayTransitContextStore.consumeForNotification(NOW + 90_000))
    }

    private fun paymentEvent(postedAtEpochMillis: Long): PaymentNotificationEvent =
        PaymentNotificationEvent(
            packageName = "com.tencent.mm",
            title = "微信支付",
            text = "付款成功 商户：午餐 金额：¥35.90",
            postedAtEpochMillis = postedAtEpochMillis
        )

    private fun sentRedPacketEvent(postedAtEpochMillis: Long): PaymentNotificationEvent =
        PaymentNotificationEvent(
            packageName = "com.tencent.mm",
            title = "微信红包",
            text = "发出红包 ¥3.00",
            postedAtEpochMillis = postedAtEpochMillis
        )

    private fun receivedRedPacketEvent(postedAtEpochMillis: Long): PaymentNotificationEvent =
        PaymentNotificationEvent(
            packageName = "com.tencent.mm",
            title = "微信红包",
            text = "收到张三的红包 ¥3.00",
            postedAtEpochMillis = postedAtEpochMillis
        )

    private fun alipayExpenseEvent(postedAtEpochMillis: Long): PaymentNotificationEvent =
        PaymentNotificationEvent(
            packageName = "com.eg.android.AlipayGphone",
            title = "支付宝",
            text = "付款成功 ¥35.90",
            postedAtEpochMillis = postedAtEpochMillis
        )

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
