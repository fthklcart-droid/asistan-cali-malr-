package com.fk.jarvis.data

import android.content.Context
import com.fk.jarvis.llm.GeminiProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AssistantSettings(
    val assistantName: String = "JARVIS",
    val provider: String = "gemini",           // "gemini" veya "none" (yalnızca yerel komutlar)
    val geminiModel: String = GeminiProvider.DEFAULT_MODEL,
    val silenceTimeoutMs: Int = 1500,           // konuşma bitti sayılmadan önceki sessizlik
    val listenAfterQuestion: Boolean = true,    // onay/açıklama sorusundan sonra otomatik dinle
    val speechRate: Float = 1.0f,
    val keepHistory: Boolean = true,
    val avatarSpeed: Float = 1.0f,
    val avatarSound: Boolean = true,
    val avatarStyle: String = "neon",        // "neon" veya "art"
)

/** Kullanıcı tercihleri (hassas olmayan). API anahtarı burada değil, [SecureKeyStore]'dadır. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AssistantSettings> = _settings.asStateFlow()

    val current: AssistantSettings get() = _settings.value

    private fun load() = AssistantSettings(
        assistantName = prefs.getString("name", null) ?: "JARVIS",
        provider = prefs.getString("provider", null) ?: "gemini",
        geminiModel = prefs.getString("gemini_model", null) ?: GeminiProvider.DEFAULT_MODEL,
        silenceTimeoutMs = prefs.getInt("silence_ms", 1500),
        listenAfterQuestion = prefs.getBoolean("listen_after_q", true),
        speechRate = prefs.getFloat("speech_rate", 1.0f),
        keepHistory = prefs.getBoolean("keep_history", true),
        avatarSpeed = prefs.getFloat("avatar_speed", 1.0f),
        avatarSound = prefs.getBoolean("avatar_sound", true),
        avatarStyle = prefs.getString("avatar_style", null) ?: "neon",
    )

    fun update(transform: (AssistantSettings) -> AssistantSettings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putString("name", s.assistantName)
            .putString("provider", s.provider)
            .putString("gemini_model", s.geminiModel)
            .putInt("silence_ms", s.silenceTimeoutMs.coerceIn(500, 10_000))
            .putBoolean("listen_after_q", s.listenAfterQuestion)
            .putFloat("speech_rate", s.speechRate.coerceIn(0.5f, 2f))
            .putBoolean("keep_history", s.keepHistory)
            .putFloat("avatar_speed", s.avatarSpeed)
            .putBoolean("avatar_sound", s.avatarSound)
            .putString("avatar_style", if (s.avatarStyle == "art") "art" else "neon")
            .apply()
        _settings.value = s
    }
}
