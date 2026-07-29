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
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.feature.capture.SharedPreferencesAlipayTransitContextStore
import com.autoaccounting.feature.review.ReviewQueueEntry
import com.autoaccounting.feature.review.ReviewQueueAction
import com.autoaccounting.feature.review.ReviewQueuePersistence
import com.autoaccounting.feature.review.ReviewQueueState
import com.autoaccounting.feature.review.reduceReviewQueue
import com.autoaccounting.feature.diagnostics.DiagnosticSensitiveField
import com.autoaccounting.feature.diagnostics.InMemoryDiagnosticRecorder
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
            "bill-sync-processor-transit-${System.nanoTime()}"
        )
        alipayTransitContextStore.clear()
        processor = BillSyncCaptureProcessor(
            pipeline = BillSyncPipeline(
                captureTimeFormatter = { "2026-07-08 12:30" }
            ),
            reviewQueuePersistence = persistence,
            preferencesRepository = LocalPreferencesRepository(database),
            clock = { NOW },
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
    fun metroContextEnrichesOneRecentGenericAlipayNotificationThatArrivedFirst() = runBlocking {
        LocalPreferencesRepository(database).seedDefaultCategorizationRules()
        val notification = ReviewQueueEntry(
            id = "notification-alipay",
            title = "未知来源",
            amountMinor = 3590,
            transactionTimeText = "2026-07-08 12:29",
            sourceLabel = "支付宝",
            kindLabel = "支出",
            captureReasonLabel = "通知捕获",
            confidence = ConfidenceState.NEEDS_REVIEW,
            capturedAtEpochMillis = NOW - 60_000
        )
        val previous = ReviewQueueState(
            pendingEntries = listOf(notification),
            nowEpochMillis = NOW
        )
        persistence.persistTransition(ReviewQueueState(nowEpochMillis = NOW), previous)

        assertTrue(processor.recordAlipayMetroExitContext())

        val entry = database.pendingEntryDao().listPendingEntries().single()
        assertEquals("地铁乘车", entry.merchantTitle)
        assertEquals("transport", entry.suggestedCategoryId)
        assertTrue(entry.parsedFieldsText.orEmpty().contains("场景证据=支付宝乘车已出站"))
        assertFalse(alipayTransitContextStore.consumeForNotification(NOW + 30_000))
    }

    @Test
    fun ambiguousRecentGenericNotificationsAreNotEnrichedOrSavedAsFutureContext() = runBlocking {
        val notifications = listOf(
            ReviewQueueEntry(
                id = "notification-alipay-1",
                title = "未知来源",
                amountMinor = 3590,
                transactionTimeText = "2026-07-08 12:29",
                sourceLabel = "支付宝",
                kindLabel = "支出",
                captureReasonLabel = "通知捕获",
                confidence = ConfidenceState.NEEDS_REVIEW,
                capturedAtEpochMillis = NOW - 60_000
            ),
            ReviewQueueEntry(
                id = "notification-alipay-2",
                title = "未知来源",
                amountMinor = 1200,
                transactionTimeText = "2026-07-08 12:28",
                sourceLabel = "支付宝",
                kindLabel = "支出",
                captureReasonLabel = "通知捕获",
                confidence = ConfidenceState.NEEDS_REVIEW,
                capturedAtEpochMillis = NOW - 120_000
            )
        )
        val previous = ReviewQueueState(
            pendingEntries = notifications,
            nowEpochMillis = NOW
        )
        persistence.persistTransition(ReviewQueueState(nowEpochMillis = NOW), previous)

        assertFalse(processor.recordAlipayMetroExitContext())

        assertTrue(
            database.pendingEntryDao().listPendingEntries().all {
                it.merchantTitle == "未知来源"
            }
        )
        assertFalse(alipayTransitContextStore.consumeForNotification(NOW + 30_000))
    }

    @Test
    fun ignoredAutomaticCaptureIsNotCreatedAgain() = runBlocking {
        val pageText = "支付成功\n收款方：餐饮\n¥20.00"
        val first = processor.processAutomatic(
            source = BillSyncSource.Alipay,
            pageText = pageText
        )
        val pendingState = persistence.observeState().first()
        val ignoredState = reduceReviewQueue(
            pendingState,
            ReviewQueueAction.Ignore(first.createdEntries.single().id)
        )
        persistence.persistTransition(pendingState, ignoredState)

        val repeated = processor.processAutomatic(
            source = BillSyncSource.Alipay,
            pageText = pageText
        )
        val persistedState = persistence.observeState().first()

        assertTrue(repeated.createdEntries.isEmpty())
        assertTrue(repeated.mergedEntries.isEmpty())
        assertEquals(1, repeated.duplicateSkippedCount)
        assertTrue(persistedState.pendingEntries.isEmpty())
        assertEquals(1, persistedState.ignoredEntries.size)
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
    fun manualWechatOcrKeepsRawTextOffDiskAndFlagsMissingMerchantForReview() = runBlocking {
        val pageText = requireNotNull(
            prepareManualWechatOcrResultText("当前状态\n支付成功\n¥10.40")
        )

        val result = processor.processManualOcr(
            source = BillSyncSource.WeChat,
            pageText = pageText
        )

        assertEquals(1, result.createdEntries.size)
        val entry = database.pendingEntryDao().listPendingEntries().single()
        assertEquals("微信支付", entry.merchantTitle)
        assertEquals(1_040L, entry.amountMinor)
        assertEquals(ConfidenceState.NEEDS_REVIEW, entry.confidence)
        assertEquals("商户未识别，请人工确认", entry.note)
        assertEquals(null, entry.evidenceSummary)
        assertTrue(entry.parsedFieldsText.orEmpty().isNotBlank())
    }

    @Test
    fun manualWechatOcrPersistsStructuredHistoryBillFieldsWithoutRawEvidence() = runBlocking {
        val pageText = requireNotNull(
            prepareManualWechatOcrResultText(
                """
                    账单服务
                    肯德基
                    -¥10.40
                    当前状态
                    支付成功
                    支付时间
                    2026年07月12日 09:16:07
                    商品
                    KFC_PREWX10012651367114169061602
                    商户全称
                    百胜餐饮（广东）有限公司
                    支付方式
                    零钱
                    交易单号
                    4500000279202607127462299679
                    商户单号
                    WX10012651367114169061602
                """.trimIndent()
            )
        )

        val result = processor.processManualOcr(
            source = BillSyncSource.WeChat,
            pageText = pageText
        )

        assertEquals(1, result.createdEntries.size)
        val entry = database.pendingEntryDao().listPendingEntries().single()
        assertEquals("KFC_PREWX10012651367114169061602", entry.merchantTitle)
        assertEquals(1_040L, entry.amountMinor)
        assertEquals("零钱", entry.fundingAccountLabel)
        assertEquals(CaptureReason.BILL_SYNC, entry.captureReason)
        assertEquals(null, entry.evidenceSummary)
        assertTrue(entry.parsedFieldsText.orEmpty().contains("当前状态=支付成功"))
        assertTrue(entry.parsedFieldsText.orEmpty().contains("商品=KFC_PREWX10012651367114169061602"))
        assertTrue(entry.parsedFieldsText.orEmpty().contains("商品名称=KFC_PREWX10012651367114169061602"))
        assertTrue(entry.parsedFieldsText.orEmpty().contains("商户或收款方=百胜餐饮（广东）有限公司"))
        assertTrue(entry.parsedFieldsText.orEmpty().contains("交易单号=4500000279202607127462299679"))
        assertTrue(entry.parsedFieldsText.orEmpty().contains("商户单号=WX10012651367114169061602"))
        val diagnosticFields = diagnostics.events.flatMap {
            it.sensitivePayload.fields.entries
        }.associate { it.key to it.value }
        assertTrue(diagnosticFields.getValue(DiagnosticSensitiveField.OcrText).contains("肯德基"))
        assertEquals("零钱", diagnosticFields[DiagnosticSensitiveField.PaymentMethod])
        assertEquals(
            "4500000279202607127462299679",
            diagnosticFields[DiagnosticSensitiveField.OrderNumber]
        )
        assertEquals(
            "WX10012651367114169061602",
            diagnosticFields[DiagnosticSensitiveField.MerchantOrderNumber]
        )
    }

    @Test
    fun manualWechatOcrPersistsCompletedTransferAndRefundKinds() = runBlocking {
        val transferText = requireNotNull(
            prepareManualWechatOcrResultText(
                """
                    转账-转给测试对象
                    ¥-7.00
                    当前状态
                    对方已收钱
                    转账时间
                    2026年7月19日 15:10:21
                    支付方式
                    测试银行卡
                    转账单号
                    10000000000000000001
                """.trimIndent()
            )
        )
        val refundText = requireNotNull(
            prepareManualWechatOcrResultText(
                """
                    转账-退款
                    ¥+0.05
                    退款状态
                    已退款
                    退款时间
                    2026年7月15日 04:54:51
                    退款方式
                    零钱
                    退款单号
                    10000000000000000002
                """.trimIndent()
            )
        )

        val transferResult = processor.processManualOcr(
            source = BillSyncSource.WeChat,
            pageText = transferText
        )
        val refundResult = processor.processManualOcr(
            source = BillSyncSource.WeChat,
            pageText = refundText
        )

        assertEquals(1, transferResult.createdEntries.size)
        assertEquals(1, refundResult.createdEntries.size)
        val entries = database.pendingEntryDao().listPendingEntries()
        assertEquals(2, entries.size)
        assertEquals(
            TransactionKind.EXPENSE,
            entries.single { it.amountMinor == 700L }.transactionKind
        )
        assertEquals(
            TransactionKind.REFUND,
            entries.single { it.amountMinor == 5L }.transactionKind
        )
        assertTrue(entries.all { it.evidenceSummary == null })
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
