package com.autoaccounting.feature.dedupe

import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.feature.review.ReviewQueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupeEngineTest {
    @Test
    fun exactNotificationAndBillSyncPairIsHighConfidenceMerge() {
        val notification = entry(
            id = "notification-1",
            captureReason = "通知捕获",
            rawEvidence = "微信支付收款凭证 午餐 35.90"
        )
        val billSync = entry(
            id = "bill-1",
            captureReason = "账单同步",
            rawEvidence = "微信账单 午餐 支出 35.90"
        )

        val result = DedupeEngine().addCandidate(listOf(notification), billSync)

        assertEquals(DedupeMatchLevel.HIGH_CONFIDENCE, result.matchLevel)
        assertEquals(1, result.pendingEntries.size)
        val merged = result.pendingEntries.single()
        assertEquals("notification-1", merged.id)
        assertEquals("重复合并", merged.captureReasonLabel)
        assertEquals(ConfidenceState.HIGH, merged.confidence)
        assertTrue(merged.rawEvidenceText.contains("微信支付收款凭证"))
        assertTrue(merged.rawEvidenceText.contains("微信账单"))
        assertTrue(merged.parsedFields.contains("匹配原因=来源、金额、时间、类型、标题一致"))
    }

    @Test
    fun weakTitleMatchBecomesDuplicateSuspectForReview() {
        val existing = entry(title = "午餐")
        val candidate = entry(
            id = "bill-1",
            title = "商户订单",
            captureReason = "账单同步"
        )

        val result = DedupeEngine().addCandidate(listOf(existing), candidate)

        assertEquals(DedupeMatchLevel.LOW_CONFIDENCE, result.matchLevel)
        assertEquals(2, result.pendingEntries.size)
        val suspect = result.pendingEntries.first()
        assertEquals("bill-1", suspect.id)
        assertEquals(ConfidenceState.DUPLICATE_SUSPECT, suspect.confidence)
        assertEquals("账单同步", suspect.captureReasonLabel)
        assertTrue(suspect.note.orEmpty().contains("可能与 午餐 重复"))
        assertTrue(suspect.parsedFields.contains("疑似重复=午餐"))
    }

    @Test
    fun differentAmountAndTimeAvoidsFalsePositive() {
        val existing = entry(amountMinor = 3590, transactionTimeText = "2026-07-08 12:20")
        val candidate = entry(
            id = "bill-1",
            amountMinor = 8800,
            transactionTimeText = "2026-07-08 18:50",
            captureReason = "账单同步"
        )

        val result = DedupeEngine().addCandidate(listOf(existing), candidate)

        assertEquals(DedupeMatchLevel.NONE, result.matchLevel)
        assertEquals(2, result.pendingEntries.size)
        assertEquals(ConfidenceState.HIGH, result.pendingEntries.first().confidence)
    }

    private fun entry(
        id: String = "notification-1",
        title: String = "午餐",
        amountMinor: Long = 3590,
        transactionTimeText: String = "2026-07-08 12:20",
        source: String = "微信",
        kind: String = "支出",
        captureReason: String = "通知捕获",
        rawEvidence: String = "微信支付收款凭证 午餐 35.90"
    ): ReviewQueueEntry = ReviewQueueEntry(
        id = id,
        title = title,
        amountMinor = amountMinor,
        transactionTimeText = transactionTimeText,
        category = "餐饮",
        fundingAccountLabel = "微信零钱",
        sourceLabel = source,
        kindLabel = kind,
        captureReasonLabel = captureReason,
        confidence = ConfidenceState.HIGH,
        capturedAtEpochMillis = NOW,
        captureTimeText = "2026-07-08 12:21",
        rawEvidenceText = rawEvidence,
        parsedFields = listOf("商户=$title", "金额=35.90", "类型=$kind")
    )

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
