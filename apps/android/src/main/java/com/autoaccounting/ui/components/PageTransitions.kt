package com.autoaccounting.ui.components

import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView

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
        HardwareLayerBox {
            content(page)
        }
    }
}

/**
 * Wraps Compose content in an AndroidView that enforces LAYER_TYPE_HARDWARE.
 * This instructs the Android HWUI pipeline to render the content into a dedicated GPU texture (FBO)
 * and cache it. During animations (like translation), the GPU only translates the single textured quad,
 * completely bypassing driver bugs related to binning complex geometry (e.g. Adreno 750 OpenGL bug).
 */
@Composable
private fun HardwareLayerBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AndroidView(
        factory = { context ->
            ComposeView(context).apply {
                // Force a hardware layer (GPU texture) for this View
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                setContent {
                    Box(modifier = modifier) {
                        content()
                    }
                }
            }
        },
        update = { composeView ->
            // Update content in case it changes, though for AnimatedContent it's usually static per page
            composeView.setContent {
                Box(modifier = modifier) {
                    content()
                }
            }
        },
        modifier = modifier
    )
}

internal fun pageEnterOffsetX(width: Int): Int = width

internal fun pageExitOffsetX(width: Int): Int = -width
