package com.bks.feature.review

import com.bks.data.local.ConfidenceState
import com.bks.feature.dedupe.DedupeEngine
import java.math.BigDecimal
import java.math.RoundingMode

data class ReviewQueueState(
    val pendingEntries: List<ReviewQueueEntry> = emptyList(),
    val confirmedEntries: List<ReviewQueueConfirmedEntry> = emptyList(),
    val ignoredEntries: List<ReviewQueueIgnoredEntry> = emptyList(),
    val lastAction: ReviewQueueLastAction? = null,
    val undoEventSequence: Long = 0,
    val nowEpochMillis: Long = SAMPLE_NOW_EPOCH_MILLIS,
    val todayStartEpochMillis: Long = SAMPLE_TODAY_START_EPOCH_MILLIS
) {
    val undoMessage: String?
        get() = lastAction?.message

    val sortedPendingEntries: List<ReviewQueueEntry>
        get() = pendingEntries.sortedWith(
            compareBy<ReviewQueueEntry> { it.reviewPriority }
                .thenByDescending { it.capturedAtEpochMillis }
        )

    val recoverableIgnoredEntries: List<ReviewQueueIgnoredEntry>
        get() = ignoredEntries.filter { it.expiresAtEpochMillis > nowEpochMillis }

    val duplicateSuspectCount: Int
        get() = pendingEntries.count { it.confidence == ConfidenceState.DUPLICATE_SUSPECT }

    val todaysNewlyCapturedCount: Int
        get() = pendingEntries.count { it.capturedAtEpochMillis >= todayStartEpochMillis }
}

data class ReviewQueueEntry(
    val id: String,
    val title: String = "",
    val amountMinor: Long = 0,
    val transactionTimeText: String = "",
    val categoryId: String? = null,
    val category: String = "",
    val fundingAccountId: Long? = null,
    val fundingAccountLabel: String = "",
    val sourceLabel: String = "",
    val kindLabel: String = "",
    val captureReasonLabel: String = "",
    val confidence: ConfidenceState = ConfidenceState.HIGH,
    val capturedAtEpochMillis: Long = 0,
    val captureTimeText: String = "",
    val note: String? = null,
    val rawEvidenceText: String = "",
    val parsedFields: List<String> = emptyList(),
    val originPendingId: String? = null
) {
    val reviewPriority: Int
        get() = when (confidence) {
            ConfidenceState.DUPLICATE_SUSPECT -> 0
            ConfidenceState.NEEDS_REVIEW -> 1
            ConfidenceState.HIGH -> 2
        }
}

internal fun mergeReviewEvidenceText(vararg evidenceTexts: String): String {
    val sections = linkedMapOf<String, String>()
    evidenceTexts.filter(String::isNotBlank).forEach { evidence ->
        parseReviewEvidenceText(evidence).forEach { (label, text) ->
            val existing = sections[label]
            when {
                existing == null -> sections[label] = text
                existing != text -> sections[label] = "$existing\n---\n$text"
            }
        }
    }
    return sections.entries.joinToString("\n\n") { (label, text) -> "[$label]\n$text" }
}

internal fun parseReviewEvidenceText(evidenceText: String): List<Pair<String, String>> {
    val matches = REVIEW_EVIDENCE_HEADER_REGEX.findAll(evidenceText).toList()
    if (matches.isEmpty()) {
        return evidenceText.trim().takeIf(String::isNotBlank)
            ?.let { listOf("原始文本" to it) }
            .orEmpty()
    }
    return matches.mapIndexedNotNull { index, match ->
        val start = match.range.last + 1
        val end = matches.getOrNull(index + 1)?.range?.first ?: evidenceText.length
        evidenceText.substring(start, end).trim().takeIf(String::isNotBlank)?.let { text ->
            match.groupValues[1] to text
        }
    }
}

private val REVIEW_EVIDENCE_HEADER_REGEX = Regex(
    pattern = "(?m)^\\[([^]\\r\\n]+)]\\s*$"
)

data class ReviewQueueConfirmedEntry(
    val id: String,
    val originPendingId: String,
    val entry: ReviewQueueEntry
) {
    companion object {
        fun fromPending(entry: ReviewQueueEntry): ReviewQueueConfirmedEntry =
            ReviewQueueConfirmedEntry(
                id = "ledger-${entry.id}",
                originPendingId = entry.id,
                entry = entry
            )
    }
}

data class ReviewQueueIgnoredEntry(
    val id: String,
    val originalPendingId: String,
    val entry: ReviewQueueEntry,
    val ignoredAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
) {
    companion object {
        fun fromPending(
            entry: ReviewQueueEntry,
            ignoredAtEpochMillis: Long = SAMPLE_NOW_EPOCH_MILLIS,
            expiresAtEpochMillis: Long = ignoredAtEpochMillis + IGNORED_RETENTION_MILLIS
        ): ReviewQueueIgnoredEntry =
            ReviewQueueIgnoredEntry(
                id = "ignored-${entry.id}",
                originalPendingId = entry.id,
                entry = entry,
                ignoredAtEpochMillis = ignoredAtEpochMillis,
                expiresAtEpochMillis = expiresAtEpochMillis
            )
    }
}

sealed interface ReviewQueueAction {
    data class AddPending(val entry: ReviewQueueEntry) : ReviewQueueAction
    data class Confirm(val entryId: String) : ReviewQueueAction
    data class Ignore(val entryId: String) : ReviewQueueAction
    data object UndoLastAction : ReviewQueueAction
    data object DismissUndo : ReviewQueueAction
    data class RecoverIgnored(val ignoredEntryId: String) : ReviewQueueAction
    data class SaveEdit(
        val entryId: String,
        val title: String,
        val amountText: String,
        val timeText: String,
        val transactionKind: String,
        val categoryId: String?,
        val category: String,
        val fundingAccountId: Long?,
        val fundingAccount: String,
        val note: String
    ) : ReviewQueueAction
}

sealed interface ReviewQueueLastAction {
    val eventId: String
    val entry: ReviewQueueEntry
    val message: String

    data class Confirmed(
        override val eventId: String,
        override val entry: ReviewQueueEntry,
        val ledgerEntry: ReviewQueueConfirmedEntry
    ) : ReviewQueueLastAction {
        override val message: String = "已确认 ${entry.title}"
    }

    data class Ignored(
        override val eventId: String,
        override val entry: ReviewQueueEntry,
        val ignoredEntry: ReviewQueueIgnoredEntry
    ) : ReviewQueueLastAction {
        override val message: String = "已忽略 ${entry.title}"
    }
}

fun reduceReviewQueue(
    state: ReviewQueueState,
    action: ReviewQueueAction
): ReviewQueueState = when (action) {
    is ReviewQueueAction.AddPending -> ReviewQueueTransitions.addPendingEntry(state, action.entry)
    is ReviewQueueAction.Confirm -> ReviewQueueTransitions.confirmPendingEntry(state, action.entryId)
    is ReviewQueueAction.Ignore -> ReviewQueueTransitions.ignorePendingEntry(state, action.entryId)
    ReviewQueueAction.UndoLastAction -> ReviewQueueTransitions.undoReviewQueueAction(state)
    ReviewQueueAction.DismissUndo -> state.copy(lastAction = null)
    is ReviewQueueAction.RecoverIgnored ->
        ReviewQueueTransitions.recoverIgnoredEntry(state, action.ignoredEntryId)
    is ReviewQueueAction.SaveEdit -> ReviewQueueTransitions.saveReviewEdit(state, action)
}

private object ReviewQueueTransitions {
    fun addPendingEntry(
        state: ReviewQueueState,
        entry: ReviewQueueEntry
    ): ReviewQueueState {
        val existingEntry = state.pendingEntries.firstOrNull { it.id == entry.id }
        if (existingEntry != null) {
            return if (existingEntry == entry) {
                state
            } else {
                state.copy(
                    pendingEntries = state.pendingEntries.map {
                        if (it.id == entry.id) entry else it
                    },
                    lastAction = null
                )
            }
        }

        val dedupeResult = DedupeEngine().addCandidate(state.pendingEntries, entry)
        return state.copy(
            pendingEntries = dedupeResult.pendingEntries,
            lastAction = null
        )
    }

    fun confirmPendingEntry(
        state: ReviewQueueState,
        entryId: String
    ): ReviewQueueState {
        val entry = state.pendingEntries.firstOrNull { it.id == entryId }
            ?: return state
        val ledgerEntry = ReviewQueueConfirmedEntry.fromPending(entry)
        val nextEventSequence = state.undoEventSequence + 1
        return state.copy(
            pendingEntries = state.pendingEntries.filterNot { it.id == entryId },
            confirmedEntries = state.confirmedEntries + ledgerEntry,
            lastAction = ReviewQueueLastAction.Confirmed(
                eventId = "review-undo-$nextEventSequence",
                entry = entry,
                ledgerEntry = ledgerEntry
            ),
            undoEventSequence = nextEventSequence
        )
    }

    fun ignorePendingEntry(
        state: ReviewQueueState,
        entryId: String
    ): ReviewQueueState {
        val entry = state.pendingEntries.firstOrNull { it.id == entryId }
            ?: return state
        val ignoredEntry = ReviewQueueIgnoredEntry.fromPending(
            entry = entry,
            ignoredAtEpochMillis = state.nowEpochMillis
        )
        val nextEventSequence = state.undoEventSequence + 1
        return state.copy(
            pendingEntries = state.pendingEntries.filterNot { it.id == entryId },
            ignoredEntries = state.ignoredEntries + ignoredEntry,
            lastAction = ReviewQueueLastAction.Ignored(
                eventId = "review-undo-$nextEventSequence",
                entry = entry,
                ignoredEntry = ignoredEntry
            ),
            undoEventSequence = nextEventSequence
        )
    }

    fun undoReviewQueueAction(state: ReviewQueueState): ReviewQueueState =
        when (val lastAction = state.lastAction) {
            is ReviewQueueLastAction.Confirmed -> state.copy(
                pendingEntries = listOf(lastAction.entry) + state.pendingEntries,
                confirmedEntries = state.confirmedEntries.filterNot {
                    it.id == lastAction.ledgerEntry.id
                },
                lastAction = null
            )

            is ReviewQueueLastAction.Ignored -> state.copy(
                pendingEntries = listOf(lastAction.entry) + state.pendingEntries,
                ignoredEntries = state.ignoredEntries.filterNot {
                    it.id == lastAction.ignoredEntry.id
                },
                lastAction = null
            )

            null -> state
        }

    fun recoverIgnoredEntry(
        state: ReviewQueueState,
        ignoredEntryId: String
    ): ReviewQueueState {
        val ignored = state.recoverableIgnoredEntries.firstOrNull { it.id == ignoredEntryId }
            ?: return state
        return state.copy(
            pendingEntries = listOf(ignored.entry) + state.pendingEntries,
            ignoredEntries = state.ignoredEntries.filterNot { it.id == ignored.id },
            lastAction = null
        )
    }

    fun saveReviewEdit(
        state: ReviewQueueState,
        action: ReviewQueueAction.SaveEdit
    ): ReviewQueueState {
        val amountMinor = parseReviewAmountMinor(action.amountText) ?: return state
        return state.copy(
            pendingEntries = state.pendingEntries.map { entry ->
                if (entry.id != action.entryId) {
                    entry
                } else {
                    val normalizedFundingAccount = action.fundingAccount.trim()
                    entry.copy(
                        title = action.title.trim().ifBlank { entry.title },
                        amountMinor = amountMinor,
                        transactionTimeText = action.timeText.trim()
                            .ifBlank { entry.transactionTimeText },
                        kindLabel = action.transactionKind.trim().ifBlank { entry.kindLabel },
                        categoryId = action.categoryId,
                        category = action.category.trim().ifBlank { entry.category },
                        fundingAccountId = action.fundingAccountId,
                        fundingAccountLabel = normalizedFundingAccount,
                        note = action.note.trim().ifBlank { null }
                    )
                }
            }
        )
    }
}

fun formatAmount(amountMinor: Long): String {
    val yuan = amountMinor / 100
    val cents = kotlin.math.abs(amountMinor % 100)
    return "¥$yuan.${cents.toString().padStart(2, '0')}"
}

fun amountMinorToText(amountMinor: Long): String {
    val yuan = amountMinor / 100
    val cents = kotlin.math.abs(amountMinor % 100)
    return "$yuan.${cents.toString().padStart(2, '0')}"
}

fun parseReviewAmountMinor(text: String): Long? = runCatching {
    BigDecimal(text.trim())
        .setScale(2, RoundingMode.HALF_UP)
        .movePointRight(2)
        .longValueExact()
}.getOrNull()

const val IGNORED_RETENTION_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L
const val SAMPLE_NOW_EPOCH_MILLIS: Long = 1_783_468_800_000L
const val SAMPLE_TODAY_START_EPOCH_MILLIS: Long = 1_783_425_600_000L
