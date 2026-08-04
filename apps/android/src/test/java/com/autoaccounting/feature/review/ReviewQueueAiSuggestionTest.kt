package com.autoaccounting.feature.review

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import com.autoaccounting.data.local.CategoryEntity
import com.autoaccounting.data.local.ConfidenceState
import com.autoaccounting.data.local.DefaultCategories
import com.autoaccounting.data.local.LocalLedgerRepository
import com.autoaccounting.data.local.TransactionKind
import com.autoaccounting.feature.account.AccountSession
import com.autoaccounting.feature.categorization.AiCategorizationFailureReason
import com.autoaccounting.feature.categorization.AiCategorizationGateway
import com.autoaccounting.feature.categorization.AiCategorizationGatewayResult
import com.autoaccounting.feature.categorization.AiCategorizationPayload
import com.autoaccounting.feature.categorization.AiCategorizationResponse
import com.autoaccounting.feature.categorization.AiCategorizationSettings
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewQueueAiSuggestionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun signedInConsentedUserCanRequestAiCategorySuggestion() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry().copy(category = ""))),
                categories = listOf(
                    category("food", "餐饮", TransactionKind.EXPENSE, 10),
                    category("transport", "交通", TransactionKind.EXPENSE, 20)
                ),
                accountSession = AccountSession.SignedIn(phone = "13800138000", token = "token-1"),
                aiSettings = AiCategorizationSettings(aiConsentGranted = true),
                aiCategorizationGateway = FixedAiCategorizationGateway("交通")
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()

        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AI 建议分类").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("manual-entry-category")
            .performScrollTo()
            .assertTextContains("交通")
    }

    @Test
    fun emptyCategoryListUsesDisplayedSystemDefaultsForAiRequest() {
        val gateway = CapturingAiCategorizationGateway()
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(
                    pendingEntries = listOf(sampleEntry().copy(category = ""))
                ),
                categories = emptyList(),
                accountSession = AccountSession.SignedIn(
                    phone = "13800138000",
                    token = "token-1"
                ),
                aiSettings = AiCategorizationSettings(aiConsentGranted = true),
                aiCategorizationGateway = gateway
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()
        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai-suggest-button").performClick()
        composeRule.waitUntil { gateway.payload.get() != null }

        val expected = DefaultCategories.systemDefaults(0)
            .filter { category ->
                category.kind == TransactionKind.EXPENSE &&
                    category.id != LocalLedgerRepository.DEFAULT_CATEGORY_ID
            }
            .map { category -> DefaultCategories.nameForId(category.id) ?: category.name }
            .distinct()
        val actual = requireNotNull(gateway.payload.get()).categoryCandidates
        assertTrue(actual.isNotEmpty())
        assertEquals(expected, actual)
    }

    @Test
    fun expenseDraftSendsOnlySelectableBusinessCategoriesToAi() {
        val gateway = CapturingAiCategorizationGateway()
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(
                    pendingEntries = listOf(sampleEntry().copy(category = ""))
                ),
                categories = listOf(
                    category("food", "餐饮", TransactionKind.EXPENSE, 10),
                    category("salary", "工资", TransactionKind.INCOME, 20),
                    CategoryEntity(
                        id = LocalLedgerRepository.DEFAULT_CATEGORY_ID,
                        name = "未分类",
                        kind = null,
                        sortOrder = Int.MAX_VALUE,
                        isSystem = true,
                        createdAtEpochMillis = NOW
                    )
                ),
                accountSession = AccountSession.SignedIn(
                    phone = "13800138000",
                    token = "token-1"
                ),
                aiSettings = AiCategorizationSettings(aiConsentGranted = true),
                aiCategorizationGateway = gateway
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()
        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai-suggest-button").performClick()
        composeRule.waitUntil { gateway.payload.get() != null }

        assertEquals(
            listOf("餐饮"),
            requireNotNull(gateway.payload.get()).categoryCandidates
        )
    }

    @Test
    fun aiRequestShowsLoadingAndDisablesDuplicateSubmissionUntilSuccess() {
        val deferred = CompletableDeferred<AiCategorizationGatewayResult>()
        val gateway = DeferredAiCategorizationGateway(deferred)
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry().copy(category = ""))),
                categories = listOf(
                    category("food", "餐饮", TransactionKind.EXPENSE, 10),
                    category("transport", "交通", TransactionKind.EXPENSE, 20)
                ),
                accountSession = AccountSession.SignedIn(phone = "13800138000", token = "token-1"),
                aiSettings = AiCategorizationSettings(aiConsentGranted = true),
                aiCategorizationGateway = gateway
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()
        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai-suggest-button").performClick()
        composeRule.waitUntil { gateway.calls.get() == 1 }

        composeRule.onNodeWithTag("ai-suggest-loading").assertIsDisplayed()
        composeRule.onNodeWithTag("ai-suggest-button").assertIsNotEnabled()
        assertEquals(1, gateway.calls.get())

        deferred.complete(
            AiCategorizationGatewayResult.Success(
                AiCategorizationResponse("交通", "高", "测试建议")
            )
        )
        composeRule.waitUntil {
            composeRule.onAllNodesWithText("AI 建议：交通").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("AI 建议：交通").assertIsDisplayed()
    }

    @Test
    fun aiFailureShowsStableMessage() {
        composeRule.setContent {
            ReviewQueueScreen(
                initialState = ReviewQueueState(pendingEntries = listOf(sampleEntry().copy(category = ""))),
                categories = listOf(category("food", "餐饮", TransactionKind.EXPENSE, 10)),
                accountSession = AccountSession.SignedIn(phone = "13800138000", token = "token-1"),
                aiSettings = AiCategorizationSettings(aiConsentGranted = true),
                aiCategorizationGateway = FixedAiCategorizationGateway(
                    AiCategorizationGatewayResult.Failure(AiCategorizationFailureReason.RATE_LIMITED)
                )
            )
        }
        composeRule.waitForIdle()
        scrollToFirstPendingEntry()
        composeRule.onNodeWithTag("detail-pending-lunch").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai-suggest-button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("AI 请求过于频繁，请稍后重试").assertIsDisplayed()
    }

    private fun category(
        id: String,
        name: String,
        kind: TransactionKind,
        sortOrder: Int
    ): CategoryEntity = CategoryEntity(
        id = id,
        name = name,
        kind = kind,
        sortOrder = sortOrder,
        isSystem = true,
        createdAtEpochMillis = NOW
    )

    private fun scrollToFirstPendingEntry() {
        composeRule.onNodeWithTag("review-queue-list").performScrollToIndex(4)
        composeRule.waitForIdle()
    }

    private fun sampleEntry(
        id: String = "pending-lunch",
        confidence: ConfidenceState = ConfidenceState.NEEDS_REVIEW
    ): ReviewQueueEntry = ReviewQueueEntry(
        id = id,
        title = "午餐",
        amountMinor = 3590,
        transactionTimeText = "2026-07-08 12:20",
        categoryId = "food",
        category = "餐饮",
        fundingAccountId = 42L,
        fundingAccountLabel = "微信零钱",
        sourceLabel = "微信",
        kindLabel = "支出",
        captureReasonLabel = "通知捕获",
        confidence = confidence,
        capturedAtEpochMillis = NOW,
        captureTimeText = "2026-07-08 12:21",
        note = null,
        rawEvidenceText = "微信支付收款凭证 午餐 35.90",
        parsedFields = listOf("商户=午餐", "金额=35.90")
    )

    private companion object {
        const val NOW = 1_783_468_800_000L
    }

    private class FixedAiCategorizationGateway(
        private val result: AiCategorizationGatewayResult
    ) : AiCategorizationGateway {
        constructor(category: String) : this(
            AiCategorizationGatewayResult.Success(
                AiCategorizationResponse(
                    category = category,
                    confidenceLabel = "中",
                    explanation = "测试建议"
                )
            )
        )

        override suspend fun suggestCategory(
            token: String,
            payload: AiCategorizationPayload
        ): AiCategorizationGatewayResult = result
    }

    private class CapturingAiCategorizationGateway : AiCategorizationGateway {
        val payload = AtomicReference<AiCategorizationPayload?>()

        override suspend fun suggestCategory(
            token: String,
            payload: AiCategorizationPayload
        ): AiCategorizationGatewayResult {
            this.payload.set(payload)
            return AiCategorizationGatewayResult.Success(
                AiCategorizationResponse(
                    category = payload.categoryCandidates.first(),
                    confidenceLabel = "中",
                    explanation = "测试建议"
                )
            )
        }
    }

    private class DeferredAiCategorizationGateway(
        private val deferred: CompletableDeferred<AiCategorizationGatewayResult>
    ) : AiCategorizationGateway {
        val calls = AtomicInteger(0)

        override suspend fun suggestCategory(
            token: String,
            payload: AiCategorizationPayload
        ): AiCategorizationGatewayResult {
            calls.incrementAndGet()
            return deferred.await()
        }
    }
}
