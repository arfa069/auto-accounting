package com.autoaccounting.feature.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.DefaultCategories
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.LedgerEntryInput
import com.autoaccounting.feature.categorization.AiCategorizationResult
import com.autoaccounting.feature.categorization.CategorizationRule
import com.autoaccounting.feature.ledger.LedgerEntryFormState
import com.autoaccounting.feature.ledger.LedgerEntryFormConfig
import com.autoaccounting.feature.ledger.SharedLedgerEntryForm

internal data class PendingCategoryRuleSave(
    val entry: ReviewQueueEntry,
    val edit: PendingReviewEdit
) {
    fun toCategorizationRule(): CategorizationRule = CategorizationRule(
        id = "rule-${entry.id}-${entry.capturedAtEpochMillis}",
        merchantContains = edit.title.trim(),
        sourceLabel = entry.sourceLabel,
        transactionKind = edit.transactionKind.trim(),
        category = edit.category.trim(),
        updatedAtEpochMillis = entry.capturedAtEpochMillis
    )
}

internal data class PendingReviewEdit(
    val title: String,
    val amountText: String,
    val timeText: String,
    val transactionKind: String,
    val categoryId: String?,
    val category: String,
    val fundingAccountId: Long?,
    val fundingAccount: String,
    val note: String
) {
    fun toSaveAction(entryId: String): ReviewQueueAction.SaveEdit = ReviewQueueAction.SaveEdit(
        entryId = entryId,
        title = title,
        amountText = amountText,
        timeText = timeText,
        transactionKind = transactionKind,
        categoryId = categoryId,
        category = category,
        fundingAccountId = fundingAccountId,
        fundingAccount = fundingAccount,
        note = note
    )

    companion object {
        fun from(
            input: LedgerEntryInput,
            categories: List<CategoryEntity>,
            fundingAccounts: List<FundingAccountEntity>
        ): PendingReviewEdit = PendingReviewEdit(
            title = input.merchantTitle,
            amountText = amountMinorToText(input.amountMinor),
            timeText = formatReviewDateTime(
                input.transactionTimeEpochMillis,
                java.time.ZoneId.systemDefault()
            ),
            transactionKind = input.transactionKind.toReviewLabel(),
            categoryId = input.categoryId,
            category = input.categoryId.toReviewCategoryName(categories),
            fundingAccountId = input.fundingAccountId,
            fundingAccount = fundingAccounts
                .firstOrNull { it.id == input.fundingAccountId }
                ?.label
                .orEmpty(),
            note = input.note.orEmpty()
        )
    }
}

internal data class ReviewPendingEntryEditorConfig(
    val modifier: Modifier,
    val snackbarHostState: SnackbarHostState,
    val onExit: () -> Unit,
    val onAiSuggest: suspend (ReviewQueueEntry, List<String>) -> AiCategorizationResult,
    val onConfirm: (PendingReviewEdit) -> Unit
)

internal data class ReviewEditorToolActions(
    val onDraftChange: (LedgerEntryFormState) -> Unit,
    val onAiSuggest: suspend (ReviewQueueEntry, List<String>) -> AiCategorizationResult
)

internal fun ReviewQueueEntry.hasCategoryCorrection(category: String): Boolean {
    return category.trim().isNotBlank() && category.trim() != this.category
}

internal fun CategoryEntity.reviewDisplayName(): String =
    DefaultCategories.nameForId(id) ?: name

@Composable
internal fun ReviewPendingEntryEditor(
    entry: ReviewQueueEntry,
    availableCategories: List<CategoryEntity>,
    fundingAccounts: List<FundingAccountEntity>,
    config: ReviewPendingEntryEditorConfig
) {
    val initial = remember(entry, availableCategories, fundingAccounts) {
        entry.toLedgerEntryFormState(availableCategories, fundingAccounts)
    }
    Box(modifier = config.modifier.fillMaxSize()) {
        SharedLedgerEntryForm(
            title = "编辑待确认账目",
            initial = initial,
            categories = availableCategories,
            fundingAccounts = fundingAccounts,
            config = LedgerEntryFormConfig(
                flowDirections = listOf(FlowDirection.OUTFLOW, FlowDirection.INFLOW),
                allowCreateFundingAccount = false,
                saveLabel = "确认入账",
                onExit = config.onExit,
                onSave = { input ->
                    config.onConfirm(PendingReviewEdit.from(input, availableCategories, fundingAccounts))
                },
                onDelete = null,
                snackbarHostState = config.snackbarHostState,
                showDefaultFundingAccountHint = true,
                leadingContent = { draft, onDraftChange ->
                    ReviewEditorTools(
                        entry = entry,
                        draft = draft,
                        categories = availableCategories,
                        fundingAccounts = fundingAccounts,
                        actions = ReviewEditorToolActions(
                            onDraftChange = onDraftChange,
                            onAiSuggest = config.onAiSuggest
                        )
                    )
                }
            )
        )
        SnackbarHost(
            hostState = config.snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp)
        )
    }
}
