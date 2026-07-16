package com.autoaccounting.feature.review

import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.feature.dedupe.DedupeEngine
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
        val category: String,
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
    is ReviewQueueAction.AddPending -> {
        val existingEntry = state.pendingEntries.firstOrNull { it.id == action.entry.id }
        if (existingEntry != null) {
            if (existingEntry == action.entry) {
                state
            } else {
                state.copy(
                    pendingEntries = state.pendingEntries.map { entry ->
                        if (entry.id == action.entry.id) action.entry else entry
                    },
                    lastAction = null
                )
            }
        } else {
            val dedupeResult = DedupeEngine().addCandidate(state.pendingEntries, action.entry)
            state.copy(
                pendingEntries = dedupeResult.pendingEntries,
                lastAction = null
            )
        }
    }

    is ReviewQueueAction.Confirm -> {
        val entry = state.pendingEntries.firstOrNull { it.id == action.entryId }
            ?: return state
        val ledgerEntry = ReviewQueueConfirmedEntry.fromPending(entry)
        val nextEventSequence = state.undoEventSequence + 1
        state.copy(
            pendingEntries = state.pendingEntries.filterNot { it.id == action.entryId },
            confirmedEntries = state.confirmedEntries + ledgerEntry,
            lastAction = ReviewQueueLastAction.Confirmed(
                eventId = "review-undo-$nextEventSequence",
                entry = entry,
                ledgerEntry = ledgerEntry
            ),
            undoEventSequence = nextEventSequence
        )
    }

    is ReviewQueueAction.Ignore -> {
        val entry = state.pendingEntries.firstOrNull { it.id == action.entryId }
            ?: return state
        val ignoredEntry = ReviewQueueIgnoredEntry.fromPending(
            entry = entry,
            ignoredAtEpochMillis = state.nowEpochMillis
        )
        val nextEventSequence = state.undoEventSequence + 1
        state.copy(
            pendingEntries = state.pendingEntries.filterNot { it.id == action.entryId },
            ignoredEntries = state.ignoredEntries + ignoredEntry,
            lastAction = ReviewQueueLastAction.Ignored(
                eventId = "review-undo-$nextEventSequence",
                entry = entry,
                ignoredEntry = ignoredEntry
            ),
            undoEventSequence = nextEventSequence
        )
    }

    ReviewQueueAction.UndoLastAction -> when (val lastAction = state.lastAction) {
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

    ReviewQueueAction.DismissUndo -> state.copy(lastAction = null)

    is ReviewQueueAction.RecoverIgnored -> {
        val ignored = state.recoverableIgnoredEntries.firstOrNull { it.id == action.ignoredEntryId }
            ?: return state
        state.copy(
            pendingEntries = listOf(ignored.entry) + state.pendingEntries,
            ignoredEntries = state.ignoredEntries.filterNot { it.id == ignored.id },
            lastAction = null
        )
    }

    is ReviewQueueAction.SaveEdit -> {
        val amountMinor = parseReviewAmountMinor(action.amountText) ?: return state
        state.copy(
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
                        category = action.category.trim().ifBlank { entry.category },
                        fundingAccountId = entry.fundingAccountId.takeIf {
                            normalizedFundingAccount == entry.fundingAccountLabel.trim()
                        },
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

fun sampleReviewQueueEntries(): List<ReviewQueueEntry> = listOf(
    ReviewQueueEntry(
        id = "pending-duplicate",
        title = "相似订单待核对",
        amountMinor = 12800,
        transactionTimeText = "2026-07-08 09:34",
        category = "购物",
        fundingAccountLabel = "支付宝余额",
        sourceLabel = "支付宝",
        kindLabel = "支出",
        captureReasonLabel = "补录账单",
        confidence = ConfidenceState.DUPLICATE_SUSPECT,
        capturedAtEpochMillis = SAMPLE_NOW_EPOCH_MILLIS - 5 * 60_000,
        captureTimeText = "2026-07-08 09:36",
        note = "可能和补录账单记录重复",
        rawEvidenceText = "支付宝账单 同步记录 相似订单待核对 128.00",
        parsedFields = listOf("来源=支付宝", "金额=128.00", "类型=支出")
    ),
    ReviewQueueEntry(
        id = "pending-lunch",
        title = "午餐",
        amountMinor = 3590,
        transactionTimeText = "2026-07-08 12:20",
        category = "餐饮",
        fundingAccountLabel = "微信零钱",
        sourceLabel = "微信",
        kindLabel = "支出",
        captureReasonLabel = "通知捕获",
        confidence = ConfidenceState.NEEDS_REVIEW,
        capturedAtEpochMillis = SAMPLE_NOW_EPOCH_MILLIS - 2 * 60_000,
        captureTimeText = "2026-07-08 12:21",
        note = null,
        rawEvidenceText = "微信支付收款凭证 午餐 35.90",
        parsedFields = listOf("商户=午餐", "金额=35.90", "类型=支出")
    ),
    ReviewQueueEntry(
        id = "pending-ride",
        title = "地铁出行",
        amountMinor = 600,
        transactionTimeText = "2026-07-08 08:10",
        category = "交通",
        fundingAccountLabel = "支付宝余额",
        sourceLabel = "支付宝",
        kindLabel = "支出",
        captureReasonLabel = "通知捕获",
        confidence = ConfidenceState.HIGH,
        capturedAtEpochMillis = SAMPLE_NOW_EPOCH_MILLIS - 40 * 60_000,
        captureTimeText = "2026-07-08 08:11",
        note = "通勤"
    ),
    ReviewQueueEntry(
        id = "pending-refund",
        title = "退款到账",
        amountMinor = 2590,
        transactionTimeText = "2026-07-07 21:10",
        category = "退款",
        fundingAccountLabel = "微信零钱",
        sourceLabel = "微信",
        kindLabel = "退款",
        captureReasonLabel = "补录账单",
        confidence = ConfidenceState.HIGH,
        capturedAtEpochMillis = SAMPLE_TODAY_START_EPOCH_MILLIS - 15 * 60_000,
        captureTimeText = "2026-07-07 21:12",
        note = null,
        rawEvidenceText = "微信账单 退款到账 25.90",
        parsedFields = listOf("来源=微信", "金额=25.90", "类型=退款")
    )
)

const val IGNORED_RETENTION_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L
const val SAMPLE_NOW_EPOCH_MILLIS: Long = 1_783_468_800_000L
const val SAMPLE_TODAY_START_EPOCH_MILLIS: Long = 1_783_425_600_000L
