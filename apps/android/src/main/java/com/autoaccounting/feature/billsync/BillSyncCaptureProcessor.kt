package com.autoaccounting.feature.billsync

import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.categorization.applyCategorizationSuggestion
import com.autoaccounting.feature.review.ReviewQueueAction
import com.autoaccounting.feature.review.ReviewQueuePersistence
import com.autoaccounting.feature.review.reduceReviewQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BillSyncCaptureProcessor(
    private val pipeline: BillSyncPipeline,
    private val reviewQueuePersistence: ReviewQueuePersistence,
    private val preferencesRepository: LocalPreferencesRepository,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val captureMutex = Mutex()

    suspend fun process(
        source: BillSyncSource,
        pageText: String
    ): BillSyncResult = processWithReason(source, pageText, "账单同步")

    suspend fun processAutomatic(
        source: BillSyncSource,
        pageText: String
    ): BillSyncResult = processWithReason(source, pageText, "支付结果自动捕获")

    private suspend fun processWithReason(
        source: BillSyncSource,
        pageText: String,
        captureReasonLabel: String
    ): BillSyncResult = captureMutex.withLock {
        reviewQueuePersistence.ensureSystemCategories()
        val previousState = reviewQueuePersistence.observeState().first()
        val existingLedgerEntries = reviewQueuePersistence.ledgerEntriesForDedupe()
        val result = pipeline.sync(
            source = source,
            pageText = pageText,
            existingPendingEntries = previousState.pendingEntries,
            existingLedgerEntries = existingLedgerEntries,
            capturedAtEpochMillis = clock(),
            captureReasonLabel = captureReasonLabel
        )
        if (result.errorMessage != null) return@withLock result

        val rules = preferencesRepository.categorizationRules.first()
        val createdEntries = result.createdEntries.map {
            it.applyCategorizationSuggestion(rules)
        }
        val mergedEntries = result.mergedEntries.map {
            it.applyCategorizationSuggestion(rules)
        }
        val nextState = (mergedEntries + createdEntries).fold(previousState) { state, entry ->
            reduceReviewQueue(state, ReviewQueueAction.AddPending(entry))
        }
        reviewQueuePersistence.persistTransition(previousState, nextState)
        result.copy(
            createdEntries = createdEntries,
            mergedEntries = mergedEntries
        )
    }
}
