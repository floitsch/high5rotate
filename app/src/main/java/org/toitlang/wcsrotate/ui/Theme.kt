// Copyright (C) 2026 Toit contributors.
package org.toitlang.wcsrotate.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = lightColorScheme(
    primary = Color(0xFF6D3FC0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDDFF),
    onPrimaryContainer = Color(0xFF261044),
    secondary = Color(0xFF9A5700),
    secondaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFFFFF8F1),
    surface = Color(0xFFFFF8F1),
    surfaceVariant = Color(0xFFF0E7F2),
    error = Color(0xFFBA1A1A),
)

@Composable
fun WcsRotateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        content = content,
    )
}
