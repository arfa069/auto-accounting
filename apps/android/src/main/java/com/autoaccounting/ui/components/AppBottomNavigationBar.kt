package com.autoaccounting.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class AppBottomNavigationItem(
    val key: String,
    val label: String,
    @param:DrawableRes val iconRes: Int
)

@Composable
fun AppBottomNavigationBar(
    items: List<AppBottomNavigationItem>,
    selectedKey: String?,
    onItemSelected: (String) -> Unit,
    onAddEntry: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    require(items.size == 4) { "AppBottomNavigationBar requires four destination items" }

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val surfaceColor = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NavigationContentHeight + bottomInset)
            .testTag("app-bottom-navigation")
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(NavigationSurfaceHeight + bottomInset)
                .align(Alignment.BottomCenter),
            shape = CenterNotchedBarShape(),
            color = surfaceColor,
            tonalElevation = 0.dp,
            shadowElevation = 4.dp
        ) {}

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(NavigationContentHeight)
                .align(Alignment.TopCenter)
        ) {
            NavigationItem(
                item = items[0],
                selected = selectedKey == items[0].key,
                enabled = enabled,
                onClick = { onItemSelected(items[0].key) }
            )
            NavigationItem(
                item = items[1],
                selected = selectedKey == items[1].key,
                enabled = enabled,
                onClick = { onItemSelected(items[1].key) }
            )
            Spacer(Modifier.weight(1f))
            NavigationItem(
                item = items[2],
                selected = selectedKey == items[2].key,
                enabled = enabled,
                onClick = { onItemSelected(items[2].key) }
            )
            NavigationItem(
                item = items[3],
                selected = selectedKey == items[3].key,
                enabled = enabled,
                onClick = { onItemSelected(items[3].key) }
            )
        }

        FloatingActionButton(
            onClick = {
                if (enabled) onAddEntry()
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(AddButtonSize)
                .semantics {
                    contentDescription = "新增一笔"
                    if (!enabled) disabled()
                }
                .testTag("app-add-entry")
        ) {
            Canvas(Modifier.size(34.dp)) {
                val strokeWidth = 4.dp.toPx()
                val inset = strokeWidth / 2f
                drawLine(
                    color = Color.White,
                    start = Offset(center.x, inset),
                    end = Offset(center.x, size.height - inset),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color.White,
                    start = Offset(inset, center.y),
                    end = Offset(size.width - inset, center.y),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun RowScope.NavigationItem(
    item: AppBottomNavigationItem,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .testTag("app-tab-${item.key}")
            .semantics(mergeDescendants = true) {
                this.selected = selected
            }
            .clickable(
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(item.iconRes),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(52.dp)
        )
        Text(
            text = item.label,
            color = contentColor,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .width(36.dp)
                .height(3.dp)
                .background(
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = CircleShape
                )
        )
    }
}

private class CenterNotchedBarShape(
    private val notchHalfWidth: Dp = 56.dp,
    private val notchDepth: Dp = 28.dp
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val centerX = size.width / 2f
        val halfWidth = with(density) { notchHalfWidth.toPx() }
            .coerceAtMost(size.width / 2f)
        val depth = with(density) { notchDepth.toPx() }
            .coerceAtMost(size.height)
        val shoulder = halfWidth * 0.55f

        return Outline.Generic(
            Path().apply {
                moveTo(0f, 0f)
                lineTo(centerX - halfWidth, 0f)
                cubicTo(
                    centerX - shoulder,
                    0f,
                    centerX - shoulder,
                    depth,
                    centerX,
                    depth
                )
                cubicTo(
                    centerX + shoulder,
                    depth,
                    centerX + shoulder,
                    0f,
                    centerX + halfWidth,
                    0f
                )
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
        )
    }
}

private val NavigationContentHeight = 112.dp
private val NavigationSurfaceHeight = 76.dp
private val AddButtonSize = 72.dp
