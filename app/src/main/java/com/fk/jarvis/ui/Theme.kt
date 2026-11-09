package com.fk.jarvis.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Arayüz renkleri. Vurgu rengi ARAYÜZ bölmesinden seçilir; "auto" seçiliyse avatara uyar
 * (ART: neon mavi, NEON: mor).
 */
object JarvisColors {
    var artStyle by mutableStateOf(true)
    /** "auto", "cyan", "violet", "gold", "green" */
    var accentChoice by mutableStateOf("auto")

    private val key: String get() = if (accentChoice == "auto") (if (artStyle) "cyan" else "violet") else accentChoice

    private val accentColor: Color get() = when (key) {
        "violet" -> Color(0xFFB05CFF)
        "gold" -> Color(0xFFFFC14D)
        "green" -> Color(0xFF3DFFB0)
        else -> Color(0xFF46D7FF)
    }
    private val lightColor: Color get() = when (key) {
        "violet" -> Color(0xFFEC96FF)
        "gold" -> Color(0xFFFFE2A3)
        "green" -> Color(0xFFA8FFE0)
        else -> Color(0xFF9CEFFF)
    }

    val bg: Color get() = Color(0xFF000306)
    val panel: Color get() = Color(0xCC020E16)
    val stroke: Color get() = accentColor.copy(alpha = 0.40f)
    /** Ana vurgu rengi (tarihsel adıyla). */
    val violet: Color get() = accentColor
    /** Asistan yanıt metni rengi. */
    val pink: Color get() = lightColor
    val gold = Color(0xFFFFC478)
    val text = Color(0xFFE6FAFF)
    val dim = Color(0xFF7FA7B5)
    val danger = Color(0xFFFF5A78)
}

/** Holografik efektlerin açık/kapalı durumu (ARAYÜZ bölmesi). */
object HoloFx {
    var animations by mutableStateOf(true)
    var grid by mutableStateOf(true)
}

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = JarvisColors.violet,
            secondary = JarvisColors.gold,
            background = JarvisColors.bg,
            surface = Color(0xFF041820),
            onPrimary = Color.Black,
            onSurface = JarvisColors.text,
            onBackground = JarvisColors.text,
            error = JarvisColors.danger,
        ),
        content = content,
    )
}
