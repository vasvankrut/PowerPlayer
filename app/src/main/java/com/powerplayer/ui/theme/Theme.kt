package com.powerplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ColorScheme = darkColorScheme(
    primary = White,
    background = Black,
    surface = Black,
    onBackground = White,
    onSurface = White,
    onPrimary = Black,
    error = Error,
)

@Composable
fun PowerPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        content = content
    )
}
