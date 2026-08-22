package com.berns.linuxports.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7FD1B9),
    onPrimary = Color(0xFF04231C),
    secondary = Color(0xFF9BB8C9),
    background = Color(0xFF0B1013),
    surface = Color(0xFF121A1E),
    surfaceVariant = Color(0xFF1C262B),
    onBackground = Color(0xFFE6EDEF),
    onSurface = Color(0xFFE6EDEF),
    error = Color(0xFFFF8A80)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF116B57),
    onPrimary = Color.White,
    background = Color(0xFFF7FAF9),
    surface = Color.White,
    surfaceVariant = Color(0xFFE4EDEA)
)

@Composable
fun BernsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content
    )
}
