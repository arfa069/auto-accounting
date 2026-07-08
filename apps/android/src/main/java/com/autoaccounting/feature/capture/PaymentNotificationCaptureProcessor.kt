package com.autoaccounting.feature.capture

import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.categorization.applyCategorizationSuggestion
import com.autoaccounting.feature.review.ReviewQueueAction
import com.autoaccounting.feature.review.ReviewQueuePersistence
import com.autoaccounting.feature.review.ReviewQueueState
import com.autoaccounting.feature.review.reduceReviewQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PaymentNotificationCaptureProcessor(
    private val pipeline: NotificationCapturePipeline,
    private val reviewQueuePersistence: ReviewQueuePersistence,
    private val preferencesRepository: LocalPreferencesRepository
) {
    private val captureMutex = Mutex()

    suspend fun process(event: PaymentNotificationEvent): ReviewQueueState? =
        captureMutex.withLock {
            val entry = pipeline.capture(event) ?: return@withLock null
            val rules = preferencesRepository.categorizationRules.first()
            val categorizedEntry = entry.applyCategorizationSuggestion(rules)
            val previousState = reviewQueuePersistence.observeState().first()
            val nextState = reduceReviewQueue(
                previousState,
                ReviewQueueAction.AddPending(categorizedEntry)
            )
            reviewQueuePersistence.persistTransition(previousState, nextState)
            nextState
        }
}
