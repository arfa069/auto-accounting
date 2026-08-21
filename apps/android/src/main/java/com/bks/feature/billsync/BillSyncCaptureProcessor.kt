package com.bks.feature.billsync

import com.bks.data.local.LocalPreferencesRepository
import com.bks.feature.categorization.applyCategorizationSuggestion
import com.bks.feature.review.ReviewQueueAction
import com.bks.feature.review.ReviewQueueCaptureCoordinator
import com.bks.feature.review.ReviewQueuePersistence
import com.bks.feature.review.reduceReviewQueue
import kotlinx.coroutines.flow.first

class BillSyncCaptureProcessor(
    private val pipeline: BillSyncPipeline,
    private val reviewQueuePersistence: ReviewQueuePersistence,
    private val preferencesRepository: LocalPreferencesRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val captureCoordinator: ReviewQueueCaptureCoordinator = ReviewQueueCaptureCoordinator.Shared
) {
    suspend fun process(pageText: String): BillSyncResult = captureCoordinator.serialize {
        reviewQueuePersistence.ensureSystemCategories()
        val previousState = reviewQueuePersistence.observeState().first()
        val result = pipeline.sync(
            pageText = pageText,
            existingPendingEntries = previousState.pendingEntries,
            existingLedgerEntries = reviewQueuePersistence.ledgerEntriesForDedupe(),
            existingIgnoredEntries = previousState.ignoredEntries.map { it.entry },
            capturedAtEpochMillis = clock()
        )
        if (!result.recognized) return@serialize result

        val rules = preferencesRepository.categorizationRules.first()
        val createdEntries = result.createdEntries.map { it.applyCategorizationSuggestion(rules) }
        val mergedEntries = result.mergedEntries.map { it.applyCategorizationSuggestion(rules) }
        if (createdEntries.isNotEmpty() || mergedEntries.isNotEmpty()) {
            val nextState = (mergedEntries + createdEntries).fold(previousState) { state, entry ->
                reduceReviewQueue(state, ReviewQueueAction.AddPending(entry))
            }
            reviewQueuePersistence.persistTransition(previousState, nextState)
        }
        result.copy(createdEntries = createdEntries, mergedEntries = mergedEntries)
    }
}
