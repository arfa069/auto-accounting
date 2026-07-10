package com.autoaccounting.feature.capture

import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.categorization.applyCategorizationSuggestion
import com.autoaccounting.feature.dedupe.DedupeEngine
import com.autoaccounting.feature.dedupe.DedupeMatchLevel
import com.autoaccounting.feature.review.ReviewQueueAction
import com.autoaccounting.feature.review.ReviewQueueCaptureCoordinator
import com.autoaccounting.feature.review.ReviewQueuePersistence
import com.autoaccounting.feature.review.ReviewQueueState
import com.autoaccounting.feature.review.reduceReviewQueue
import kotlinx.coroutines.flow.first

data class PaymentNotificationProcessResult(
    val state: ReviewQueueState,
    val notification: BookkeepingResultNotification
)

class PaymentNotificationCaptureProcessor(
    private val pipeline: NotificationCapturePipeline,
    private val reviewQueuePersistence: ReviewQueuePersistence,
    private val preferencesRepository: LocalPreferencesRepository,
    private val captureCoordinator: ReviewQueueCaptureCoordinator =
        ReviewQueueCaptureCoordinator.Shared
) {
    suspend fun process(event: PaymentNotificationEvent): ReviewQueueState? =
        processWithResult(event)?.state

    suspend fun processWithResult(
        event: PaymentNotificationEvent
    ): PaymentNotificationProcessResult? = captureCoordinator.serialize {
        reviewQueuePersistence.ensureSystemCategories()
        val entry = pipeline.capture(event) ?: return@serialize null
        val rules = preferencesRepository.categorizationRules.first()
        val categorizedEntry = entry.applyCategorizationSuggestion(rules)
        val previousState = reviewQueuePersistence.observeState().first()
        val ledgerDedupeResult = DedupeEngine().addCandidate(
            reviewQueuePersistence.ledgerEntriesForDedupe(),
            categorizedEntry
        )
        if (ledgerDedupeResult.matchLevel == DedupeMatchLevel.HIGH_CONFIDENCE) {
            return@serialize PaymentNotificationProcessResult(
                state = previousState,
                notification = BookkeepingResultNotification.DuplicateMerged(categorizedEntry.id)
            )
        }
        val candidateAfterLedgerCheck = if (
            ledgerDedupeResult.matchLevel == DedupeMatchLevel.LOW_CONFIDENCE
        ) {
            ledgerDedupeResult.pendingEntries.first { it.id == categorizedEntry.id }
        } else {
            categorizedEntry
        }
        val dedupeResult = DedupeEngine().addCandidate(
            previousState.pendingEntries,
            candidateAfterLedgerCheck
        )
        val entryToPersist = when (dedupeResult.matchLevel) {
            DedupeMatchLevel.HIGH_CONFIDENCE -> {
                val matchedId = dedupeResult.matchedEntry?.id ?: return@serialize null
                dedupeResult.pendingEntries.first { it.id == matchedId }
            }

            DedupeMatchLevel.NONE,
            DedupeMatchLevel.LOW_CONFIDENCE ->
                dedupeResult.pendingEntries.first { it.id == candidateAfterLedgerCheck.id }
        }
        val nextState = reduceReviewQueue(
            previousState,
            ReviewQueueAction.AddPending(entryToPersist)
        )
        reviewQueuePersistence.persistTransition(previousState, nextState)

        val notification = if (dedupeResult.matchLevel == DedupeMatchLevel.HIGH_CONFIDENCE) {
            BookkeepingResultNotification.DuplicateMerged(entryToPersist.id)
        } else {
            BookkeepingResultNotification.PendingCreated(
                key = entryToPersist.id,
                count = 1,
                category = entryToPersist.category.takeIf { it.isNotBlank() }
            )
        }
        PaymentNotificationProcessResult(nextState, notification)
    }
}
