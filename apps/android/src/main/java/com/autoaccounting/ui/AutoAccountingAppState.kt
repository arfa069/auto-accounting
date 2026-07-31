package com.autoaccounting.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.autoaccounting.AppTab
import com.autoaccounting.feature.profile.ProfileDestination
import com.autoaccounting.ui.components.AppBottomNavigationItem

/**
 * Remembers and manages UI navigation state, tab selections, list scroll positions,
 * and bottom bar items for [AutoAccountingApp].
 */
@Stable
internal class AutoAccountingAppState(
    val snackbarHostState: SnackbarHostState,
    val ledgerEntryListState: LazyListState,
    val reportCategoryRankingListState: LazyListState,
    val tabs: List<AppTab>
) {
    val selectedTab: MutableState<AppTab?> = mutableStateOf(null)
    val manualEntryOpen: MutableState<Boolean> = mutableStateOf(false)
    val profileDestination: MutableState<ProfileDestination?> = mutableStateOf(null)

    val bottomNavigationItems: List<AppBottomNavigationItem> = tabs.map { tab ->
        AppBottomNavigationItem(
            key = tab.name,
            label = tab.label,
            iconRes = tab.iconRes
        )
    }
}

@Composable
internal fun rememberAutoAccountingAppState(
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    ledgerEntryListState: LazyListState = rememberLazyListState(),
    reportCategoryRankingListState: LazyListState = rememberLazyListState(),
    tabs: List<AppTab> = remember { AppTab.entries.toList() }
): AutoAccountingAppState {
    return remember(snackbarHostState, ledgerEntryListState, reportCategoryRankingListState, tabs) {
        AutoAccountingAppState(
            snackbarHostState = snackbarHostState,
            ledgerEntryListState = ledgerEntryListState,
            reportCategoryRankingListState = reportCategoryRankingListState,
            tabs = tabs
        )
    }
}
