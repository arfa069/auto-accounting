package com.autoaccounting.feature.billsync

import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.feature.review.ReviewQueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillSyncPipelineTest {
    @Test
    fun syncCreatesPendingEntriesAndStepwiseProgress() {
        val result = BillSyncPipeline(
            parser = BillPageParser(),
            captureTimeFormatter = { "2026-07-08 12:30" }
        ).sync(
            source = BillSyncSource.WeChat,
            pageText = "2026-07-08 12:20 午餐 支出 ¥35.90 微信零钱",
            existingPendingEntries = emptyList(),
            capturedAtEpochMillis = NOW
        )

        assertEquals(
            listOf(
                BillSyncStep.OpenSource,
                BillSyncStep.ReadBills,
                BillSyncStep.Parse,
                BillSyncStep.Deduplicate,
                BillSyncStep.CreatePendingEntries,
                BillSyncStep.Completed
            ),
            result.steps
        )
        assertEquals(1, result.createdEntries.size)
        assertEquals("午餐", result.createdEntries.single().title)
        assertEquals("账单同步", result.createdEntries.single().captureReasonLabel)
        assertEquals(ConfidenceState.HIGH, result.createdEntries.single().confidence)
    }

    @Test
    fun exactNotificationDuplicateIsSkipped() {
        val existingNotification = ReviewQueueEntry(
            id = "notification-1",
            title = "午餐",
            amountMinor = 3590,
            transactionTimeText = "2026-07-08 12:20",
            sourceLabel = "微信",
            kindLabel = "支出",
            captureReasonLabel = "通知捕获"
        )

        val result = BillSyncPipeline(parser = BillPageParser()).sync(
            source = BillSyncSource.WeChat,
            pageText = "2026-07-08 12:20 午餐 支出 ¥35.90 微信零钱",
            existingPendingEntries = listOf(existingNotification),
            capturedAtEpochMillis = NOW
        )

        assertEquals(0, result.createdEntries.size)
        assertEquals(1, result.mergedEntries.size)
        assertEquals("重复合并", result.mergedEntries.single().captureReasonLabel)
        assertEquals(1, result.duplicateSkippedCount)
        assertTrue(result.summary.contains("已去重 1 条"))
    }

    @Test
    fun exactLedgerDuplicateDoesNotCreateAnotherPendingEntry() {
        val existingLedgerEntry = ReviewQueueEntry(
            id = "ledger-1",
            title = "午餐",
            amountMinor = 3590,
            transactionTimeText = "2026-07-08 12:20",
            sourceLabel = "微信",
            kindLabel = "支出",
            captureReasonLabel = "已入账"
        )

        val result = BillSyncPipeline(parser = BillPageParser()).sync(
            source = BillSyncSource.WeChat,
            pageText = "2026-07-08 12:20 午餐 支出 ¥35.90 微信零钱",
            existingPendingEntries = emptyList(),
            existingLedgerEntries = listOf(existingLedgerEntry),
            capturedAtEpochMillis = NOW
        )

        assertTrue(result.createdEntries.isEmpty())
        assertTrue(result.mergedEntries.isEmpty())
        assertEquals(1, result.duplicateSkippedCount)
    }

    @Test
    fun unrecognizedPageFailsWithoutCreatingEntries() {
        val result = BillSyncPipeline(parser = BillPageParser()).sync(
            source = BillSyncSource.Alipay,
            pageText = "not a bill page",
            existingPendingEntries = emptyList(),
            capturedAtEpochMillis = NOW
        )

        assertEquals(BillSyncStep.Failed, result.steps.last())
        assertTrue(result.errorMessage != null)
        assertTrue(result.createdEntries.isEmpty())
        assertTrue(result.mergedEntries.isEmpty())
    }

    @Test
    fun paymentInitiationPageFailsClosedWithClearMessage() {
        val result = BillSyncPipeline(parser = BillPageParser()).sync(
            source = BillSyncSource.Alipay,
            pageText = "支付宝\n收银台\n立即付款\n确认支付\n¥20.00",
            existingPendingEntries = emptyList(),
            capturedAtEpochMillis = NOW
        )

        assertEquals(BillSyncStep.Failed, result.steps.last())
        assertTrue(result.errorMessage.orEmpty().contains("付款或转账发起页"))
        assertTrue(result.createdEntries.isEmpty())
    }

    @Test
    fun completedPaymentResultCreatesPendingWithSafeSourceTitle() {
        val result = BillSyncPipeline(parser = BillPageParser()).sync(
            source = BillSyncSource.Alipay,
            pageText = "支付信息\n支付成功\n交易时间 2026-07-10 09:12\n金额 ¥20.00",
            existingPendingEntries = emptyList(),
            capturedAtEpochMillis = NOW
        )

        assertEquals(BillSyncStep.Completed, result.steps.last())
        assertEquals("支付宝支付", result.createdEntries.single().title)
        assertEquals(2_000L, result.createdEntries.single().amountMinor)
    }

    @Test
    fun transientOcrEvidenceIsNotStoredWithPendingEntry() {
        val result = BillSyncPipeline(parser = BillPageParser()).sync(
            source = BillSyncSource.WeChat,
            pageText = "支付成功\n测试商户\n¥12.34\n返回商家",
            existingPendingEntries = emptyList(),
            capturedAtEpochMillis = NOW,
            captureReasonLabel = "支付结果自动捕获",
            retainRawEvidence = false
        )

        assertEquals(1, result.createdEntries.size)
        assertEquals("", result.createdEntries.single().rawEvidenceText)
        assertTrue(result.createdEntries.single().parsedFields.isNotEmpty())
    }

    @Test
    fun trustedWechatMerchantOcrWithoutMerchantIsIgnored() {
        val result = BillSyncPipeline(parser = BillPageParser()).sync(
            source = BillSyncSource.WeChat,
            pageText = "支付成功\n¥6.99\n返回商家",
            existingPendingEntries = emptyList(),
            capturedAtEpochMillis = NOW,
            captureReasonLabel = "支付结果自动捕获",
            retainRawEvidence = false
        )

        assertTrue(result.createdEntries.isEmpty())
        assertTrue(result.mergedEntries.isEmpty())
    }

    @Test
    fun untrustedWechatMerchantOcrWithoutNotificationIsIgnored() {
        val result = BillSyncPipeline(
            parser = BillPageParser(),
            captureTimeFormatter = { "2026-07-08 12:21" }
        ).sync(
            source = BillSyncSource.WeChat,
            pageText = "支付成功\n中国电信\n¥6.99\n返回商家",
            existingPendingEntries = emptyList(),
            capturedAtEpochMillis = NOW,
            captureReasonLabel = "支付结果自动捕获",
            retainRawEvidence = false,
            automaticCaptureVerification =
                AutomaticCaptureVerification.RequireRecentNotification
        )

        assertTrue(result.createdEntries.isEmpty())
        assertTrue(result.mergedEntries.isEmpty())
    }

    @Test
    fun untrustedWechatMerchantOcrMergesWithUniqueRecentNotification() {
        val notification = notificationEntry(
            id = "wechat-notification-1",
            transactionTimeText = "2026-07-08 12:20",
            title = "中国电信",
            amountMinor = 699,
            sourceLabel = "微信"
        )
        val result = BillSyncPipeline(
            parser = BillPageParser(),
            captureTimeFormatter = { "2026-07-08 12:21" }
        ).sync(
            source = BillSyncSource.WeChat,
            pageText = "支付成功\n中国电信\n¥6.99\n返回商家",
            existingPendingEntries = listOf(notification),
            capturedAtEpochMillis = NOW,
            captureReasonLabel = "支付结果自动捕获",
            retainRawEvidence = false,
            automaticCaptureVerification =
                AutomaticCaptureVerification.RequireRecentNotification
        )

        assertTrue(result.createdEntries.isEmpty())
        assertEquals("wechat-notification-1", result.mergedEntries.single().id)
    }

    @Test
    fun untrustedWechatMerchantOcrRejectsLateOrAmbiguousNotifications() {
        fun notification(id: String, time: String, capturedAtEpochMillis: Long) = notificationEntry(
            id = id,
            transactionTimeText = time,
            title = "中国电信",
            amountMinor = 699,
            sourceLabel = "微信",
            capturedAtEpochMillis = capturedAtEpochMillis
        )
        val pipeline = BillSyncPipeline(
            parser = BillPageParser(),
            captureTimeFormatter = { "2026-07-08 12:21" }
        )
        val late = pipeline.sync(
            source = BillSyncSource.WeChat,
            pageText = "支付成功\n中国电信\n¥6.99\n返回商家",
            existingPendingEntries = listOf(
                notification(
                    id = "late",
                    time = "2026-07-08 12:20",
                    capturedAtEpochMillis = NOW - 6 * 60_000
                )
            ),
            capturedAtEpochMillis = NOW,
            captureReasonLabel = "支付结果自动捕获",
            retainRawEvidence = false,
            automaticCaptureVerification =
                AutomaticCaptureVerification.RequireRecentNotification
        )
        val ambiguous = pipeline.sync(
            source = BillSyncSource.WeChat,
            pageText = "支付成功\n中国电信\n¥6.99\n返回商家",
            existingPendingEntries = listOf(
                notification("first", "2026-07-08 12:20", NOW - 60_000),
                notification("second", "2026-07-08 12:22", NOW - 2 * 60_000)
            ),
            capturedAtEpochMillis = NOW,
            captureReasonLabel = "支付结果自动捕获",
            retainRawEvidence = false,
            automaticCaptureVerification =
                AutomaticCaptureVerification.RequireRecentNotification
        )

        assertTrue(late.createdEntries.isEmpty())
        assertTrue(late.mergedEntries.isEmpty())
        assertTrue(ambiguous.createdEntries.isEmpty())
        assertTrue(ambiguous.mergedEntries.isEmpty())
    }

    @Test
    fun untrustedWechatMerchantOcrRejectsOldNotificationWithMatchingExplicitTime() {
        val oldNotification = notificationEntry(
            id = "old-wechat-notification",
            transactionTimeText = "2026-07-01 08:00",
            title = "中国电信",
            amountMinor = 699,
            sourceLabel = "微信",
            capturedAtEpochMillis = NOW - 7 * 24 * 60 * 60_000L
        )

        val result = BillSyncPipeline(parser = BillPageParser()).sync(
            source = BillSyncSource.WeChat,
            pageText = "支付成功\n中国电信\n交易时间 2026-07-01 08:00\n¥6.99\n返回商家",
            existingPendingEntries = listOf(oldNotification),
            capturedAtEpochMillis = NOW,
            captureReasonLabel = "支付结果自动捕获",
            retainRawEvidence = false,
            automaticCaptureVerification =
                AutomaticCaptureVerification.RequireRecentNotification
        )

        assertTrue(result.createdEntries.isEmpty())
        assertTrue(result.mergedEntries.isEmpty())
    }

    @Test
    fun paymentResultWithoutTimeMergesWithUniqueRecentNotification() {
        val notification = notificationEntry(
            id = "notification-1",
            transactionTimeText = "2026-07-08 12:20"
        )

        val result = BillSyncPipeline(
            parser = BillPageParser(),
            captureTimeFormatter = { "2026-07-08 13:10" }
        ).sync(
            source = BillSyncSource.Alipay,
            pageText = "支付成功\n¥35.90",
            existingPendingEntries = listOf(notification),
            capturedAtEpochMillis = NOW,
            captureReasonLabel = "支付结果自动捕获"
        )

        assertTrue(result.createdEntries.isEmpty())
        assertEquals(1, result.mergedEntries.size)
        assertEquals("notification-1", result.mergedEntries.single().id)
        assertEquals("重复合并", result.mergedEntries.single().captureReasonLabel)
    }

    @Test
    fun repeatedPaymentResultUsesPreviouslyMergedNotificationEvidence() {
        val previouslyMergedNotification = notificationEntry(
            id = "notification-1",
            transactionTimeText = "2026-07-08 12:20"
        ).copy(
            captureReasonLabel = "重复合并",
            parsedFields = listOf("证据来源=通知捕获", "证据来源=支付结果自动捕获")
        )

        val result = BillSyncPipeline(
            parser = BillPageParser(),
            captureTimeFormatter = { "2026-07-08 13:10" }
        ).sync(
            source = BillSyncSource.Alipay,
            pageText = "支付成功\n¥35.90",
            existingPendingEntries = listOf(previouslyMergedNotification),
            capturedAtEpochMillis = NOW,
            captureReasonLabel = "支付结果自动捕获"
        )

        assertTrue(result.createdEntries.isEmpty())
        assertEquals(1, result.mergedEntries.size)
        assertEquals("notification-1", result.mergedEntries.single().id)
        assertTrue(result.mergedEntries.single().parsedFields.contains("证据来源=通知捕获"))
    }

    @Test
    fun paymentResultWithoutTimeStaysSuspectWhenRecentNotificationsAreAmbiguous() {
        val notifications = listOf(
            notificationEntry(
                id = "notification-1",
                transactionTimeText = "2026-07-08 12:20"
            ),
            notificationEntry(
                id = "notification-2",
                transactionTimeText = "2026-07-08 12:22"
            )
        )

        val result = BillSyncPipeline(
            parser = BillPageParser(),
            captureTimeFormatter = { "2026-07-08 13:10" }
        ).sync(
            source = BillSyncSource.Alipay,
            pageText = "支付成功\n¥35.90",
            existingPendingEntries = notifications,
            capturedAtEpochMillis = NOW,
            captureReasonLabel = "支付结果自动捕获"
        )

        assertEquals(1, result.createdEntries.size)
        assertTrue(result.mergedEntries.isEmpty())
        assertEquals(ConfidenceState.DUPLICATE_SUSPECT, result.createdEntries.single().confidence)
    }

    @Test
    fun paymentResultOutsideRecentNotificationWindowIsNotAutoMerged() {
        val notification = notificationEntry(
            id = "notification-1",
            transactionTimeText = "2026-07-08 12:20"
        )

        val result = BillSyncPipeline(
            parser = BillPageParser(),
            captureTimeFormatter = { "2026-07-08 13:21" }
        ).sync(
            source = BillSyncSource.Alipay,
            pageText = "支付成功\n¥35.90",
            existingPendingEntries = listOf(notification),
            capturedAtEpochMillis = NOW,
            captureReasonLabel = "支付结果自动捕获"
        )

        assertEquals(1, result.createdEntries.size)
        assertTrue(result.mergedEntries.isEmpty())
    }

    @Test
    fun explicitPaymentTimeIsNotReplacedByRecentNotificationTime() {
        val notification = notificationEntry(
            id = "notification-1",
            transactionTimeText = "2026-07-08 12:20"
        )

        val result = BillSyncPipeline(
            parser = BillPageParser(),
            captureTimeFormatter = { "2026-07-08 12:25" }
        ).sync(
            source = BillSyncSource.Alipay,
            pageText = "支付成功\n交易时间 2026-07-08 12:25\n¥35.90",
            existingPendingEntries = listOf(notification),
            capturedAtEpochMillis = NOW,
            captureReasonLabel = "支付结果自动捕获"
        )

        assertEquals(1, result.createdEntries.size)
        assertTrue(result.mergedEntries.isEmpty())
        assertEquals("2026-07-08 12:25", result.createdEntries.single().transactionTimeText)
        assertEquals(ConfidenceState.DUPLICATE_SUSPECT, result.createdEntries.single().confidence)
    }

    private fun notificationEntry(
        id: String,
        transactionTimeText: String,
        title: String = "未知来源",
        amountMinor: Long = 3_590,
        sourceLabel: String = "支付宝",
        capturedAtEpochMillis: Long = NOW - 60_000
    ): ReviewQueueEntry = ReviewQueueEntry(
        id = id,
        title = title,
        amountMinor = amountMinor,
        transactionTimeText = transactionTimeText,
        sourceLabel = sourceLabel,
        kindLabel = "支出",
        captureReasonLabel = "通知捕获",
        capturedAtEpochMillis = capturedAtEpochMillis
    )

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
