package com.autoaccounting.feature.review

import com.autoaccounting.data.local.ConfidenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewQueueStateTest {
    @Test
    fun confirmMovesPendingEntryIntoLedgerCandidate() {
        val state = ReviewQueueState(pendingEntries = listOf(sampleEntry()))

        val next = reduceReviewQueue(state, ReviewQueueAction.Confirm("pending-lunch"))

        assertTrue(next.pendingEntries.isEmpty())
        assertEquals(listOf("pending-lunch"), next.confirmedEntries.map { it.originPendingId })
        assertEquals("已确认 午餐", next.undoMessage)
    }

    @Test
    fun sameTitleActionsCreateDistinctUndoEvents() {
        val state = ReviewQueueState(
            pendingEntries = listOf(
                sampleEntry(id = "same-title-1"),
                sampleEntry(id = "same-title-2")
            )
        )

        val first = reduceReviewQueue(state, ReviewQueueAction.Confirm("same-title-1"))
        val second = reduceReviewQueue(first, ReviewQueueAction.Confirm("same-title-2"))

        assertEquals("已确认 午餐", first.undoMessage)
        assertEquals("已确认 午餐", second.undoMessage)
        assertFalse(first.lastAction?.eventId == second.lastAction?.eventId)
    }

    @Test
    fun ignoreMovesPendingEntryIntoRecoverableList() {
        val state = ReviewQueueState(
            pendingEntries = listOf(sampleEntry()),
            nowEpochMillis = NOW
        )

        val next = reduceReviewQueue(state, ReviewQueueAction.Ignore("pending-lunch"))

        assertTrue(next.pendingEntries.isEmpty())
        assertEquals(listOf("pending-lunch"), next.ignoredEntries.map { it.originalPendingId })
        assertEquals(NOW, next.ignoredEntries.single().ignoredAtEpochMillis)
        assertEquals(NOW + THIRTY_DAYS_MILLIS, next.ignoredEntries.single().expiresAtEpochMillis)
        assertEquals("已忽略 午餐", next.undoMessage)
    }

    @Test
    fun undoRestoresLastConfirmedOrIgnoredPendingEntry() {
        val ignored = reduceReviewQueue(
            ReviewQueueState(pendingEntries = listOf(sampleEntry())),
            ReviewQueueAction.Ignore("pending-lunch")
        )

        val restored = reduceReviewQueue(ignored, ReviewQueueAction.UndoLastAction)

        assertEquals(listOf("pending-lunch"), restored.pendingEntries.map { it.id })
        assertTrue(restored.ignoredEntries.isEmpty())
        assertNull(restored.undoMessage)
    }

    @Test
    fun editUpdatesPendingEntryDetails() {
        val state = ReviewQueueState(pendingEntries = listOf(sampleEntry()))

        val next = reduceReviewQueue(
            state,
            ReviewQueueAction.SaveEdit(
                entryId = "pending-lunch",
                title = "工作餐",
                amountText = "45.80",
                timeText = "2026-07-08 12:30",
                transactionKind = "退款",
                category = "餐饮",
                fundingAccount = "微信零钱",
                note = "客户会议"
            )
        )

        val edited = next.pendingEntries.single()
        assertEquals("工作餐", edited.title)
        assertEquals(4580, edited.amountMinor)
        assertEquals("2026-07-08 12:30", edited.transactionTimeText)
        assertEquals("退款", edited.kindLabel)
        assertEquals("餐饮", edited.category)
        assertEquals(42L, edited.fundingAccountId)
        assertEquals("微信零钱", edited.fundingAccountLabel)
        assertEquals("客户会议", edited.note)
    }

    @Test
    fun editClearsFundingAccountIdWhenFundingAccountLabelChanges() {
        val state = ReviewQueueState(pendingEntries = listOf(sampleEntry()))

        val next = reduceReviewQueue(
            state,
            ReviewQueueAction.SaveEdit(
                entryId = "pending-lunch",
                title = "午餐",
                amountText = "35.90",
                timeText = "2026-07-08 12:20",
                transactionKind = "支出",
                category = "餐饮",
                fundingAccount = "支付宝余额",
                note = ""
            )
        )

        val edited = next.pendingEntries.single()
        assertNull(edited.fundingAccountId)
        assertEquals("支付宝余额", edited.fundingAccountLabel)
    }

    @Test
    fun editCanClearFundingAccountLabelAndItsId() {
        val state = ReviewQueueState(pendingEntries = listOf(sampleEntry()))

        val next = reduceReviewQueue(
            state,
            ReviewQueueAction.SaveEdit(
                entryId = "pending-lunch",
                title = "午餐",
                amountText = "35.90",
                timeText = "2026-07-08 12:20",
                transactionKind = "支出",
                category = "餐饮",
                fundingAccount = "",
                note = ""
            )
        )

        val edited = next.pendingEntries.single()
        assertNull(edited.fundingAccountId)
        assertEquals("", edited.fundingAccountLabel)
    }

    @Test
    fun invalidAmountDoesNotSilentlySaveOtherEditedFields() {
        val original = sampleEntry()
        val state = ReviewQueueState(pendingEntries = listOf(original))

        val next = reduceReviewQueue(
            state,
            ReviewQueueAction.SaveEdit(
                entryId = "pending-lunch",
                title = "工作餐",
                amountText = "abc",
                timeText = "2026-07-08 12:30",
                transactionKind = "退款",
                category = "餐饮",
                fundingAccount = "微信零钱",
                note = "客户会议"
            )
        )

        assertEquals(original, next.pendingEntries.single())
    }

    @Test
    fun recoverIgnoredEntryMovesItBackToPending() {
        val ignored = ReviewQueueIgnoredEntry.fromPending(sampleEntry())
        val state = ReviewQueueState(
            pendingEntries = emptyList(),
            ignoredEntries = listOf(ignored)
        )

        val next = reduceReviewQueue(state, ReviewQueueAction.RecoverIgnored(ignored.id))

        assertEquals(listOf("pending-lunch"), next.pendingEntries.map { it.id })
        assertTrue(next.ignoredEntries.isEmpty())
    }

    @Test
    fun expiredIgnoredEntryCannotBeRecovered() {
        val expired = ReviewQueueIgnoredEntry.fromPending(
            entry = sampleEntry(),
            ignoredAtEpochMillis = NOW - THIRTY_DAYS_MILLIS - 1,
            expiresAtEpochMillis = NOW - 1
        )
        val state = ReviewQueueState(
            pendingEntries = emptyList(),
            ignoredEntries = listOf(expired),
            nowEpochMillis = NOW
        )

        val next = reduceReviewQueue(state, ReviewQueueAction.RecoverIgnored(expired.id))

        assertTrue(next.pendingEntries.isEmpty())
        assertEquals(listOf(expired), next.ignoredEntries)
        assertTrue(state.recoverableIgnoredEntries.isEmpty())
    }

    @Test
    fun pendingEntriesAreSortedByRiskThenCaptureTime() {
        val state = ReviewQueueState(
            pendingEntries = listOf(
                sampleEntry(id = "quick-new", confidence = ConfidenceState.HIGH, capturedAt = NOW + 3),
                sampleEntry(id = "low-old", confidence = ConfidenceState.NEEDS_REVIEW, capturedAt = NOW + 1),
                sampleEntry(id = "duplicate-old", confidence = ConfidenceState.DUPLICATE_SUSPECT, capturedAt = NOW),
                sampleEntry(id = "duplicate-new", confidence = ConfidenceState.DUPLICATE_SUSPECT, capturedAt = NOW + 2)
            )
        )

        assertEquals(
            listOf("duplicate-new", "duplicate-old", "low-old", "quick-new"),
            state.sortedPendingEntries.map { it.id }
        )
    }

    @Test
    fun addPendingAutoMergesHighConfidenceDuplicateEvidence() {
        val state = ReviewQueueState(pendingEntries = listOf(sampleEntry()))
        val billSyncDuplicate = sampleEntry(
            id = "bill-lunch",
            confidence = ConfidenceState.HIGH
        ).copy(
            captureReasonLabel = "账单同步",
            rawEvidenceText = "微信账单 午餐 支出 35.90",
            parsedFields = listOf("来源=微信", "商户=午餐", "金额=35.90", "类型=支出")
        )

        val next = reduceReviewQueue(state, ReviewQueueAction.AddPending(billSyncDuplicate))

        assertEquals(1, next.pendingEntries.size)
        val merged = next.pendingEntries.single()
        assertEquals("pending-lunch", merged.id)
        assertEquals("重复合并", merged.captureReasonLabel)
        assertEquals(ConfidenceState.HIGH, merged.confidence)
        assertTrue(merged.rawEvidenceText.contains("微信支付收款凭证"))
        assertTrue(merged.rawEvidenceText.contains("微信账单"))
    }

    private fun sampleEntry(
        id: String = "pending-lunch",
        confidence: ConfidenceState = ConfidenceState.NEEDS_REVIEW,
        capturedAt: Long = NOW
    ): ReviewQueueEntry = ReviewQueueEntry(
        id = id,
        title = "午餐",
        amountMinor = 3590,
        transactionTimeText = "2026-07-08 12:20",
        category = "餐饮",
        fundingAccountId = 42L,
        fundingAccountLabel = "微信零钱",
        sourceLabel = "微信",
        kindLabel = "支出",
        captureReasonLabel = "通知捕获",
        confidence = confidence,
        capturedAtEpochMillis = capturedAt,
        captureTimeText = "2026-07-08 12:21",
        note = null,
        rawEvidenceText = "微信支付收款凭证 午餐 35.90",
        parsedFields = listOf("商户=午餐", "金额=35.90")
    )

    private companion object {
        const val NOW = 1_783_468_800_000L
        const val THIRTY_DAYS_MILLIS = 30L * 24L * 60L * 60L * 1000L
    }
}
