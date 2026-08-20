package com.autoaccounting.feature.billsync

import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.feature.review.ReviewQueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillSyncPipelineTest {
    @Test
    fun manualBillPageCreatesPendingEntry() {
        val result = BillSyncPipeline(
            captureTimeFormatter = { "2026-07-08 12:30" }
        ).sync(
            source = BillSyncSource.WeChat,
            pageText = "2026-07-08 12:20 午餐 支出 ¥35.90 微信零钱",
            existingPendingEntries = emptyList(),
            capturedAtEpochMillis = NOW
        )

        assertEquals(1, result.createdEntries.size)
        assertEquals("午餐", result.createdEntries.single().title)
        assertEquals("补录账单", result.createdEntries.single().captureReasonLabel)
        assertEquals(ConfidenceState.HIGH, result.createdEntries.single().confidence)
    }

    @Test
    fun manualPaymentRecordUsesSafeFallbackTitleAndKeepsResultPending() {
        val result = BillSyncPipeline().sync(
            source = BillSyncSource.Alipay,
            pageText = "支付信息\n支付成功\n交易时间 2026-07-10 09:12\n金额 ¥20.00",
            existingPendingEntries = emptyList(),
            capturedAtEpochMillis = NOW
        )

        assertEquals(1, result.createdEntries.size)
        assertEquals("支付宝支付", result.createdEntries.single().title)
        assertEquals(2_000L, result.createdEntries.single().amountMinor)
    }

    @Test
    fun manualWechatOcrDoesNotPersistRawEvidenceAndFlagsFallbackTitle() {
        val result = BillSyncPipeline().sync(
            source = BillSyncSource.WeChat,
            pageText = "当前状态\n支付成功\n¥10.40",
            existingPendingEntries = emptyList(),
            capturedAtEpochMillis = NOW,
            captureReasonLabel = MANUAL_OCR_CAPTURE_REASON,
            retainRawEvidence = false
        )

        val entry = result.createdEntries.single()
        assertEquals("微信支付", entry.title)
        assertEquals(ConfidenceState.NEEDS_REVIEW, entry.confidence)
        assertEquals("", entry.rawEvidenceText)
        assertTrue(entry.note != null)
    }

    @Test
    fun duplicateManualBillMergesIntoExistingPendingEntry() {
        val existing = ReviewQueueEntry(
            id = "pending-1",
            title = "午餐",
            amountMinor = 3_590,
            transactionTimeText = "2026-07-08 12:20",
            sourceLabel = "微信",
            kindLabel = "支出",
            captureReasonLabel = "补录账单"
        )

        val result = BillSyncPipeline().sync(
            source = BillSyncSource.WeChat,
            pageText = "2026-07-08 12:20 午餐 支出 ¥35.90 微信零钱",
            existingPendingEntries = listOf(existing),
            capturedAtEpochMillis = NOW
        )

        assertTrue(result.createdEntries.isEmpty())
        assertEquals(1, result.mergedEntries.size)
        assertEquals(1, result.duplicateSkippedCount)
    }

    @Test
    fun unsupportedPageFailsWithoutCreatingEntries() {
        val result = BillSyncPipeline().sync(
            source = BillSyncSource.Alipay,
            pageText = "not a bill page",
            existingPendingEntries = emptyList(),
            capturedAtEpochMillis = NOW
        )

        assertTrue(result.errorMessage != null)
        assertTrue(result.createdEntries.isEmpty())
    }

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
