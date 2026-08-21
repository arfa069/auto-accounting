package com.bks.feature.dedupe

import com.bks.data.local.ConfidenceState
import com.bks.feature.review.ReviewQueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupeEngineTest {
    @Test
    fun exactAutomaticPairIsHighConfidenceMerge() {
        val existing = entry(
            id = "pending-1",
            captureReason = "支付结果自动捕获"
        )
        val automatic = entry(
            id = "bill-1",
            captureReason = "支付结果自动捕获"
        )

        val result = DedupeEngine().addCandidate(listOf(existing), automatic)

        assertEquals(DedupeMatchLevel.HIGH_CONFIDENCE, result.matchLevel)
        assertEquals(1, result.pendingEntries.size)
        val merged = result.pendingEntries.single()
        assertEquals("pending-1", merged.id)
        assertEquals("重复合并", merged.captureReasonLabel)
        assertEquals(ConfidenceState.HIGH, merged.confidence)
        assertNull(merged.note)
        assertTrue(merged.rawEvidenceText.isEmpty())
        assertTrue(merged.parsedFields.contains("匹配原因=来源、金额、时间、类型、标题一致"))
        assertTrue(merged.parsedFields.contains("证据来源=支付结果自动捕获"))
    }

    @Test
    fun weakTitleMatchBecomesDuplicateSuspectForReview() {
        val existing = entry(title = "午餐")
        val candidate = entry(
            id = "bill-1",
            title = "商户订单",
            captureReason = "支付结果自动捕获"
        )

        val result = DedupeEngine().addCandidate(listOf(existing), candidate)

        assertEquals(DedupeMatchLevel.LOW_CONFIDENCE, result.matchLevel)
        assertEquals(2, result.pendingEntries.size)
        val suspect = result.pendingEntries.first()
        assertEquals("bill-1", suspect.id)
        assertEquals(ConfidenceState.DUPLICATE_SUSPECT, suspect.confidence)
        assertEquals("支付结果自动捕获", suspect.captureReasonLabel)
        assertNull(suspect.note)
        assertTrue(suspect.parsedFields.contains("疑似重复=午餐"))
    }

    @Test
    fun duplicateSuspectPreservesUserNote() {
        val existing = entry(title = "午餐")
        val candidate = entry(
            id = "bill-1",
            title = "商户订单",
            captureReason = "支付结果自动捕获",
            note = "客户会议"
        )

        val suspect = DedupeEngine().addCandidate(listOf(existing), candidate)
            .pendingEntries.first()

        assertEquals("客户会议", suspect.note)
    }

    @Test
    fun highConfidenceMergePreservesUserNote() {
        val existing = entry(note = "客户会议")
        val automatic = entry(
            id = "bill-1",
            captureReason = "支付结果自动捕获"
        )

        val merged = DedupeEngine().addCandidate(listOf(existing), automatic)
            .pendingEntries.single()

        assertEquals("客户会议", merged.note)
    }

    @Test
    fun differentAmountAndTimeAvoidsFalsePositive() {
        val existing = entry(amountMinor = 3590, transactionTimeText = "2026-07-08 12:20")
        val candidate = entry(
            id = "bill-1",
            amountMinor = 8800,
            transactionTimeText = "2026-07-08 18:50",
            captureReason = "支付结果自动捕获"
        )

        val result = DedupeEngine().addCandidate(listOf(existing), candidate)

        assertEquals(DedupeMatchLevel.NONE, result.matchLevel)
        assertEquals(2, result.pendingEntries.size)
        assertEquals(ConfidenceState.HIGH, result.pendingEntries.first().confidence)
    }

    @Suppress("LongParameterList")
    private fun entry(
        id: String = "pending-1",
        title: String = "午餐",
        amountMinor: Long = 3590,
        transactionTimeText: String = "2026-07-08 12:20",
        source: String = "其他应用",
        kind: String = "支出",
        captureReason: String = "支付结果自动捕获",
        rawEvidence: String = "",
        note: String? = null
    ): ReviewQueueEntry = ReviewQueueEntry(
        id = id,
        title = title,
        amountMinor = amountMinor,
        transactionTimeText = transactionTimeText,
        category = "餐饮",
        fundingAccountLabel = "",
        sourceLabel = source,
        kindLabel = kind,
        captureReasonLabel = captureReason,
        confidence = ConfidenceState.HIGH,
        capturedAtEpochMillis = NOW,
        captureTimeText = "2026-07-08 12:21",
        note = note,
        rawEvidenceText = rawEvidence,
        parsedFields = listOf("商户=$title", "金额=35.90", "类型=$kind")
    )

    private companion object {
        const val NOW = 1_783_468_800_000L
    }
}
