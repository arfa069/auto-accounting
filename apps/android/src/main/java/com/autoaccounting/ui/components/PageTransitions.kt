package com.autoaccounting.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

private const val PAGE_TRANSITION_DURATION_MILLIS = 300

@Composable
fun <T> SlidePageTransition(
    targetState: T,
    modifier: Modifier = Modifier,
    animateOutgoingContent: Boolean = true,
    content: @Composable (T) -> Unit
) {
    AnimatedContent(
        targetState = targetState,
        transitionSpec = {
            slideInHorizontally(
                animationSpec = tween(PAGE_TRANSITION_DURATION_MILLIS),
                initialOffsetX = ::pageEnterOffsetX
            ) togetherWith if (animateOutgoingContent) {
                slideOutHorizontally(
                    animationSpec = tween(PAGE_TRANSITION_DURATION_MILLIS),
                    targetOffsetX = ::pageExitOffsetX
                )
            } else {
                ExitTransition.None
            }
        },
        modifier = modifier,
        label = "page-slide-transition"
    ) { page ->
        content(page)
    }
}


internal fun pageEnterOffsetX(width: Int): Int = width

internal fun pageExitOffsetX(width: Int): Int = -width
