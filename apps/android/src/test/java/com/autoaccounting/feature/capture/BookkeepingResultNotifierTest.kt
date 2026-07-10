package com.autoaccounting.feature.capture

import com.autoaccounting.feature.billsync.BillSyncResult
import com.autoaccounting.feature.billsync.BillSyncStep
import com.autoaccounting.feature.review.ReviewQueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookkeepingResultNotifierTest {
    @Test
    fun categorizedPendingNotificationKeepsLockScreenTextGeneric() {
        val content = BookkeepingResultNotification.PendingCreated(
            key = "pending-1",
            count = 1,
            category = "餐饮"
        ).content()

        assertEquals("已归类为餐饮，待确认", content.text)
        assertEquals("识别到待确认账目", content.publicText)
    }

    @Test
    fun billSyncResultMapsCreatedMergedAndFailedOutcomes() {
        val created = result(
            createdEntries = listOf(ReviewQueueEntry(id = "pending-1", category = "交通"))
        ).toBookkeepingResultNotification("支付宝")
        val merged = result(
            mergedEntries = listOf(ReviewQueueEntry(id = "pending-1"))
        ).toBookkeepingResultNotification("支付宝")
        val failed = result(errorMessage = "missing fields")
            .toBookkeepingResultNotification("支付宝")

        assertTrue(created is BookkeepingResultNotification.PendingCreated)
        assertTrue(merged is BookkeepingResultNotification.DuplicateMerged)
        assertTrue(failed is BookkeepingResultNotification.RecognitionFailed)
    }

    private fun result(
        createdEntries: List<ReviewQueueEntry> = emptyList(),
        mergedEntries: List<ReviewQueueEntry> = emptyList(),
        errorMessage: String? = null
    ): BillSyncResult = BillSyncResult(
        steps = listOf(BillSyncStep.Completed),
        createdEntries = createdEntries,
        mergedEntries = mergedEntries,
        duplicateSkippedCount = mergedEntries.size,
        summary = "test",
        errorMessage = errorMessage
    )
}
