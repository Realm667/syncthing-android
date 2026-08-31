package com.nutomic.syncthingandroid.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF9C001E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9DE),
    onPrimaryContainer = Color(0xFF3F000A),
    secondary = Color(0xFF87525C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9DE),
    onSecondaryContainer = Color(0xFF3A0B15),
    tertiary = Color(0xFF77565D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E0),
    onTertiaryContainer = Color(0xFF2D151A),
    background = Color(0xFFFFF8F8),
    onBackground = Color(0xFF25191B),
    surface = Color(0xFFFFF8F8),
    onSurface = Color(0xFF25191B),
    surfaceVariant = Color(0xFFF5DADD),
    onSurfaceVariant = Color(0xFF554044),
    outline = Color(0xFF8A7175),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB3BF),
    onPrimary = Color(0xFF650012),
    primaryContainer = Color(0xFF870019),
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Color(0xFFE8BDC4),
    onSecondary = Color(0xFF43282E),
    secondaryContainer = Color(0xFF5C3E45),
    onSecondaryContainer = Color(0xFFFFD9DE),
    tertiary = Color(0xFFE6BDC4),
    onTertiary = Color(0xFF42292F),
    tertiaryContainer = Color(0xFF5B3F45),
    onTertiaryContainer = Color(0xFFFFD9E0),
    background = Color(0xFF160B0E),
    onBackground = Color(0xFFF1DEE1),
    surface = Color(0xFF160B0E),
    onSurface = Color(0xFFF1DEE1),
    surfaceVariant = Color(0xFF554044),
    onSurfaceVariant = Color(0xFFD8C1C5),
    outline = Color(0xFFA38A8E),
)

@Composable
fun ApplicationTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
