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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun automaticWechatOcrPersistsParsedFieldsWithoutRawEvidence() = runBlocking {
        val result = processor.processAutomatic(
            source = BillSyncSource.WeChat,
            pageText = "支付成功\n中国电信\n¥6.99\n返回商家",
            retainRawEvidence = false
        )

        assertEquals(1, result.createdEntries.size)
        val entry = database.pendingEntryDao().listPendingEntries().single()
        assertEquals("中国电信", entry.merchantTitle)
        assertEquals(699L, entry.amountMinor)
        assertEquals(CaptureReason.ACCESSIBILITY_AUTO, entry.captureReason)
        assertEquals(null, entry.evidenceSummary)
        assertTrue(entry.parsedFieldsText.orEmpty().isNotBlank())
    }

    @Test
    fun sentRedPacketFindsUniqueRecentNotificationThatHasNotBeenLinkedToOcr() = runBlocking {
        val fingerprint = requireNotNull(
            wechatOcrPaymentFingerprint(
                "Arfa的红包\n红包金额3.00元，等待对方领取\n" +
                    "未领取的红包，将于24小时后发起退款"
            )
        )
        val linkedNotification = redPacketNotificationEntry(
            id = "linked-notification",
            capturedAtEpochMillis = NOW - 90_000
        ).copy(
            captureReasonLabel = "重复合并",
            parsedFields = listOf(
                "证据来源=通知捕获",
                "证据来源=支付结果自动捕获"
            )
        )
        val newNotification = redPacketNotificationEntry(
            id = "new-notification",
            capturedAtEpochMillis = NOW - 30_000
        )
        val currentState = ReviewQueueState(
            pendingEntries = listOf(
                linkedNotification,
                newNotification,
                newNotification.copy(id = "different-title", title = "转账"),
                newNotification.copy(id = "different-amount", amountMinor = 400L),
                newNotification.copy(id = "different-kind", kindLabel = "收入")
            ),
            nowEpochMillis = NOW
        )
        persistence.persistTransition(
            ReviewQueueState(nowEpochMillis = NOW),
            currentState
        )

        assertTrue(processor.hasUniqueUnlinkedRecentWechatNotification(fingerprint))

        val ambiguousState = currentState.copy(
            pendingEntries = currentState.pendingEntries + newNotification.copy(
                id = "second-new-notification",
                capturedAtEpochMillis = NOW - 10_000
            )
        )
        persistence.persistTransition(currentState, ambiguousState)

        assertFalse(processor.hasUniqueUnlinkedRecentWechatNotification(fingerprint))
    }

    @Test
    fun newNotificationAllowsSameSentRedPacketToBeLinkedOnlyOnce() = runBlocking {
        val linkedNotification = redPacketNotificationEntry(
            id = "linked-notification",
            capturedAtEpochMillis = NOW - 90_000
        ).copy(
            captureReasonLabel = "重复合并",
            parsedFields = listOf(
                "证据来源=通知捕获",
                "证据来源=支付结果自动捕获"
            )
        )
        val newNotification = redPacketNotificationEntry(
            id = "new-notification",
            capturedAtEpochMillis = NOW - 30_000
        )
        persistence.persistTransition(
            ReviewQueueState(nowEpochMillis = NOW),
            ReviewQueueState(
                pendingEntries = listOf(linkedNotification, newNotification),
                nowEpochMillis = NOW
            )
        )
        val pageText = "Arfa的红包\n红包金额3.00元，等待对方领取\n" +
            "未领取的红包，将于24小时后发起退款"

        val first = processor.processAutomatic(
            source = BillSyncSource.WeChat,
            pageText = pageText,
            retainRawEvidence = false,
            automaticCaptureVerification =
                AutomaticCaptureVerification.RequireRecentNotification
        )
        val second = processor.processAutomatic(
            source = BillSyncSource.WeChat,
            pageText = pageText,
            retainRawEvidence = false,
            automaticCaptureVerification =
                AutomaticCaptureVerification.RequireRecentNotification
        )

        assertEquals("new-notification", first.mergedEntries.single().id)
        assertTrue(second.createdEntries.isEmpty())
        assertTrue(second.mergedEntries.isEmpty())
        val entries = persistence.observeState().first().pendingEntries
        assertEquals(2, entries.size)
        assertTrue(
            entries.single { it.id == "new-notification" }
                .parsedFields.contains("证据来源=支付结果自动捕获")
        )
    }

    private fun redPacketNotificationEntry(
        id: String,
        capturedAtEpochMillis: Long
    ): ReviewQueueEntry = ReviewQueueEntry(
        id = id,
        title = "红包",
        amountMinor = 300L,
        transactionTimeText = "2026-07-08 12:29",
        sourceLabel = "微信",
        kindLabel = "支出",
        captureReasonLabel = "通知捕获",
        confidence = ConfidenceState.NEEDS_REVIEW,
        capturedAtEpochMillis = capturedAtEpochMillis
    )

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
