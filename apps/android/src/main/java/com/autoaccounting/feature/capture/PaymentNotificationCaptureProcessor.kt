package com.autoaccounting.feature.capture

import com.autoaccounting.data.local.LocalPreferencesRepository
import com.autoaccounting.feature.categorization.applyCategorizationSuggestion
import com.autoaccounting.feature.dedupe.DedupeEngine
import com.autoaccounting.feature.dedupe.DedupeMatchLevel
import com.autoaccounting.feature.review.ReviewQueueAction
import com.autoaccounting.feature.review.ReviewQueueCaptureCoordinator
import com.autoaccounting.feature.review.ReviewQueueEntry
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
        val ledgerEntriesForDedupe = reviewQueuePersistence.ledgerEntriesForDedupe()
            .filterNot { ledgerEntry ->
                ledgerEntry.isPriorRedPacketLedgerFor(categorizedEntry)
            }
        val ledgerDedupeResult = DedupeEngine().addCandidate(
            ledgerEntriesForDedupe,
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
        val hasPriorRedPacketNotification = previousState.pendingEntries.any { existing ->
            existing.isPriorRedPacketNotificationFor(candidateAfterLedgerCheck)
        }
        val pendingEntriesForDedupe = if (hasPriorRedPacketNotification) {
            previousState.pendingEntries.filterNot { existing ->
                existing.isPriorRedPacketNotificationFor(candidateAfterLedgerCheck)
            }
        } else {
            previousState.pendingEntries
        }
        val dedupeResult = DedupeEngine().addCandidate(
            pendingEntriesForDedupe,
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
        val nextState = if (
            hasPriorRedPacketNotification &&
            entryToPersist.id == candidateAfterLedgerCheck.id
        ) {
            previousState.copy(
                pendingEntries = listOf(entryToPersist) +
                    previousState.pendingEntries.filterNot { it.id == entryToPersist.id },
                lastAction = null
            )
        } else {
            reduceReviewQueue(
                previousState,
                ReviewQueueAction.AddPending(entryToPersist)
            )
        }
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

private fun ReviewQueueEntry.isPriorRedPacketNotificationFor(
    candidate: ReviewQueueEntry
): Boolean =
    candidate.isWechatRedPacketNotification &&
        id != candidate.id &&
        hasNotificationCaptureEvidence &&
        sourceLabel == candidate.sourceLabel &&
        title == candidate.title &&
        amountMinor == candidate.amountMinor &&
        kindLabel == candidate.kindLabel

private fun ReviewQueueEntry.isPriorRedPacketLedgerFor(
    candidate: ReviewQueueEntry
): Boolean =
    candidate.isWechatRedPacketNotification &&
        captureReasonLabel == "已入账" &&
        originPendingId != null &&
        originPendingId != candidate.id &&
        sourceLabel == candidate.sourceLabel &&
        title == candidate.title &&
        amountMinor == candidate.amountMinor &&
        kindLabel == candidate.kindLabel

private val ReviewQueueEntry.isWechatRedPacketNotification: Boolean
    get() = sourceLabel == "微信" &&
        captureReasonLabel == "通知捕获" &&
        rawEvidenceText.contains("红包")

private val ReviewQueueEntry.hasNotificationCaptureEvidence: Boolean
    get() = captureReasonLabel == "通知捕获" ||
        parsedFields.contains("证据来源=通知捕获")
