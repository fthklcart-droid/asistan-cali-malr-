package com.fk.jarvis.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object JarvisColors {
    val bg = Color(0xFF07020F)
    val panel = Color(0xCC120624)
    val stroke = Color(0x66B07CFF)
    val violet = Color(0xFFB05CFF)
    val pink = Color(0xFFEC96FF)
    val gold = Color(0xFFFFC478)
    val text = Color(0xFFEDE3FF)
    val dim = Color(0xFF9A88B8)
    val danger = Color(0xFFFF5A78)
}

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = JarvisColors.violet,
            secondary = JarvisColors.gold,
            background = JarvisColors.bg,
            surface = Color(0xFF140828),
            onPrimary = Color.Black,
            onSurface = JarvisColors.text,
            onBackground = JarvisColors.text,
            error = JarvisColors.danger,
        ),
        content = content,
    )
}
