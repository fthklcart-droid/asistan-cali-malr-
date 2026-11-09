package com.fk.jarvis.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Arayüz renkleri seçili avatara uyar:
 *  - ART: siyah zemin + neon mavi (holografik büst ile aynı palet)
 *  - NEON: mor/pembe neon
 */
object JarvisColors {
    var artStyle by mutableStateOf(true)

    val bg get() = if (artStyle) Color(0xFF000305) else Color(0xFF07020F)
    val panel get() = if (artStyle) Color(0xCC03121A) else Color(0xCC120624)
    val stroke get() = if (artStyle) Color(0x6646D7FF) else Color(0x66B07CFF)
    /** Ana vurgu rengi (ART'ta neon mavi). */
    val violet get() = if (artStyle) Color(0xFF46D7FF) else Color(0xFFB05CFF)
    /** Asistan yanıt metni rengi. */
    val pink get() = if (artStyle) Color(0xFF9CEFFF) else Color(0xFFEC96FF)
    val gold = Color(0xFFFFC478)
    val text get() = if (artStyle) Color(0xFFE6FAFF) else Color(0xFFEDE3FF)
    val dim get() = if (artStyle) Color(0xFF7FA7B5) else Color(0xFF9A88B8)
    val danger = Color(0xFFFF5A78)
}

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = JarvisColors.violet,
            secondary = JarvisColors.gold,
            background = JarvisColors.bg,
            surface = if (JarvisColors.artStyle) Color(0xFF041820) else Color(0xFF140828),
            onPrimary = Color.Black,
            onSurface = JarvisColors.text,
            onBackground = JarvisColors.text,
            error = JarvisColors.danger,
        ),
        content = content,
    )
}
