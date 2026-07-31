package com.autoaccounting.feature.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.autoaccounting.ui.components.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.autoaccounting.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.DefaultCategories
import com.autoaccounting.data.local.FlowDirection
import com.autoaccounting.data.local.FundingAccountEntity
import com.autoaccounting.data.local.LedgerEntryInput
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.PaymentSource
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.data.local.defaultFlowDirection
import com.autoaccounting.feature.categorization.AiCategorizationFailureReason
import com.autoaccounting.feature.categorization.AiCategorizationResult
import com.autoaccounting.feature.categorization.AiCategorizationSkipReason
import com.autoaccounting.feature.categorization.CategorizationRule
import com.autoaccounting.feature.ledger.LedgerEntryFormState
import com.autoaccounting.feature.ledger.SharedLedgerEntryForm
import com.autoaccounting.feature.ledger.ledgerCategoryOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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

internal fun ReviewQueueEntry.hasCategoryCorrection(category: String): Boolean {
    return category.trim().isNotBlank() && category.trim() != this.category
}

private fun CategoryEntity.reviewDisplayName(): String =
    DefaultCategories.nameForId(id) ?: name

@Composable
internal fun ReviewPendingEntryEditor(
    entry: ReviewQueueEntry,
    availableCategories: List<CategoryEntity>,
    fundingAccounts: List<FundingAccountEntity>,
    modifier: Modifier,
    snackbarHostState: SnackbarHostState,
    onExit: () -> Unit,
    onAiSuggest: suspend (ReviewQueueEntry, List<String>) -> AiCategorizationResult,
    onConfirm: (PendingReviewEdit) -> Unit
) {
    val initial = remember(entry, availableCategories, fundingAccounts) {
        entry.toLedgerEntryFormState(availableCategories, fundingAccounts)
    }
    Box(modifier = modifier.fillMaxSize()) {
        SharedLedgerEntryForm(
            title = "编辑待确认账目",
            initial = initial,
            categories = availableCategories,
            fundingAccounts = fundingAccounts,
            flowDirections = listOf(FlowDirection.OUTFLOW, FlowDirection.INFLOW),
            allowCreateFundingAccount = false,
            saveLabel = "确认入账",
            onExit = onExit,
            onSave = { input ->
                onConfirm(PendingReviewEdit.from(input, availableCategories, fundingAccounts))
            },
            onDelete = null,
            snackbarHostState = snackbarHostState,
            leadingContent = { draft, onDraftChange ->
                ReviewEditorTools(
                    entry = entry,
                    draft = draft,
                    categories = availableCategories,
                    fundingAccounts = fundingAccounts,
                    onDraftChange = onDraftChange,
                    onAiSuggest = onAiSuggest
                )
            }
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp)
        )
    }
}

@Composable
private fun ReviewEditorTools(
    entry: ReviewQueueEntry,
    draft: LedgerEntryFormState,
    categories: List<CategoryEntity>,
    fundingAccounts: List<FundingAccountEntity>,
    onDraftChange: (LedgerEntryFormState) -> Unit,
    onAiSuggest: suspend (ReviewQueueEntry, List<String>) -> AiCategorizationResult
) {
    var showEvidence by remember(entry.id) { mutableStateOf(false) }
    var aiMessage by remember(entry.id) { mutableStateOf<String?>(null) }
    var aiRequestInFlight by remember(entry.id) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val aiCategoryCandidates = remember(
        categories,
        draft.flowDirection,
        draft.transactionKind
    ) {
        ledgerCategoryOptions(
            categories = categories,
            flowDirection = draft.flowDirection,
            transactionKind = draft.transactionKind
        ).asSequence()
            .filterNot { it.id == LocalLedgerRepository.DEFAULT_CATEGORY_ID }
            .map(CategoryEntity::reviewDisplayName)
            .distinct()
            .toList()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showEvidence = !showEvidence }) {
                    Text(if (showEvidence) "收起证据" else "查看证据")
                }
                OutlinedButton(
                    onClick = {
                        if (aiRequestInFlight) return@OutlinedButton
                        val input = runCatching { draft.toInput(System.currentTimeMillis()) }
                            .getOrElse {
                                aiMessage = it.message ?: "当前内容无法获取分类建议"
                                return@OutlinedButton
                            }
                        aiRequestInFlight = true
                        aiMessage = null
                        coroutineScope.launch {
                            try {
                                val result = onAiSuggest(
                                    entry.copy(
                                        title = input.merchantTitle,
                                        amountMinor = input.amountMinor,
                                        transactionTimeText = formatReviewDateTime(
                                            input.transactionTimeEpochMillis,
                                            java.time.ZoneId.systemDefault()
                                        ),
                                        kindLabel = input.transactionKind.toReviewLabel(),
                                        categoryId = input.categoryId,
                                        category = input.categoryId.toReviewCategoryName(categories),
                                        fundingAccountId = input.fundingAccountId,
                                        fundingAccountLabel = fundingAccounts
                                            .firstOrNull { it.id == input.fundingAccountId }
                                            ?.label
                                            .orEmpty(),
                                        note = input.note
                                    ),
                                    aiCategoryCandidates
                                )
                                result.suggestion?.let { suggestion ->
                                    val categoryId = categories.firstOrNull {
                                        it.reviewDisplayName() == suggestion.category
                                    }?.id ?: DefaultCategories.idForName(
                                        suggestion.category,
                                        input.transactionKind
                                    )
                                    if (categoryId == null) {
                                        aiMessage = "AI 建议“${suggestion.category}”不在现有分类中"
                                    } else {
                                        onDraftChange(draft.copy(categoryId = categoryId))
                                        aiMessage = "AI 建议：${suggestion.category}"
                                    }
                                } ?: run {
                                    aiMessage = result.toUserMessage()
                                }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: RuntimeException) {
                                aiMessage = "云端 AI 暂时不可用，请稍后重试"
                            } finally {
                                aiRequestInFlight = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("ai-suggest-button"),
                    enabled = !aiRequestInFlight
                ) {
                    if (aiRequestInFlight) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(18.dp)
                                .testTag("ai-suggest-loading"),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在获取建议")
                    } else {
                        Text("AI 建议分类")
                    }
                }
            }
            aiMessage?.let {
                Text(
                    it,
                    modifier = Modifier.testTag("ai-suggest-message"),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (showEvidence) EvidenceSection(entry)
        }
    }
}

private fun AiCategorizationResult.toUserMessage(): String = when {
    skipReason == AiCategorizationSkipReason.REQUIRES_SIGNED_IN_ACCOUNT ->
        "登录后才能使用云端 AI 分类"
    skipReason == AiCategorizationSkipReason.REQUIRES_AI_CONSENT ->
        "开启云端 AI 后才能获取分类建议"
    failureReason == AiCategorizationFailureReason.BACKEND_NOT_CONFIGURED ->
        "云端 AI 后端地址尚未配置"
    failureReason == AiCategorizationFailureReason.INVALID_SESSION ->
        "登录状态已失效，请重新登录"
    failureReason == AiCategorizationFailureReason.ACCOUNT_DELETION_PENDING ->
        "账号注销冷静期内，云端 AI 已暂停"
    failureReason == AiCategorizationFailureReason.AI_CONSENT_REQUIRED ->
        "请先开启云端 AI 分类"
    failureReason == AiCategorizationFailureReason.ENHANCED_CONTEXT_NOT_AUTHORIZED ->
        "增强上下文授权已失效，请重新确认设置"
    failureReason == AiCategorizationFailureReason.CATEGORY_CANDIDATES_REQUIRED ->
        "暂无可用分类，请先创建或启用分类"
    failureReason == AiCategorizationFailureReason.RATE_LIMITED ->
        "AI 请求过于频繁，请稍后重试"
    failureReason == AiCategorizationFailureReason.SERVICE_UNAVAILABLE ->
        "云端 AI 暂时不可用，请稍后重试"
    failureReason == AiCategorizationFailureReason.NETWORK_FAILURE ->
        "网络连接失败，请检查网络后重试"
    failureReason == AiCategorizationFailureReason.INVALID_RESPONSE ->
        "AI 响应无法解析，请稍后重试"
    else -> "暂时没有 AI 分类建议"
}

private fun ReviewQueueEntry.toLedgerEntryFormState(
    categories: List<CategoryEntity>,
    fundingAccounts: List<FundingAccountEntity>
): LedgerEntryFormState {
    val transactionKind = kindLabel.toTransactionKind()
    val selectedCategoryId = categoryId
        ?: categories.firstOrNull { it.reviewDisplayName() == category }?.id
        ?: DefaultCategories.idForName(category, transactionKind)
        ?: LocalLedgerRepository.DEFAULT_CATEGORY_ID
    val selectedFundingAccountId = fundingAccountId
        ?: fundingAccounts.firstOrNull { it.label == fundingAccountLabel }?.id
    return LedgerEntryFormState(
        flowDirection = transactionKind.defaultFlowDirection(),
        transactionKind = transactionKind,
        amountText = amountMinorToText(amountMinor),
        transactionTimeEpochMillis = parseReviewDateTime(
            transactionTimeText,
            java.time.ZoneId.systemDefault()
        ) ?: capturedAtEpochMillis,
        merchantTitle = title,
        categoryId = selectedCategoryId,
        fundingAccountId = selectedFundingAccountId,
        creatingFundingAccount = false,
        newFundingAccountLabel = "",
        note = note.orEmpty(),
        paymentSource = sourceLabel.toReviewPaymentSource()
    )
}

private fun String?.toReviewCategoryName(categories: List<CategoryEntity>): String =
    this?.let { id ->
        DefaultCategories.nameForId(id)
            ?: categories.firstOrNull { it.id == id }?.name
    } ?: "未分类"

private fun TransactionKind.toReviewLabel(): String = when (this) {
    TransactionKind.EXPENSE -> "支出"
    TransactionKind.INCOME -> "收入"
    TransactionKind.REFUND -> "退款"
    TransactionKind.TRANSFER -> "转账"
    TransactionKind.RED_PACKET -> "红包"
    TransactionKind.REPAYMENT -> "还款"
    TransactionKind.INVESTMENT -> "理财"
    TransactionKind.FEE -> "手续费"
    TransactionKind.OTHER -> "其他"
}

private fun String.toReviewPaymentSource(): PaymentSource? = when (trim()) {
    "微信" -> PaymentSource.WECHAT
    "支付宝" -> PaymentSource.ALIPAY
    else -> null
}

@Composable
private fun EvidenceSection(entry: ReviewQueueEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("证据", fontWeight = FontWeight.SemiBold)
        Text("来源：${entry.sourceLabel}", style = MaterialTheme.typography.bodySmall)
        Text("捕获时间：${entry.captureTimeText}", style = MaterialTheme.typography.bodySmall)
        Text("解析字段", fontWeight = FontWeight.SemiBold)
        entry.parsedFields.forEach { field ->
            Text(field, style = MaterialTheme.typography.bodySmall)
        }
        Text("原始文本", fontWeight = FontWeight.SemiBold)
        Text(entry.rawEvidenceText, style = MaterialTheme.typography.bodySmall)
    }
}
