package com.healthscan.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD8BE),
    onPrimary = Color(0xFF06301F),
    secondary = Color(0xFF9FCDF5),
    background = Color(0xFF10161A),
    surface = Color(0xFF161E24),
    onBackground = Color(0xFFE7EDF1),
    onSurface = Color(0xFFE7EDF1),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF136A4E),
    secondary = Color(0xFF2A5F86),
)

@Composable
fun HealthScanTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
