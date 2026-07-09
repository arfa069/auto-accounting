package com.autoaccounting.feature.billsync

import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.categorization.applyCategorizationSuggestion
import com.autoaccounting.feature.review.ReviewQueueAction
import com.autoaccounting.feature.review.ReviewQueuePersistence
import com.autoaccounting.feature.review.reduceReviewQueue
import kotlinx.coroutines.flow.first

class BillSyncCaptureProcessor(
    private val pipeline: BillSyncPipeline,
    private val reviewQueuePersistence: ReviewQueuePersistence,
    private val preferencesRepository: LocalPreferencesRepository,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun process(
        source: BillSyncSource,
        pageText: String
    ): BillSyncResult {
        val previousState = reviewQueuePersistence.observeState().first()
        val result = pipeline.sync(
            source = source,
            pageText = pageText,
            existingPendingEntries = previousState.pendingEntries,
            capturedAtEpochMillis = clock()
        )
        if (result.errorMessage != null) return result

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
        return result.copy(
            createdEntries = createdEntries,
            mergedEntries = mergedEntries
        )
    }
}
