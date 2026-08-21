package com.bks.feature.review

internal data class ReviewQueueListActions(
    val onAction: (ReviewQueueAction) -> Unit,
    val onEdit: (ReviewQueueEntry) -> Unit,
    val onShowIgnoredList: () -> Unit,
    val onNavigateHome: () -> Unit
)
