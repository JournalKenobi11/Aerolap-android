package com.runwise.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AerolapBlue,
    onPrimary = Color.White,
    primaryContainer = AerolapBlueDark,
    onPrimaryContainer = Color.White,
    secondary = SportWalk,
    onSecondary = Color.Black,
    tertiary = SportHike,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = CardDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = CardBorderDark
)

@Composable
fun AerolapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

@Composable
fun RunwiseTheme(content: @Composable () -> Unit) = AerolapTheme(content)
