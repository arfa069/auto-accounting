package com.autoaccounting.feature.ledger

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ReportsScreenLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onlyCategoryRankingScrollsAndRestoresItsPosition() {
        val entries = List(16) { index ->
            LedgerUiEntry(
                id = "category-$index",
                title = "账目 $index",
                amountMinor = 2_000L - index,
                monthKey = "2026-07",
                transactionTimeText = "2026-07-18 12:00",
                category = "分类 $index",
                sourceLabel = "支付宝",
                kindLabel = "支出",
                flowType = LedgerFlowType.EXPENSE
            )
        }
        var showReports by mutableStateOf(true)
        lateinit var rankingListState: LazyListState
        composeRule.setContent {
            rankingListState = rememberLazyListState()
            if (showReports) {
                ReportsScreen(
                    entries = entries,
                    categoryRankingListState = rankingListState
                )
            }
        }

        val chartBounds = composeRule.onNodeWithTag(ReportTestTags.CATEGORY_CHART)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val cashFlowBounds = composeRule.onNodeWithTag(ReportTestTags.CASH_FLOW)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        val rankingBounds = composeRule.onNodeWithTag(ReportTestTags.CATEGORY_RANKING_LIST)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "ranking=$rankingBounds chart=$chartBounds cashFlow=$cashFlowBounds",
            rankingBounds.height > 0f
        )
        composeRule.onNodeWithTag(ReportTestTags.CATEGORY_RANKING_LIST)
            .performScrollToIndex(15)

        composeRule.runOnIdle {
            assertEquals(15, rankingListState.firstVisibleItemIndex)
        }
        assertEquals(
            chartBounds,
            composeRule.onNodeWithTag(ReportTestTags.CATEGORY_CHART)
                .fetchSemanticsNode()
                .boundsInRoot
        )
        assertEquals(
            cashFlowBounds,
            composeRule.onNodeWithTag(ReportTestTags.CASH_FLOW)
                .fetchSemanticsNode()
                .boundsInRoot
        )

        composeRule.runOnIdle { showReports = false }
        composeRule.runOnIdle { showReports = true }

        composeRule.runOnIdle {
            assertEquals(15, rankingListState.firstVisibleItemIndex)
        }
    }
}
