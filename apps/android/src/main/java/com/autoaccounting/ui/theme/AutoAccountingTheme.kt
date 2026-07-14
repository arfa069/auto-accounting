package com.autoaccounting.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val AutoAccountingColors = lightColorScheme(
    primary = Color(0xFF5B5BD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E9FF),
    onPrimaryContainer = Color(0xFF252536),
    secondary = Color(0xFF2D9E90),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF7F1),
    onSecondaryContainer = Color(0xFF173D38),
    tertiary = Color(0xFFFF6F7D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE0E4),
    onTertiaryContainer = Color(0xFF5B2027),
    background = Color(0xFFFFF9EC),
    onBackground = Color(0xFF252536),
    surface = Color(0xFFFFFEFA),
    onSurface = Color(0xFF252536),
    surfaceVariant = Color(0xFFF6F0E5),
    onSurfaceVariant = Color(0xFF625F68),
    outline = Color(0xFF77727D),
    outlineVariant = Color(0xFFD8D1C6),
    error = Color(0xFFDC2626)
)

private val AutoAccountingShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

@Composable
fun AutoAccountingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AutoAccountingColors,
        typography = Typography(),
        shapes = AutoAccountingShapes,
        content = content
    )
}
