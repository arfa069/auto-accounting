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

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
